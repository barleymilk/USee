package com.ssafy.animal.simulation.adapter.out.persistence

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.persistence.SortPolicy
import com.ssafy.animal.simulation.application.port.out.ActionLogRepository
import com.ssafy.animal.simulation.application.port.out.InventoryRepository
import com.ssafy.animal.simulation.application.port.out.OccurrenceRepository
import com.ssafy.animal.simulation.application.port.out.ReportRepository
import com.ssafy.animal.simulation.application.port.out.ScenarioRepository
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.application.port.out.ShopItemRepository
import com.ssafy.animal.simulation.application.port.out.SpendingRow
import com.ssafy.animal.simulation.application.port.out.TransactionRepository
import com.ssafy.animal.simulation.application.port.out.WalkRepository
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionLog
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.EventOccurrence
import com.ssafy.animal.simulation.domain.EventScenario
import com.ssafy.animal.simulation.domain.InventoryItem
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.QActionLog.Companion.actionLog
import com.ssafy.animal.simulation.domain.QEventOccurrence.Companion.eventOccurrence
import com.ssafy.animal.simulation.domain.QShopItem.Companion.shopItem
import com.ssafy.animal.simulation.domain.QSimulationReport.Companion.simulationReport
import com.ssafy.animal.simulation.domain.QSimulationSession.Companion.simulationSession
import com.ssafy.animal.simulation.domain.QSimulationTransaction.Companion.simulationTransaction
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.ShopItem
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SimulationReport
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SimulationTransaction
import com.ssafy.animal.simulation.domain.SimulationWalk
import com.ssafy.animal.simulation.domain.SpeciesScope
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import com.ssafy.animal.simulation.domain.WalkStatus
import jakarta.persistence.LockModeType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.findByIdOrNull
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

// ===== Spring Data =====

interface SessionJpaRepository : JpaRepository<SimulationSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SimulationSession s where s.sessionSeq = :sessionSeq")
    fun lockById(@Param("sessionSeq") sessionSeq: Long): SimulationSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SimulationSession s where s.userSeq = :userSeq and s.status = :status")
    fun lockByUser(@Param("userSeq") userSeq: Int, @Param("status") status: SessionStatus): SimulationSession?

    @Query("select s.sessionSeq from SimulationSession s where s.status = :status order by s.sessionSeq")
    fun findSeqsByStatus(@Param("status") status: SessionStatus): List<Long>
}

interface ShopItemJpaRepository : JpaRepository<ShopItem, Long>

interface InventoryJpaRepository : JpaRepository<InventoryItem, Long> {
    fun findBySessionSeqOrderByInventoryItemSeq(sessionSeq: Long): List<InventoryItem>
    fun findBySessionSeqAndItemSeq(sessionSeq: Long, itemSeq: Long): InventoryItem?
}

interface TransactionJpaRepository : JpaRepository<SimulationTransaction, Long>

interface ActionLogJpaRepository : JpaRepository<ActionLog, Long> {
    @Query(
        """
        select count(l) from ActionLog l
        where l.sessionSeq = :sessionSeq and (l.actionType = :care or l.actionCode in :walkActions)
        """,
    )
    fun countUserActions(
        @Param("sessionSeq") sessionSeq: Long,
        @Param("care") care: ActionType,
        @Param("walkActions") walkActions: Collection<ActionCode>,
    ): Long
}

interface WalkJpaRepository : JpaRepository<SimulationWalk, Long> {
    fun findBySessionSeqAndStatus(sessionSeq: Long, status: WalkStatus): SimulationWalk?
    fun countBySessionSeq(sessionSeq: Long): Long

    @Query("select w from SimulationWalk w where w.status = :status and coalesce(w.lastHeartbeatDttm, w.startedDttm) < :before")
    fun findStale(@Param("status") status: WalkStatus, @Param("before") before: OffsetDateTime): List<SimulationWalk>
}

interface ScenarioJpaRepository : JpaRepository<EventScenario, Long> {
    fun findByTriggerContextAndIsActiveTrueOrderByScenarioSeq(triggerContext: TriggerContext): List<EventScenario>
}

interface OccurrenceJpaRepository : JpaRepository<EventOccurrence, Long> {
    fun findBySessionSeqAndStatus(sessionSeq: Long, status: OccurrenceStatus): EventOccurrence?
    fun countByWalkSeq(walkSeq: Long): Long
    fun countBySessionSeq(sessionSeq: Long): Long
    fun countBySessionSeqAndStatus(sessionSeq: Long, status: OccurrenceStatus): Long

