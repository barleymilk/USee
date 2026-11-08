package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.event.EventPublisher
import com.ssafy.animal.simulation.application.port.out.AccountRow
import com.ssafy.animal.simulation.application.port.out.ActionLogRepository
import com.ssafy.animal.simulation.application.port.out.BreedRow
import com.ssafy.animal.simulation.application.port.out.CoreReader
import com.ssafy.animal.simulation.application.port.out.InventoryRepository
import com.ssafy.animal.simulation.application.port.out.OccurrenceRepository
import com.ssafy.animal.simulation.application.port.out.ReportRepository
import com.ssafy.animal.simulation.application.port.out.ScenarioRepository
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.application.port.out.ShopItemRepository
import com.ssafy.animal.simulation.application.port.out.SpendingRow
import com.ssafy.animal.simulation.application.port.out.TransactionRepository
import com.ssafy.animal.simulation.application.port.out.UserRow
import com.ssafy.animal.simulation.application.port.out.WalkRepository
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionLog
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.EventOccurrence
import com.ssafy.animal.simulation.domain.EventScenario
import com.ssafy.animal.simulation.domain.InventoryItem
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.Personality
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.ShopItem
import com.ssafy.animal.simulation.domain.SimulationProperties
import com.ssafy.animal.simulation.domain.SimulationReport
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SimulationTransaction
import com.ssafy.animal.simulation.domain.SimulationWalk
import com.ssafy.animal.simulation.domain.SpeciesScope
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.StatCalculator
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import com.ssafy.animal.simulation.domain.TriggerType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** 서비스 테스트용 메모리 저장소. JPA 대신 엔티티를 맵에 들고, IDENTITY는 저장 시 번호를 붙인다 */
class FakeSimulationStore(
    var now: OffsetDateTime = OffsetDateTime.parse("2026-11-01T14:00:00+09:00"),
    /** 0이면 확률 판정이 항상 통과 */
    random: Random = FixedRandom(0.0),
) {
    private val ids = AtomicLong(1000)
    private fun <T : Any> withId(entity: T, field: String): T {
        if (ReflectionTestUtils.getField(entity, field) == null) ReflectionTestUtils.setField(entity, field, ids.incrementAndGet())
        ReflectionTestUtils.setField(entity, "regDttm", now)
        return entity
    }

    val sessionRows = mutableListOf<SimulationSession>()
    val items = mutableListOf<ShopItem>()
    val inventoryRows = mutableListOf<InventoryItem>()
    val transactionRows = mutableListOf<SimulationTransaction>()
    val logs = mutableListOf<ActionLog>()
    val walkRows = mutableListOf<SimulationWalk>()
    val scenarioRows = mutableListOf<EventScenario>()
    val occurrenceRows = mutableListOf<EventOccurrence>()
    val reportRows = mutableListOf<SimulationReport>()
    val breeds = mutableMapOf<Int, BreedRow>()
    val published = mutableListOf<Any>()
    val internalEvents = mutableListOf<Any>()

    val sessions = object : SessionRepository {
        override fun saveAndFlush(session: SimulationSession) = withId(session, "sessionSeq").also { sessionRows += it }
        override fun findById(sessionSeq: Long) = sessionRows.firstOrNull { it.sessionSeq == sessionSeq }
        override fun lock(sessionSeq: Long) = findById(sessionSeq)
        override fun lockActiveByUser(userSeq: Int) = sessionRows.firstOrNull { it.userSeq == userSeq && it.inProgress }
        override fun findPageByUser(userSeq: Int, status: SessionStatus?, pageable: Pageable): Page<SimulationSession> =
            PageImpl(sessionRows.filter { it.userSeq == userSeq && (status == null || it.status == status) }, pageable, 0)
        override fun findInProgressSeqs() = sessionRows.filter { it.inProgress }.map { it.sessionSeq!! }
    }

    val shopItems = object : ShopItemRepository {
        override fun findActive(species: SpeciesType?) = items.filter { it.isActive && (species == null || it.speciesScope.covers(species)) }
        override fun findById(itemSeq: Long) = items.firstOrNull { it.itemSeq == itemSeq }
        override fun findAllById(itemSeqs: Collection<Long>) = items.filter { it.itemSeq in itemSeqs }
    }

    val inventory = object : InventoryRepository {
        override fun findBySession(sessionSeq: Long) = inventoryRows.filter { it.sessionSeq == sessionSeq }
        override fun find(sessionSeq: Long, itemSeq: Long) = inventoryRows.firstOrNull { it.sessionSeq == sessionSeq && it.itemSeq == itemSeq }
        override fun save(item: InventoryItem) = withId(item, "inventoryItemSeq").also { if (it !in inventoryRows) inventoryRows += it }
    }

    val transactions = object : TransactionRepository {
        override fun save(transaction: SimulationTransaction) = withId(transaction, "transactionSeq").also { transactionRows += it }
        override fun findPage(sessionSeq: Long, type: TransactionType?, pageable: Pageable): Page<SimulationTransaction> =
            PageImpl(transactionRows.filter { it.sessionSeq == sessionSeq && (type == null || it.transactionType == type) })
        override fun findSpending(sessionSeq: Long) = transactionRows.filter { it.sessionSeq == sessionSeq }.map { t ->
            SpendingRow(items.firstOrNull { it.itemSeq == t.itemSeq }?.category, t.amount, t.regDttm!!)
        }
    }

    val actionLogs = object : ActionLogRepository {
        override fun save(log: ActionLog) = withId(log, "actionLogSeq").also { logs += it }
        override fun findPage(sessionSeq: Long, type: ActionType?, pageable: Pageable): Page<ActionLog> =
            PageImpl(logs.filter { it.sessionSeq == sessionSeq && (type == null || it.actionType == type) })
        override fun countUserActions(sessionSeq: Long) = logs.count {
            it.sessionSeq == sessionSeq && (it.actionType == ActionType.CARE || it.actionCode in ActionCode.WALK_USER_ACTIONS)
        }
    }

    val walks = object : WalkRepository {
        override fun save(walk: SimulationWalk) = withId(walk, "walkSeq").also { walkRows += it }
        override fun findById(walkSeq: Long) = walkRows.firstOrNull { it.walkSeq == walkSeq }
        override fun findActive(sessionSeq: Long) = walkRows.firstOrNull { it.sessionSeq == sessionSeq && it.inProgress }
        override fun findStale(before: OffsetDateTime) = walkRows.filter { it.inProgress && it.lastSeen.isBefore(before) }
        override fun countBySession(sessionSeq: Long) = walkRows.count { it.sessionSeq == sessionSeq }
    }

    val scenarios = object : ScenarioRepository {
        override fun findActive(context: TriggerContext) = scenarioRows.filter { it.isActive && it.triggerContext == context }
        override fun findAllById(scenarioSeqs: Collection<Long>) = scenarioRows.filter { it.scenarioSeq in scenarioSeqs }
    }

    val occurrences = object : OccurrenceRepository {
        override fun save(occurrence: EventOccurrence) = withId(occurrence, "occurrenceSeq").also { occurrenceRows += it }
        override fun findPending(sessionSeq: Long) =
            occurrenceRows.firstOrNull { it.sessionSeq == sessionSeq && it.status == OccurrenceStatus.PENDING }
        override fun findPage(sessionSeq: Long, status: OccurrenceStatus?, pageable: Pageable): Page<EventOccurrence> =
            PageImpl(occurrenceRows.filter { it.sessionSeq == sessionSeq && (status == null || it.status == status) })
        override fun lastOccurredAt(sessionSeq: Long, scenarioSeq: Long) =
            occurrenceRows.filter { it.sessionSeq == sessionSeq && it.scenarioSeq == scenarioSeq }.maxOfOrNull { it.occurredDttm }
        override fun countByWalk(walkSeq: Long) = occurrenceRows.count { it.walkSeq == walkSeq }
        override fun countBySession(sessionSeq: Long, status: OccurrenceStatus?) =
            occurrenceRows.count { it.sessionSeq == sessionSeq && (status == null || it.status == status) }
    }

    val reports = object : ReportRepository {
        override fun save(report: SimulationReport) = withId(report, "reportSeq").also { reportRows += it }
        override fun findBySession(sessionSeq: Long) = reportRows.firstOrNull { it.sessionSeq == sessionSeq }
        override fun findById(reportSeq: Long) = reportRows.firstOrNull { it.reportSeq == reportSeq }
        override fun existingSessionSeqs(sessionSeqs: Collection<Long>) = reportRows.map { it.sessionSeq }.filter { it in sessionSeqs }.toSet()
        override fun findPage(userSeq: Int?, pageable: Pageable): Page<Pair<SimulationReport, SimulationSession>> =
            PageImpl(reportRows.map { r -> r to sessions.findById(r.sessionSeq)!! }.filter { userSeq == null || it.second.userSeq == userSeq })
        override fun findSummaryMissing(endedBefore: OffsetDateTime) = reportRows.filter { it.summary == null }.map { it.reportSeq!! }
        override fun fillSummary(reportSeq: Long, summary: String): Boolean {
            val report = findById(reportSeq)?.takeIf { it.summary == null } ?: return false
            ReflectionTestUtils.setField(report, "summary", summary)
            return true
        }
    }

    val coreReader = object : CoreReader {
        override fun findBreed(breedSeq: Int) = breeds[breedSeq]
        override fun findBreeds(breedSeqs: Collection<Int>) = breeds.filterKeys { it in breedSeqs }
        override fun findUsers(userSeqs: Collection<Int>) = emptyMap<Int, UserRow>()
        override fun findAccount(userSeq: Int): AccountRow? = null
    }

    val props = SimulationProperties()

    val eventPublisher = object : EventPublisher {
        override fun publish(event: Any) {
            published += event
        }
    }

    val engine = SimulationEngine(
        props, StatCalculator(props), object : Clock() {
            override fun getZone() = ZoneId.of("Asia/Seoul")
            override fun withZone(zone: ZoneId?) = this
            override fun instant() = now.toInstant()
        },
        shopItems, inventory, transactions, actionLogs, walks, scenarios, occurrences, reports,
        eventPublisher,
        ApplicationEventPublisher { internalEvents += it },
        random,
    )

    val imageUrls = ImageUrls("https://cdn.test")
    val views = SimulationViewAssembler(engine, coreReader, occurrences, scenarios, walks, imageUrls)

    // ===== 픽스처 =====

    fun breed(seq: Int, species: String, personality: Personality = Personality.FRIENDLY) =
        BreedRow(seq, "품종$seq", species, personality.name, null).also { breeds[seq] = it }

    fun session(userSeq: Int = 7, species: SpeciesType = SpeciesType.DOG, personality: Personality = Personality.FRIENDLY, balance: Int = 100000) =
        sessions.saveAndFlush(
            SimulationSession(userSeq, 1, "초코", species, personality, balance, now, now.plus(props.sessionLength), engine.initialStats()),
        )

    fun item(category: ItemCategory, scope: SpeciesScope = SpeciesScope.ALL, price: Int = 10000, effects: Map<String, Int>? = null) =
        ShopItem(
            ids.incrementAndGet(), category.name, category, !category.equipment, price, null, "images/${category.name}.png",
            scope, effects, true, items.size,
        ).also { items += it }

    fun own(session: SimulationSession, item: ShopItem, quantity: Int = 1) =
        inventory.save(InventoryItem(session.sessionSeq!!, item.itemSeq, quantity))

    fun scenario(
        resolve: ActionCode,
        context: TriggerContext = TriggerContext.HOME,
        type: TriggerType = TriggerType.RANDOM,
        cost: Int = 0,
        condition: Map<String, Any>? = null,
        resolveEffects: Map<String, Int>? = mapOf("health" to 30),
        neglectEffects: Map<String, Int>? = mapOf("health" to -10),
    ) = EventScenario(
        ids.incrementAndGet(), "시나리오 ${resolve.name}", "설명", context, type, condition, null, BigDecimal.ONE,
        SpeciesScope.ALL, null, 1, resolve, cost, resolveEffects, "해결 문구", neglectEffects, "방치 문구", true,
    ).also { scenarioRows += it }

    fun pending(session: SimulationSession, scenario: EventScenario, walkSeq: Long? = null) =
        occurrences.save(EventOccurrence(session.sessionSeq!!, scenario.scenarioSeq, walkSeq, now, now.plus(props.event.homeTtl)))
}

/** nextDouble이 항상 같은 값을 내는 Random(확률 판정 고정) */
class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int) = 0
    override fun nextDouble() = value
}
