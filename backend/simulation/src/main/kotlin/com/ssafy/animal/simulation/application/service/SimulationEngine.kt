package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.event.EventPublisher
import com.ssafy.animal.common.event.SimulationPushRequested
import com.ssafy.animal.common.event.SimulationPushType
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.exception.CommonErrorCode
import com.ssafy.animal.common.response.ErrorDetail
import com.ssafy.animal.simulation.application.port.`in`.ActionResult
import com.ssafy.animal.simulation.application.port.`in`.ConsumedItem
import com.ssafy.animal.simulation.application.port.`in`.ResolvedEvent
import com.ssafy.animal.simulation.application.port.`in`.StatsView
import com.ssafy.animal.simulation.application.port.out.ActionLogRepository
import com.ssafy.animal.simulation.application.port.out.InventoryRepository
import com.ssafy.animal.simulation.application.port.out.OccurrenceRepository
import com.ssafy.animal.simulation.application.port.out.ReportRepository
import com.ssafy.animal.simulation.application.port.out.ScenarioRepository
import com.ssafy.animal.simulation.application.port.out.ShopItemRepository
import com.ssafy.animal.simulation.application.port.out.TransactionRepository
import com.ssafy.animal.simulation.application.port.out.WalkRepository
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionLog
import com.ssafy.animal.simulation.domain.EventOccurrence
import com.ssafy.animal.simulation.domain.EventScenario
import com.ssafy.animal.simulation.domain.InventoryItem
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.ReportCreated
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.ShopItem
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SimulationProperties
import com.ssafy.animal.simulation.domain.SimulationReport
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SimulationTransaction
import com.ssafy.animal.simulation.domain.SimulationWalk
import com.ssafy.animal.simulation.domain.StatCalculator
import com.ssafy.animal.simulation.domain.StatKey
import com.ssafy.animal.simulation.domain.Stats
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import com.ssafy.animal.simulation.domain.TriggerType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * 세션 상태를 바꾸는 공통 규칙(API 명세 21.5). 트랜잭션은 호출하는 서비스가 열고, 세션은 미리 잠가(FOR UPDATE) 넘긴다.
 * 검사는 모두 변경 전에 끝내서, 예외가 나도 지연 계산 결과 외에는 바뀐 것이 없게 한다(서비스는 BusinessException에 롤백하지 않음).
 */