    @Query("select max(o.occurredDttm) from EventOccurrence o where o.sessionSeq = :sessionSeq and o.scenarioSeq = :scenarioSeq")
    fun lastOccurredAt(@Param("sessionSeq") sessionSeq: Long, @Param("scenarioSeq") scenarioSeq: Long): OffsetDateTime?
}

interface ReportJpaRepository : JpaRepository<SimulationReport, Long> {
    fun findBySessionSeq(sessionSeq: Long): SimulationReport?

    @Query("select r.sessionSeq from SimulationReport r where r.sessionSeq in :sessionSeqs")
    fun findSessionSeqs(@Param("sessionSeqs") sessionSeqs: Collection<Long>): List<Long>

    @Query(
        """
        select r.reportSeq from SimulationReport r join SimulationSession s on s.sessionSeq = r.sessionSeq
        where r.summary is null and s.endedDttm < :before order by r.reportSeq
        """,
    )
    fun findSummaryMissing(@Param("before") before: OffsetDateTime): List<Long>

    // summary 비동기 생성은 트랜잭션 밖에서 부르므로 여기서 연다. WHERE summary IS NULL이 중복 푸시를 막는다(Q487)
    @Transactional
    @Modifying
    @Query("update SimulationReport r set r.summary = :summary where r.reportSeq = :reportSeq and r.summary is null")
    fun fillSummary(@Param("reportSeq") reportSeq: Long, @Param("summary") summary: String): Int
}

// ===== 포트 구현 =====

