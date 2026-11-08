package com.ssafy.animal.simulation.application.port.out

import com.ssafy.animal.simulation.domain.ActionLog
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.EventOccurrence
import com.ssafy.animal.simulation.domain.EventScenario
import com.ssafy.animal.simulation.domain.InventoryItem
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.ShopItem
import com.ssafy.animal.simulation.domain.SimulationReport
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SimulationTransaction
import com.ssafy.animal.simulation.domain.SimulationWalk
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.OffsetDateTime

interface SessionRepository {
    /** 부분 UNIQUE(USER당 진행 중 1건) 위반은 SIMULATION_ACTIVE_ALREADY_EXISTS로 바꿔 던진다 */
    fun saveAndFlush(session: SimulationSession): SimulationSession
    fun findById(sessionSeq: Long): SimulationSession?

    /** SELECT ... FOR UPDATE */
    fun lock(sessionSeq: Long): SimulationSession?
    fun lockActiveByUser(userSeq: Int): SimulationSession?

    /** 정렬 화이트리스트 startedDttm(기본)·endedDttm(18.3) */
    fun findPageByUser(userSeq: Int, status: SessionStatus?, pageable: Pageable): Page<SimulationSession>
    fun findInProgressSeqs(): List<Long>
}

interface ShopItemRepository {
    /** is_active이고 종 범위가 맞는 물품(species가 null이면 전체), display_order 순 */
    fun findActive(species: SpeciesType?): List<ShopItem>
    fun findById(itemSeq: Long): ShopItem?
    fun findAllById(itemSeqs: Collection<Long>): List<ShopItem>
}

interface InventoryRepository {
    fun findBySession(sessionSeq: Long): List<InventoryItem>
    fun find(sessionSeq: Long, itemSeq: Long): InventoryItem?
    fun save(item: InventoryItem): InventoryItem
}

interface TransactionRepository {
    fun save(transaction: SimulationTransaction): SimulationTransaction
    fun findPage(sessionSeq: Long, type: TransactionType?, pageable: Pageable): Page<SimulationTransaction>

    /** 리포트 집계용 지출 행(PURCHASE는 물품 분류, EVENT는 null) */
    fun findSpending(sessionSeq: Long): List<SpendingRow>
}

data class SpendingRow(val category: ItemCategory?, val amount: Int, val regDttm: OffsetDateTime)

interface ActionLogRepository {
    fun save(log: ActionLog): ActionLog
    fun findPage(sessionSeq: Long, type: ActionType?, pageable: Pageable): Page<ActionLog>

    /** 리포트 actionCount: 사용자가 한 돌봄·산책 행동 수(CARE + GIVE_WATER·GIVE_SNACK) */
    fun countUserActions(sessionSeq: Long): Int
}

interface WalkRepository {
    fun save(walk: SimulationWalk): SimulationWalk
    fun findById(walkSeq: Long): SimulationWalk?
    fun findActive(sessionSeq: Long): SimulationWalk?

    /** 마지막 하트비트(없으면 시작)가 [before]보다 이른 진행 중 산책 */
    fun findStale(before: OffsetDateTime): List<SimulationWalk>
    fun countBySession(sessionSeq: Long): Int
}

interface ScenarioRepository {
    fun findActive(context: TriggerContext): List<EventScenario>
    fun findAllById(scenarioSeqs: Collection<Long>): List<EventScenario>
}

interface OccurrenceRepository {
    fun save(occurrence: EventOccurrence): EventOccurrence
    fun findPending(sessionSeq: Long): EventOccurrence?
    fun findPage(sessionSeq: Long, status: OccurrenceStatus?, pageable: Pageable): Page<EventOccurrence>
    fun lastOccurredAt(sessionSeq: Long, scenarioSeq: Long): OffsetDateTime?
    fun countByWalk(walkSeq: Long): Int
    fun countBySession(sessionSeq: Long, status: OccurrenceStatus? = null): Int
}

interface ReportRepository {
    fun save(report: SimulationReport): SimulationReport
    fun findBySession(sessionSeq: Long): SimulationReport?
    fun findById(reportSeq: Long): SimulationReport?
    fun existingSessionSeqs(sessionSeqs: Collection<Long>): Set<Long>

    /** 종료 리포트 목록(18.19·18.20). userSeq가 null이면 전체. 정렬 화이트리스트 endedDttm */
    fun findPage(userSeq: Int?, pageable: Pageable): Page<Pair<SimulationReport, SimulationSession>>

    /** summary가 NULL이고 세션이 [endedBefore] 전에 끝난 리포트(고아 복구, Q487) */
    fun findSummaryMissing(endedBefore: OffsetDateTime): List<Long>

    /** UPDATE ... SET summary WHERE report_seq AND summary IS NULL. 갱신했으면 true */
    fun fillSummary(reportSeq: Long, summary: String): Boolean
}

/** core 스키마 읽기 전용 JOIN(Q420 D·Q482). 엔티티를 import하지 않고 native로 읽는다 */
interface CoreReader {
    fun findBreed(breedSeq: Int): BreedRow?
    fun findBreeds(breedSeqs: Collection<Int>): Map<Int, BreedRow>
    fun findUsers(userSeqs: Collection<Int>): Map<Int, UserRow>

    /** 쓰기 요청 계정 재확인(Q433). 행이 없으면 null */
    fun findAccount(userSeq: Int): AccountRow?
}

data class BreedRow(
    val breedSeq: Int,
    val breedName: String,
    /** DOG/CAT/ETC */
    val speciesCode: String,
    val personality: String,
    val imageKey: String?,
)

data class UserRow(
    val userSeq: Int,
    val role: String,
    val nickname: String?,
    val isDeleted: Boolean,
    val profileImageSeq: Long?,
    val profileImageKey: String?,
)

data class AccountRow(val isDeleted: Boolean, val accountStatus: String)

/** 리포트 summary 문구 생성(LLM 어댑터 자리, Q444). 기본 구현은 규칙 문구 */
interface ReportSummarizer {
    fun summarize(input: SummaryInput): String
}

data class SummaryInput(
    val animalName: String,
    val status: SessionStatus,
    val expectedCost: Int,
    val totalSpent: Int,
    val spentByCategory: Map<String, Int>,
    val finalStats: Map<String, Int>,
    val actionCount: Int,
    val eventCount: Int,
    val eventResolvedCount: Int,
    val walkCount: Int,
)