@Component
class SimulationEngine(
    private val props: SimulationProperties,
    private val calculator: StatCalculator,
    private val clock: Clock,
    private val shopItems: ShopItemRepository,
    private val inventory: InventoryRepository,
    private val transactions: TransactionRepository,
    private val actionLogs: ActionLogRepository,
    private val walks: WalkRepository,
    private val scenarios: ScenarioRepository,
    private val occurrences: OccurrenceRepository,
    private val reports: ReportRepository,
    private val eventPublisher: EventPublisher,
    private val applicationEvents: ApplicationEventPublisher,
    private val random: Random = Random.Default,
) {

    fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    /**
     * 조회·행동·스케줄러 공통(21.5의 2·5): 지연 계산 → 종료 판정 → 만료(방치 효과) → CONDITION 판정. 계속 진행 중이면 true
     */
    fun refresh(session: SimulationSession, now: OffsetDateTime = now()): Boolean {
        if (!session.inProgress) return false
        val advanced = calculator.advance(
            session.stats, session.startedDttm, session.statsCalculatedDttm, minOf(now, session.endsDttm), session.personality,
        )
        session.recalculated(advanced.stats, advanced.calculatedAt)
        when {
            advanced.depleted -> end(session, SessionStatus.FAILED, advanced.calculatedAt)
            !now.isBefore(session.endsDttm) -> end(session, SessionStatus.COMPLETED, session.endsDttm)
        }
        if (!session.inProgress) return false
        occurrences.findPending(session.seq)?.takeIf { it.expired(now) }?.let { expireWithNeglect(session, it, now) }
        if (!session.inProgress) return false
        triggerCondition(session, now)
        return true
    }

    // ===== 이벤트(18.12) =====

    fun scenarioOf(occurrence: EventOccurrence): EventScenario = scenarios.findAllById(listOf(occurrence.scenarioSeq)).single()

    /** 시한 경과·산책 종료: 방치 효과 → EXPIRED → EVENT_EXPIRE 로그. health 0이면 FAILED(Q486) */
    fun expireWithNeglect(session: SimulationSession, occurrence: EventOccurrence, now: OffsetDateTime) {
        val before = session.stats
        val after = before + calculator.neglect(scenarioOf(occurrence).neglectEffects, session.personality)
        session.stats = after
        occurrence.expire()
        log(session, ActionCode.EVENT_EXPIRE, occurrenceSeq = occurrence.occurrenceSeq, delta = after.diff(before))
        if (after.health == 0) end(session, SessionStatus.FAILED, now)
    }

    /** HOME·CONDITION: 조건 → 쿨다운 → trigger_probability 추첨(Q481). PENDING이 있으면 만들지 않는다 */
    fun triggerCondition(session: SimulationSession, now: OffsetDateTime) {
        if (occurrences.findPending(session.seq) != null) return
        val scenario = scenarios.findActive(TriggerContext.HOME).firstOrNull {
            it.triggerType == TriggerType.CONDITION && it.appliesTo(session.speciesType, session.personality) &&
                it.conditionMet(session.stats) && !coolingDown(session, it, now) &&
                random.nextDouble() < it.triggerProbability.toDouble()
        } ?: return
        occur(session, scenario, null, now)
    }

    /** HOME·RANDOM: 스케줄러 1회당 하루 평균 횟수 / 하루 실행 횟수 확률로 가중치 추첨(가안 하루 1~2회) */
    fun triggerRandomHome(session: SimulationSession, now: OffsetDateTime) {
        if (occurrences.findPending(session.seq) != null) return
        val runsPerDay = Duration.ofDays(1).dividedBy(SimulationProperties.SCHEDULER_INTERVAL)
        if (random.nextDouble() >= props.event.homeRandomPerDay / runsPerDay) return
        val candidates = scenarios.findActive(TriggerContext.HOME).filter {
            it.triggerType == TriggerType.RANDOM && it.appliesTo(session.speciesType, session.personality) && !coolingDown(session, it, now)
        }
        pick(candidates)?.let { occur(session, it, null, now) }
    }

    /** 하트비트(18.15): 산책당 최대 N회, 하트비트마다 확률로 WALK 시나리오 가중치 추첨. PENDING이 있으면 null */
    fun triggerWalk(session: SimulationSession, walk: SimulationWalk, now: OffsetDateTime): EventOccurrence? {
        if (occurrences.findPending(session.seq) != null) return null
        if (occurrences.countByWalk(walk.seq) >= props.event.walkMaxPerWalk) return null
        if (random.nextDouble() >= props.event.walkChancePerHeartbeat) return null
        val candidates = scenarios.findActive(TriggerContext.WALK).filter {
            it.triggerType == TriggerType.RANDOM && it.appliesTo(session.speciesType, session.personality)
        }
        return pick(candidates)?.let { occur(session, it, walk.seq, now) }
    }

    private fun coolingDown(session: SimulationSession, scenario: EventScenario, now: OffsetDateTime): Boolean {
        val cooldown = scenario.cooldownHours?.let { Duration.ofHours(it.toLong()) }
            ?: if (scenario.triggerType == TriggerType.CONDITION) props.event.defaultCooldown else return false
        val last = occurrences.lastOccurredAt(session.seq, scenario.scenarioSeq) ?: return false
        return last.plus(cooldown).isAfter(now)
    }

    private fun pick(candidates: List<EventScenario>): EventScenario? {
        if (candidates.isEmpty()) return null
        var roll = random.nextInt(candidates.sumOf { it.weight.toInt() })
        return candidates.first { roll -= it.weight.toInt(); roll < 0 }
    }

    private fun occur(session: SimulationSession, scenario: EventScenario, walkSeq: Long?, now: OffsetDateTime): EventOccurrence {
        val ttl = if (scenario.triggerContext == TriggerContext.HOME) props.event.homeTtl else props.event.walkTtl
        val occurrence = occurrences.save(EventOccurrence(session.seq, scenario.scenarioSeq, walkSeq, now, now.plus(ttl)))
        // HOME만 푸시(산책 이벤트는 하트비트 응답으로 전달, Q481). 커밋 후에만 나간다
        if (scenario.triggerContext == TriggerContext.HOME) {
            eventPublisher.publish(
                SimulationPushRequested(session.userSeq.toLong(), SimulationPushType.EVENT, PUSH_TITLE, "${session.animalName}: ${scenario.title}"),
            )
        }
        return occurrence
    }

    // ===== 행동(18.6·18.16) =====

    data class Owned(val inventory: InventoryItem, val item: ShopItem)

    fun owned(sessionSeq: Long): List<Owned> {
        val rows = inventory.findBySession(sessionSeq)
        val items = shopItems.findAllById(rows.map { it.itemSeq }).associateBy { it.itemSeq }
        return rows.mapNotNull { row -> items[row.itemSeq]?.let { Owned(row, it) } }
    }

    /** 세션은 잠겨 있고 진행 중이어야 한다. [walk]가 있으면 산책 중 행동 */
    fun act(session: SimulationSession, code: ActionCode, itemSeq: Long?, walk: SimulationWalk?): ActionResult {
        val now = now()
        // --- 검사(변경 없음) ---
        val pending = occurrences.findPending(session.seq)
        val scenario = pending?.let(::scenarioOf)
        val resolves = pending != null && scenario != null && scenario.resolveActionCode == code &&
            (if (scenario.triggerContext == TriggerContext.WALK) walk != null && pending.walkSeq == walk.walkSeq else walk == null)
        if (code == ActionCode.VET_VISIT && !resolves) throw BusinessException(SimulationErrorCode.SIMULATION_VET_NOT_NEEDED)

        val owned = owned(session.seq).filter { it.inventory.quantity > 0 }
        code.equipment?.let { equipment ->
            if (owned.none { it.item.category == equipment }) throw BusinessException(SimulationErrorCode.SIMULATION_EQUIPMENT_MISSING)
        }
        val used: Owned? = when {
            code.consumable != null -> {
                val item = requireItem(code.consumable, itemSeq, session)
                owned.firstOrNull { it.item.itemSeq == item.itemSeq }
                    ?: throw BusinessException(SimulationErrorCode.SIMULATION_ITEM_NOT_IN_INVENTORY)
            }
            // PLAY: 고른 장난감, 생략하면 보유 장난감 중 첫 번째
            code == ActionCode.PLAY && itemSeq != null -> {
                val item = requireItem(ItemCategory.TOY, itemSeq, session)
                owned.firstOrNull { it.item.itemSeq == item.itemSeq }
                    ?: throw BusinessException(SimulationErrorCode.SIMULATION_EQUIPMENT_MISSING)
            }
            code == ActionCode.PLAY -> owned.first { it.item.category == ItemCategory.TOY }
            else -> null
        }

        // --- 반영 ---
        val effects = when (code) {
            ActionCode.WATER -> props.waterEffect
            ActionCode.GIVE_WATER -> props.giveWaterEffect
            else -> used?.item?.effects.orEmpty()
        }
        val before = session.stats
        val afterItem = before + calculator.gain(effects, session.personality)
        val after = if (resolves) afterItem + calculator.gain(scenario!!.resolveEffects, session.personality) else afterItem
        if (used != null && !used.item.category.equipment) used.inventory.consumeOne()
        session.stats = after
        val log = log(
            session, code, itemSeq = used?.item?.itemSeq, walkSeq = walk?.walkSeq,
            occurrenceSeq = if (resolves) pending!!.occurrenceSeq else null, delta = after.diff(before),
        )
        val resolved = if (resolves) {
            val cost = scenario!!.resolveCost
            if (cost > 0) {
                val balance = session.spend(cost)
                transactions.save(
                    SimulationTransaction(session.seq, TransactionType.EVENT, null, pending!!.occurrenceSeq, 1, cost, balance, "${scenario.title} 진료비"),
                )
            }
            pending!!.resolve(log.actionLogSeq!!, now)
            ResolvedEvent(pending.occurrenceSeq!!, scenario.title, scenario.resultText, after.diff(afterItem), cost)
        } else {
            null
        }
        session.acted(now)
        if (after.health == 0) end(session, SessionStatus.FAILED, now)
        return ActionResult(
            actionLogSeq = log.actionLogSeq!!,
            actionType = code.type,
            actionCode = code,
            walkSeq = walk?.walkSeq,
            consumedItem = used?.takeIf { !it.item.category.equipment }
                ?.let { ConsumedItem(it.item.itemSeq, it.item.itemName, it.inventory.quantity) },
            statDelta = after.diff(before),
            stats = StatsView.of(after, session.statsCalculatedDttm),
            balance = session.balance,
            resolvedEvent = resolved,
        )
    }

    /** 행동·구매 공통 물품 검사: 누락 400, 없음 404, 분류 불일치 400, 종 불일치 409 */
    fun requireItem(category: ItemCategory?, itemSeq: Long?, session: SimulationSession, activeOnly: Boolean = false): ShopItem {
        itemSeq ?: throw invalid("itemSeq", "이 행동에는 물품이 필요합니다.")
        val item = shopItems.findById(itemSeq)?.takeIf { !activeOnly || it.isActive }
            ?: throw BusinessException(SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOUND)
        if (category != null && item.category != category) throw invalid("itemSeq", "행동과 물품 분류가 맞지 않습니다.")
        if (!item.speciesScope.covers(session.speciesType)) throw BusinessException(SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOR_SPECIES)
        return item
    }

    // ===== 산책 종료(18.17·자동 종료) =====

    data class WalkOutcome(val statDelta: Map<String, Int>, val eventCount: Int)

    /** 길이·성격 배율로 효과를 한 번에 반영 → WALK_END 로그 → 이 산책의 PENDING 이벤트는 방치 효과 후 EXPIRED(Q486) */
    fun endWalk(session: SimulationSession, walk: SimulationWalk, at: OffsetDateTime): WalkOutcome {
        val before = session.stats
        val after = before + calculator.walk(Duration.between(walk.startedDttm, at), session.personality)
        walk.end(at)
        session.stats = after
        log(session, ActionCode.WALK_END, walkSeq = walk.walkSeq, delta = after.diff(before))
        occurrences.findPending(session.seq)?.takeIf { it.walkSeq == walk.walkSeq }?.let { expireWithNeglect(session, it, at) }
        if (session.inProgress && after.health == 0) end(session, SessionStatus.FAILED, at)
        return WalkOutcome(after.diff(before), occurrences.countByWalk(walk.seq))
    }

    // ===== 종료(21.5의 6) =====

    /** 진행 중 산책 ENDED·PENDING EXPIRED(효과 없음) → 상태 → 리포트(summary NULL) → SESSION_END 로그 → 커밋 후 요약 */
    fun end(session: SimulationSession, status: SessionStatus, at: OffsetDateTime): SimulationReport {
        walks.findActive(session.seq)?.end(at)
        occurrences.findPending(session.seq)?.expire()
        session.end(status, at)
        val report = reports.save(newReport(session))
        log(session, ActionCode.SESSION_END)
        applicationEvents.publishEvent(ReportCreated(report.reportSeq!!))
        return report
    }

    /** 저장 리포트와 중간 리포트(18.18 isFinal=false)가 같이 쓰는 집계 */
    fun newReport(session: SimulationSession): SimulationReport {
        val spending = transactions.findSpending(session.seq)
        val byCategory = (ItemCategory.entries.map { it.name } + EVENT_KEY).associateWith { 0 }.toMutableMap()
        val days = props.sessionLength.toDays().toInt()
        val daily = (1..days).associate { it.toString() to 0 }.toMutableMap()
        spending.forEach {
            byCategory.merge(it.category?.name ?: EVENT_KEY, it.amount, Int::plus)
            val day = (Duration.between(session.startedDttm, it.regDttm).toDays() + 1).toInt().coerceIn(1, days)
            daily.merge(day.toString(), it.amount, Int::plus)
        }
        return SimulationReport(
            sessionSeq = session.seq,
            expectedCost = session.expectedCost,
            totalSpent = spending.sumOf { it.amount },
            spentByCategoryJsonb = byCategory,
            dailySpentJsonb = daily,
            finalStatsJsonb = session.stats.toMap(),
            actionCount = actionLogs.countUserActions(session.seq),
            eventCount = occurrences.countBySession(session.seq),
            eventResolvedCount = occurrences.countBySession(session.seq, OccurrenceStatus.RESOLVED),
            walkCount = walks.countBySession(session.seq),
        )
    }

    fun log(
        session: SimulationSession,
        code: ActionCode,
        itemSeq: Long? = null,
        walkSeq: Long? = null,
        occurrenceSeq: Long? = null,
        delta: Map<String, Int>? = null,
    ): ActionLog = actionLogs.save(
        ActionLog(session.seq, code.type, code, itemSeq, walkSeq, occurrenceSeq, delta?.takeIf { it.isNotEmpty() }, session.stats.toMap()),
    )

    fun initialStats(): Stats = Stats.of(props.initialStats.withDefaults())

    private fun Map<StatKey, Int>.withDefaults() = StatKey.entries.associateWith { this[it] ?: 0 }

    private fun invalid(field: String, reason: String) =
        BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, errors = listOf(ErrorDetail(field, reason)))

    companion object {
        const val EVENT_KEY = "EVENT"
        const val PUSH_TITLE = "모의 입양"
    }
}

/** 저장된 엔티티의 PK(저장 전 접근은 버그) */
val SimulationSession.seq: Long get() = checkNotNull(sessionSeq)
val SimulationWalk.seq: Long get() = checkNotNull(walkSeq)