@Repository
class SessionPersistenceAdapter(
    private val jpa: SessionJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : SessionRepository {

    override fun saveAndFlush(session: SimulationSession): SimulationSession = try {
        // 같은 트랜잭션에서 이전 세션을 방금 종료했으면 그 UPDATE를 먼저 내보내야 부분 UNIQUE에 걸리지 않는다
        jpa.flush()
        jpa.saveAndFlush(session)
    } catch (e: DataIntegrityViolationException) {
        if (ACTIVE_UNIQUE in e.mostSpecificCause.message.orEmpty()) throw BusinessException(SimulationErrorCode.SIMULATION_ACTIVE_ALREADY_EXISTS)
        throw e
    }

    override fun findById(sessionSeq: Long) = jpa.findByIdOrNull(sessionSeq)

    override fun lock(sessionSeq: Long) = jpa.lockById(sessionSeq)

    override fun lockActiveByUser(userSeq: Int) = jpa.lockByUser(userSeq, SessionStatus.IN_PROGRESS)

    override fun findPageByUser(userSeq: Int, status: SessionStatus?, pageable: Pageable): Page<SimulationSession> {
        val where = arrayOf(simulationSession.userSeq.eq(userSeq), status?.let(simulationSession.status::eq))
        return page(
            queryFactory.selectFrom(simulationSession).where(*where).orderBy(*SORT.toOrderSpecifiers(pageable.sort)),
            queryFactory.select(simulationSession.count()).from(simulationSession).where(*where),
            pageable,
        )
    }

    override fun findInProgressSeqs() = jpa.findSeqsByStatus(SessionStatus.IN_PROGRESS)

    companion object {
        private const val ACTIVE_UNIQUE = "uq_sim_session_user_active"

        /** 18.3 정렬 화이트리스트 startedDttm(기본)·endedDttm */
        private val SORT = SortPolicy(
            fields = mapOf("startedDttm" to simulationSession.startedDttm, "endedDttm" to simulationSession.endedDttm),
            default = listOf(simulationSession.startedDttm.desc()),
            tieBreaker = simulationSession.sessionSeq.desc(),
        )
    }
}

@Repository
class ShopItemPersistenceAdapter(
    private val jpa: ShopItemJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : ShopItemRepository {

    override fun findActive(species: SpeciesType?): List<ShopItem> = queryFactory.selectFrom(shopItem)
        .where(
            shopItem.isActive.isTrue,
            species?.let { shopItem.speciesScope.`in`(SpeciesScope.ALL, SpeciesScope.valueOf(it.name)) },
        )
        .orderBy(shopItem.displayOrder.asc(), shopItem.itemSeq.asc())
        .fetch()

    override fun findById(itemSeq: Long) = jpa.findByIdOrNull(itemSeq)

    override fun findAllById(itemSeqs: Collection<Long>): List<ShopItem> = if (itemSeqs.isEmpty()) emptyList() else jpa.findAllById(itemSeqs)
}

@Repository
class InventoryPersistenceAdapter(private val jpa: InventoryJpaRepository) : InventoryRepository {
    override fun findBySession(sessionSeq: Long) = jpa.findBySessionSeqOrderByInventoryItemSeq(sessionSeq)
    override fun find(sessionSeq: Long, itemSeq: Long) = jpa.findBySessionSeqAndItemSeq(sessionSeq, itemSeq)
    override fun save(item: InventoryItem): InventoryItem = jpa.save(item)
}

@Repository
class TransactionPersistenceAdapter(
    private val jpa: TransactionJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : TransactionRepository {

    override fun save(transaction: SimulationTransaction): SimulationTransaction = jpa.save(transaction)

    override fun findPage(sessionSeq: Long, type: TransactionType?, pageable: Pageable): Page<SimulationTransaction> {
        val where = arrayOf(simulationTransaction.sessionSeq.eq(sessionSeq), type?.let(simulationTransaction.transactionType::eq))
        return page(
            queryFactory.selectFrom(simulationTransaction).where(*where).orderBy(*SORT.toOrderSpecifiers(pageable.sort)),
            queryFactory.select(simulationTransaction.count()).from(simulationTransaction).where(*where),
            pageable,
        )
    }

    override fun findSpending(sessionSeq: Long): List<SpendingRow> = queryFactory
        .select(shopItem.category, simulationTransaction.amount, simulationTransaction.regDttm)
        .from(simulationTransaction)
        .leftJoin(shopItem).on(shopItem.itemSeq.eq(simulationTransaction.itemSeq))
        .where(simulationTransaction.sessionSeq.eq(sessionSeq))
        .fetch()
        .map {
            SpendingRow(
                category = it.get(shopItem.category), // EVENT(진료비)는 물품이 없어 null
                amount = it.get(simulationTransaction.amount)!!,
                regDttm = it.get(simulationTransaction.regDttm)!!,
            )
        }

    companion object {
        private val SORT = SortPolicy.regDttmOnly(simulationTransaction.regDttm, simulationTransaction.transactionSeq)
    }
}

@Repository
class ActionLogPersistenceAdapter(
    private val jpa: ActionLogJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : ActionLogRepository {

    override fun save(log: ActionLog): ActionLog = jpa.save(log)

    override fun findPage(sessionSeq: Long, type: ActionType?, pageable: Pageable): Page<ActionLog> {
        val where = arrayOf(actionLog.sessionSeq.eq(sessionSeq), type?.let(actionLog.actionType::eq))
        return page(
            queryFactory.selectFrom(actionLog).where(*where).orderBy(*SORT.toOrderSpecifiers(pageable.sort)),
            queryFactory.select(actionLog.count()).from(actionLog).where(*where),
            pageable,
        )
    }

    override fun countUserActions(sessionSeq: Long) =
        jpa.countUserActions(sessionSeq, ActionType.CARE, ActionCode.WALK_USER_ACTIONS).toInt()

    companion object {
        private val SORT = SortPolicy.regDttmOnly(actionLog.regDttm, actionLog.actionLogSeq)
    }
}

@Repository
class WalkPersistenceAdapter(private val jpa: WalkJpaRepository) : WalkRepository {
    override fun save(walk: SimulationWalk): SimulationWalk = jpa.save(walk)
    override fun findById(walkSeq: Long) = jpa.findByIdOrNull(walkSeq)
    override fun findActive(sessionSeq: Long) = jpa.findBySessionSeqAndStatus(sessionSeq, WalkStatus.IN_PROGRESS)
    override fun findStale(before: OffsetDateTime) = jpa.findStale(WalkStatus.IN_PROGRESS, before)
    override fun countBySession(sessionSeq: Long) = jpa.countBySessionSeq(sessionSeq).toInt()
}

@Repository
class ScenarioPersistenceAdapter(private val jpa: ScenarioJpaRepository) : ScenarioRepository {
    override fun findActive(context: TriggerContext) = jpa.findByTriggerContextAndIsActiveTrueOrderByScenarioSeq(context)
    override fun findAllById(scenarioSeqs: Collection<Long>): List<EventScenario> =
        if (scenarioSeqs.isEmpty()) emptyList() else jpa.findAllById(scenarioSeqs)
}

@Repository
class OccurrencePersistenceAdapter(
    private val jpa: OccurrenceJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : OccurrenceRepository {

    override fun save(occurrence: EventOccurrence): EventOccurrence = jpa.save(occurrence)

    override fun findPending(sessionSeq: Long) = jpa.findBySessionSeqAndStatus(sessionSeq, OccurrenceStatus.PENDING)

    override fun findPage(sessionSeq: Long, status: OccurrenceStatus?, pageable: Pageable): Page<EventOccurrence> {
        val where = arrayOf(eventOccurrence.sessionSeq.eq(sessionSeq), status?.let(eventOccurrence.status::eq))
        return page(
            queryFactory.selectFrom(eventOccurrence).where(*where).orderBy(*SORT.toOrderSpecifiers(pageable.sort)),
            queryFactory.select(eventOccurrence.count()).from(eventOccurrence).where(*where),
            pageable,
        )
    }

    override fun lastOccurredAt(sessionSeq: Long, scenarioSeq: Long) = jpa.lastOccurredAt(sessionSeq, scenarioSeq)

    override fun countByWalk(walkSeq: Long) = jpa.countByWalkSeq(walkSeq).toInt()

    override fun countBySession(sessionSeq: Long, status: OccurrenceStatus?) =
        (status?.let { jpa.countBySessionSeqAndStatus(sessionSeq, it) } ?: jpa.countBySessionSeq(sessionSeq)).toInt()

    companion object {
        /** 18.12 정렬 화이트리스트 occurredDttm */
        private val SORT = SortPolicy(
            fields = mapOf("occurredDttm" to eventOccurrence.occurredDttm),
            default = listOf(eventOccurrence.occurredDttm.desc()),
            tieBreaker = eventOccurrence.occurrenceSeq.desc(),
        )
    }
}

@Repository
class ReportPersistenceAdapter(
    private val jpa: ReportJpaRepository,
    private val queryFactory: JPAQueryFactory,
) : ReportRepository {

    override fun save(report: SimulationReport): SimulationReport = jpa.save(report)

    override fun findBySession(sessionSeq: Long) = jpa.findBySessionSeq(sessionSeq)

    override fun findById(reportSeq: Long) = jpa.findByIdOrNull(reportSeq)

    override fun existingSessionSeqs(sessionSeqs: Collection<Long>): Set<Long> =
        if (sessionSeqs.isEmpty()) emptySet() else jpa.findSessionSeqs(sessionSeqs).toSet()

    override fun findPage(userSeq: Int?, pageable: Pageable): Page<Pair<SimulationReport, SimulationSession>> {
        val where = userSeq?.let(simulationSession.userSeq::eq)
        val content = queryFactory.select(simulationReport, simulationSession)
            .from(simulationReport)
            .join(simulationSession).on(simulationSession.sessionSeq.eq(simulationReport.sessionSeq))
            .where(where)
            .orderBy(*SORT.toOrderSpecifiers(pageable.sort))
            .offset(pageable.offset)
            .limit(pageable.pageSize.toLong())
            .fetch()
            .map { it.get(simulationReport)!! to it.get(simulationSession)!! }
        val total = queryFactory.select(simulationReport.count())
            .from(simulationReport)
            .join(simulationSession).on(simulationSession.sessionSeq.eq(simulationReport.sessionSeq))
            .where(where)
            .fetchOne() ?: 0L
        return PageImpl(content, pageable, total)
    }

    override fun findSummaryMissing(endedBefore: OffsetDateTime) = jpa.findSummaryMissing(endedBefore)

    override fun fillSummary(reportSeq: Long, summary: String) = jpa.fillSummary(reportSeq, summary) > 0

    companion object {
        /** 18.19·18.20 정렬 화이트리스트 endedDttm */
        private val SORT = SortPolicy(
            fields = mapOf("endedDttm" to simulationSession.endedDttm),
            default = listOf(simulationSession.endedDttm.desc()),
            tieBreaker = simulationReport.reportSeq.desc(),
        )
    }
}

private fun <T : Any> page(content: JPAQuery<T>, count: JPAQuery<Long>, pageable: Pageable): Page<T> = PageImpl(
    content.offset(pageable.offset).limit(pageable.pageSize.toLong()).fetch(),
    pageable,
    count.fetchOne() ?: 0L,
)
