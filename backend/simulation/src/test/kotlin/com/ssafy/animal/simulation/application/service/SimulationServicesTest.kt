package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.event.SimulationPushRequested
import com.ssafy.animal.common.event.SimulationPushType
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.exception.CommonErrorCode
import com.ssafy.animal.common.exception.ErrorCode
import com.ssafy.animal.simulation.application.port.`in`.ActionCommand
import com.ssafy.animal.simulation.application.port.`in`.StartSessionCommand
import com.ssafy.animal.simulation.adapter.out.summary.RuleReportSummarizer
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.Personality
import com.ssafy.animal.simulation.domain.ReportCreated
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SpeciesScope
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import com.ssafy.animal.simulation.domain.TriggerType
import com.ssafy.animal.simulation.domain.WalkStatus
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationServicesTest {

    private val store = FakeSimulationStore()
    private val sessionService = SimulationSessionService(store.props, store.engine, store.views, store.sessions, store.reports, store.coreReader)
    private val careService = SimulationCareService(
        store.engine, store.views, store.imageUrls, store.sessions, store.shopItems, store.inventory, store.transactions,
        store.actionLogs, store.occurrences,
    )
    private val walkService = SimulationWalkService(store.props, store.engine, store.views, store.sessions, store.walks, store.occurrences)
    private val reportService = SimulationReportService(store.engine, store.views, store.sessions, store.reports, store.coreReader)
    private val summaryService = ReportSummaryService(store.reports, store.sessions, RuleReportSummarizer(), store.eventPublisher)

    private fun assertCode(code: ErrorCode, block: () -> Unit) =
        assertEquals(code, assertFailsWith<BusinessException> { block() }.errorCode)

    // ===== 18.1~18.5 =====

    @Test
    fun `세션 시작은 품종의 종·성격을 스냅샷하고 초기 스탯·잔액·7일 종료로 만든다`() {
        store.breed(21, "DOG", Personality.ACTIVE)
        val detail = sessionService.start(StartSessionCommand(7, 21, "초코", 150000))
        assertEquals(SpeciesType.DOG, detail.speciesType)
        assertEquals(Personality.ACTIVE, detail.personality)
        assertEquals(150000, detail.balance)
        assertEquals(store.now.plusDays(7), detail.endsDttm)
        assertEquals(70, detail.stats.satiety)
        assertEquals("품종21", detail.breed.breedName)
        assertEquals(ActionCode.SESSION_START, store.logs.single().actionCode)
    }

    @Test
    fun `세션 시작 실패 - 품종 없음 404, ETC 409, 진행 중 409`() {
        assertCode(SimulationErrorCode.SIMULATION_BREED_NOT_FOUND) { sessionService.start(StartSessionCommand(7, 99, "초코", 0)) }
        store.breed(3, "ETC")
        assertCode(SimulationErrorCode.SIMULATION_SPECIES_NOT_SUPPORTED) { sessionService.start(StartSessionCommand(7, 3, "초코", 0)) }
        store.breed(1, "DOG")
        sessionService.start(StartSessionCommand(7, 1, "초코", 0))
        assertCode(SimulationErrorCode.SIMULATION_ACTIVE_ALREADY_EXISTS) { sessionService.start(StartSessionCommand(7, 1, "보리", 0)) }
    }

    @Test
    fun `돌보지 않은 세션은 조회 시 지연 계산으로 FAILED 종료되고 한 번만 돌려준 뒤 null`() {
        val session = store.session()
        store.now = store.now.plusDays(8)
        val detail = assertNotNull(sessionService.findActive(7))
        assertEquals(SessionStatus.FAILED, detail.status)
        assertEquals(0, detail.stats.health)
        assertTrue(detail.endedDttm!!.isBefore(session.endsDttm))
        assertEquals(1, store.reportRows.size)
        assertTrue(store.internalEvents.single() is ReportCreated)
        assertNull(sessionService.findActive(7))
    }

    @Test
    fun `남의 세션은 404 SIMULATION_NOT_FOUND`() {
        val session = store.session(userSeq = 8)
        assertCode(SimulationErrorCode.SIMULATION_NOT_FOUND) { sessionService.get(7, session.sessionSeq!!) }
    }

    @Test
    fun `포기는 산책을 끝내고 PENDING을 방치 효과 없이 만료한 뒤 리포트를 만든다`() {
        val session = store.session()
        val walk = walkService.start(7, session.sessionSeq!!)
        val pending = store.pending(session, store.scenario(ActionCode.FEED))
        val healthBefore = session.stats.health

        val result = sessionService.abandon(7, session.sessionSeq!!)

        assertEquals(SessionStatus.ABANDONED, result.status)
        assertEquals(OccurrenceStatus.EXPIRED, pending.status)
        assertEquals(healthBefore, session.stats.health)
        assertEquals(WalkStatus.ENDED, store.walks.findById(walk.walkSeq)!!.status)
        assertNull(result.report.summary)
        assertEquals(1, result.report.walkCount)
        assertCode(SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS) { sessionService.abandon(7, session.sessionSeq!!) }
    }

    // ===== 18.6 행동 =====

    @Test
    fun `FEED는 밥그릇이 없으면 409, 사료 없으면 409, itemSeq 누락은 400`() {
        val session = store.session()
        val food = store.item(ItemCategory.FOOD, effects = mapOf("satiety" to 30))
        assertCode(SimulationErrorCode.SIMULATION_EQUIPMENT_MISSING) { careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.FEED, food.itemSeq)) }
        store.own(session, store.item(ItemCategory.FOOD_BOWL))
        assertCode(SimulationErrorCode.SIMULATION_ITEM_NOT_IN_INVENTORY) { careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.FEED, food.itemSeq)) }
        assertCode(CommonErrorCode.COMMON_INVALID_INPUT) { careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.FEED, null)) }
        assertCode(CommonErrorCode.COMMON_INVALID_INPUT) { careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.GIVE_WATER, null)) }
    }

    @Test
    fun `FEED는 사료를 하나 소모하고 효과를 반영해 로그를 남긴다(돈은 안 쓴다)`() {
        val session = store.session()
        store.own(session, store.item(ItemCategory.FOOD_BOWL))
        val food = store.item(ItemCategory.FOOD, effects = mapOf("satiety" to 30, "bond" to 1))
        store.own(session, food, quantity = 2)

        val result = careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.FEED, food.itemSeq))

        assertEquals(1, result.consumedItem!!.remainingQuantity)
        assertEquals(mapOf("satiety" to 30, "bond" to 2), result.statDelta) // FRIENDLY 친밀도 회복 1.5
        assertEquals(100000, result.balance)
        assertNull(result.resolvedEvent)
        assertEquals(store.now, session.lastActionDttm)
    }

    @Test
    fun `종 범위가 다른 물품은 409`() {
        val session = store.session(species = SpeciesType.DOG)
        val catSnack = store.item(ItemCategory.SNACK, SpeciesScope.CAT)
        assertCode(SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOR_SPECIES) { careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.SNACK, catSnack.itemSeq)) }
    }

    @Test
    fun `정답이 병원인 PENDING이 없으면 VET_VISIT은 409이고 로그를 남기지 않는다`() {
        val session = store.session()
        store.pending(session, store.scenario(ActionCode.FEED))
        val logs = store.logs.size
        assertCode(SimulationErrorCode.SIMULATION_VET_NOT_NEEDED) { careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.VET_VISIT, null)) }
        assertEquals(logs, store.logs.size)
    }

    @Test
    fun `VET_VISIT 정답이면 이벤트를 해결하고 진료비를 EVENT 거래로 차감한다(음수 허용)`() {
        val session = store.session(balance = 10000)
        val pending = store.pending(session, store.scenario(ActionCode.VET_VISIT, cost = 30000, resolveEffects = mapOf("health" to 5)))

        val result = careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.VET_VISIT, null))

        assertEquals(OccurrenceStatus.RESOLVED, pending.status)
        assertEquals(result.actionLogSeq, pending.resolvedActionLogSeq)
        assertEquals(-20000, result.balance)
        assertEquals(30000, result.resolvedEvent!!.eventCost)
        assertEquals(mapOf("health" to 5), result.resolvedEvent!!.eventStatDelta)
        val transaction = store.transactionRows.single()
        assertEquals(TransactionType.EVENT, transaction.transactionType)
        assertEquals(pending.occurrenceSeq, transaction.occurrenceSeq)
    }

    @Test
    fun `오답 행동은 이벤트를 건드리지 않는다`() {
        val session = store.session()
        store.own(session, store.item(ItemCategory.WATER_BOWL))
        val pending = store.pending(session, store.scenario(ActionCode.PLAY))
        val result = careService.act(7, session.sessionSeq!!, ActionCommand(ActionCode.WATER, null))
        assertNull(result.resolvedEvent)
        assertEquals(OccurrenceStatus.PENDING, pending.status)
    }

    // ===== 18.9 구매·18.12 이벤트 =====

    @Test
    fun `기구 재구매는 409, 잔액은 음수까지 허용`() {
        val session = store.session(balance = 5000)
        val bowl = store.item(ItemCategory.FOOD_BOWL, price = 12000)
        val result = careService.purchase(7, session.sessionSeq!!, bowl.itemSeq, 1)
        assertEquals(-7000, result.balanceAfter)
        assertEquals(1, result.inventoryQuantity)
        assertCode(SimulationErrorCode.SIMULATION_EQUIPMENT_ALREADY_OWNED) { careService.purchase(7, session.sessionSeq!!, bowl.itemSeq, 1) }
        assertCode(SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOUND) { careService.purchase(7, session.sessionSeq!!, 424242, 1) }
    }

    @Test
    fun `소모품은 수량만큼 쌓인다`() {
        val session = store.session()
        val food = store.item(ItemCategory.FOOD, price = 8000)
        careService.purchase(7, session.sessionSeq!!, food.itemSeq, 2)
        val result = careService.purchase(7, session.sessionSeq!!, food.itemSeq, 3)
        assertEquals(5, result.inventoryQuantity)
        assertEquals(24000, result.amount)
    }

    @Test
    fun `이벤트 조회는 시한이 지난 PENDING에 방치 효과를 적용하고 EXPIRED로 바꾼다`() {
        val session = store.session()
        val pending = store.pending(session, store.scenario(ActionCode.FEED, neglectEffects = mapOf("bond" to -10)))
        store.now = store.now.plusMinutes(6 * 60 + 30) // tick 6번(bond -6) + 방치 -10, FRIENDLY는 친밀도 방치 배율 없음
        val events = careService.events(7, session.sessionSeq!!, null, Pageable.unpaged())
        assertEquals(OccurrenceStatus.EXPIRED, events.content.first { it.occurrenceSeq == pending.occurrenceSeq }.status)
        assertEquals("방치 문구", events.content.first().resultText)
        assertEquals(30 - 6 - 10, session.stats.bond)
        assertEquals(ActionCode.EVENT_EXPIRE, store.logs.last().actionCode)
    }

    @Test
    fun `CONDITION 시나리오는 조건이 맞으면 확률 판정 후 발생하고 HOME 이벤트 푸시를 낸다`() {
        val session = store.session()
        store.scenario(ActionCode.VET_VISIT, type = TriggerType.CONDITION, cost = 30000, condition = mapOf("stat" to "health", "op" to "<", "value" to 95))
        sessionService.get(7, session.sessionSeq!!)
        assertNotNull(store.occurrences.findPending(session.sessionSeq!!))
        val push = store.published.single() as SimulationPushRequested
        assertEquals(SimulationPushType.EVENT, push.pushType)
        assertEquals(7L, push.userSeq)
    }

    // ===== 18.14~18.17 산책 =====

    @Test
    fun `고양이 세션 산책은 409, 진행 중 산책이 있으면 409`() {
        val cat = store.session(userSeq = 8, species = SpeciesType.CAT)
        assertCode(SimulationErrorCode.SIMULATION_WALK_NOT_FOR_SPECIES) { walkService.start(8, cat.sessionSeq!!) }
        val dog = store.session()
        walkService.start(7, dog.sessionSeq!!)
        assertCode(SimulationErrorCode.SIMULATION_WALK_ALREADY_IN_PROGRESS) { walkService.start(7, dog.sessionSeq!!) }
    }

    @Test
    fun `하트비트에서 WALK 이벤트가 나고 GIVE_WATER 정답이면 해결한다(푸시 없음)`() {
        val session = store.session()
        store.scenario(ActionCode.GIVE_WATER, context = TriggerContext.WALK, resolveEffects = mapOf("hydration" to 10))
        val walk = walkService.start(7, session.sessionSeq!!)
        val beat = walkService.heartbeat(7, session.sessionSeq!!, walk.walkSeq)
        assertNotNull(beat.newEvent)
        assertTrue(store.published.isEmpty())

        val result = walkService.act(7, session.sessionSeq!!, walk.walkSeq, ActionCommand(ActionCode.GIVE_WATER, null))
        assertEquals(beat.newEvent!!.occurrenceSeq, result.resolvedEvent!!.occurrenceSeq)
        assertEquals(walk.walkSeq, result.walkSeq)
        assertCode(CommonErrorCode.COMMON_INVALID_INPUT) { walkService.act(7, session.sessionSeq!!, walk.walkSeq, ActionCommand(ActionCode.VET_VISIT, null)) }
    }

    @Test
    fun `산책 종료는 길이만큼 효과를 반영하고 이 산책의 PENDING을 방치 처리한다`() {
        val session = store.session(personality = Personality.AGGRESSIVE)
        val walk = walkService.start(7, session.sessionSeq!!)
        val pending = store.pending(session, store.scenario(ActionCode.GIVE_SNACK, context = TriggerContext.WALK, neglectEffects = mapOf("calmness" to -8)), walk.walkSeq)
        store.now = store.now.plusMinutes(35)

        val ended = walkService.end(7, session.sessionSeq!!, walk.walkSeq)

        assertEquals(35, ended.durationMinutes)
        assertEquals(mapOf("satiety" to -6, "hydration" to -8, "bond" to 4, "calmness" to 12, "energy" to 25), ended.statDelta)
        assertEquals(OccurrenceStatus.EXPIRED, pending.status)
        assertEquals(1, ended.eventCount)
        assertCode(SimulationErrorCode.SIMULATION_WALK_NOT_IN_PROGRESS) { walkService.end(7, session.sessionSeq!!, walk.walkSeq) }
        assertCode(SimulationErrorCode.SIMULATION_WALK_NOT_FOUND) { walkService.end(7, session.sessionSeq!!, 999999) }
    }

    // ===== 18.18 리포트·요약 =====

    @Test
    fun `진행 중이면 본인은 중간 리포트, 보호소는 404`() {
        val session = store.session()
        val interim = reportService.get(7, false, session.sessionSeq!!)
        assertEquals(false, interim.isFinal)
        assertNull(interim.summary)
        assertCode(SimulationErrorCode.SIMULATION_NOT_FOUND) { reportService.get(1, true, session.sessionSeq!!) }
    }

    @Test
    fun `요약은 summary가 비어 있을 때만 채우고 그때만 SESSION_END 푸시를 한 번 낸다`() {
        val session = store.session()
        sessionService.abandon(7, session.sessionSeq!!)
        val reportSeq = store.reportRows.single().reportSeq!!

        summaryService.fill(reportSeq)
        summaryService.fill(reportSeq)

        assertTrue(store.reportRows.single().summary!!.contains("그만두었어요"))
        assertEquals(1, store.published.filterIsInstance<SimulationPushRequested>().count { it.pushType == SimulationPushType.SESSION_END })
        val shelterView = reportService.get(1, true, session.sessionSeq!!)
        assertEquals(true, shelterView.isFinal)
        assertEquals(100000, shelterView.difference)
    }
}
