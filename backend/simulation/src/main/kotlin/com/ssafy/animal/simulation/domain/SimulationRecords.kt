package com.ssafy.animal.simulation.domain

import com.ssafy.animal.common.persistence.BaseCreatedEntity
import com.ssafy.animal.common.persistence.BaseTimeEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

// 세션에 딸린 기록들(DB 명세 37~40·43·44). 테이블끼리는 FK가 있지만 엔티티는 숫자 컬럼으로만 잇는다

/** 37. simulation_inventory_items. 기구는 수량 1, 소모품은 0이 되어도 행 유지 */
@Entity
@Table(name = "simulation_inventory_items")
class InventoryItem(
    val sessionSeq: Long,
    val itemSeq: Long,
    quantity: Int,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var inventoryItemSeq: Long? = null
        protected set

    var quantity: Int = quantity
        protected set

    fun add(count: Int) {
        quantity += count
    }

    fun consumeOne() {
        check(quantity > 0)
        quantity -= 1
    }
}

/** 38. simulation_transactions. PURCHASE는 물품, EVENT(진료비)는 발생 이벤트 필수(M15) */
@Entity
@Table(name = "simulation_transactions")
class SimulationTransaction(
    val sessionSeq: Long,
    @field:Enumerated(EnumType.STRING)
    val transactionType: TransactionType,
    val itemSeq: Long?,
    val occurrenceSeq: Long?,
    val quantity: Int,
    val amount: Int,
    val balanceAfter: Int,
    val description: String?,
) : BaseCreatedEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var transactionSeq: Long? = null
        protected set
}

/** 39. simulation_action_logs */
@Entity
@Table(name = "simulation_action_logs")
class ActionLog(
    val sessionSeq: Long,
    @field:Enumerated(EnumType.STRING)
    val actionType: ActionType,
    @field:Enumerated(EnumType.STRING)
    val actionCode: ActionCode,
    val itemSeq: Long?,
    val walkSeq: Long?,
    val occurrenceSeq: Long?,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val statDeltaJsonb: Map<String, Int>?,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val statsAfterJsonb: Map<String, Int>,
) : BaseCreatedEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var actionLogSeq: Long? = null
        protected set
}

/** 40. simulation_walks. DOG 세션만(Q488) */
@Entity
@Table(name = "simulation_walks")
class SimulationWalk(
    val sessionSeq: Long,
    val startedDttm: OffsetDateTime,
) : BaseCreatedEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var walkSeq: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: WalkStatus = WalkStatus.IN_PROGRESS
        protected set

    var lastHeartbeatDttm: OffsetDateTime? = null
        protected set

    var endedDttm: OffsetDateTime? = null
        protected set

    val inProgress get() = status == WalkStatus.IN_PROGRESS

    /** 마지막 생존 신호(하트비트가 없으면 시작 시각) */
    val lastSeen get() = lastHeartbeatDttm ?: startedDttm

    fun heartbeat(at: OffsetDateTime) {
        lastHeartbeatDttm = at
    }

    fun end(at: OffsetDateTime) {
        check(inProgress)
        status = WalkStatus.ENDED
        endedDttm = at
    }
}

/** 43. simulation_event_occurrences. 세션당 PENDING 1건(M11) */
@Entity
@Table(name = "simulation_event_occurrences")
class EventOccurrence(
    val sessionSeq: Long,
    val scenarioSeq: Long,
    val walkSeq: Long?,
    val occurredDttm: OffsetDateTime,
    val expiresDttm: OffsetDateTime,
) : BaseCreatedEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var occurrenceSeq: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: OccurrenceStatus = OccurrenceStatus.PENDING
        protected set

    var resolvedDttm: OffsetDateTime? = null
        protected set

    var resolvedActionLogSeq: Long? = null
        protected set

    fun expired(now: OffsetDateTime) = !expiresDttm.isAfter(now)

    /** 로그 INSERT 뒤에 부른다(상호 FK 순서) */
    fun resolve(actionLogSeq: Long, at: OffsetDateTime) {
        check(status == OccurrenceStatus.PENDING)
        status = OccurrenceStatus.RESOLVED
        resolvedDttm = at
        resolvedActionLogSeq = actionLogSeq
    }

    fun expire() {
        check(status == OccurrenceStatus.PENDING)
        status = OccurrenceStatus.EXPIRED
    }
}

/** 44. simulation_reports. 세션 1:1, 종료 시 1회 생성, summary는 커밋 후 비동기로 채운다(Q487) */
@Entity
@Table(name = "simulation_reports")
class SimulationReport(
    val sessionSeq: Long,
    val expectedCost: Int,
    val totalSpent: Int,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val spentByCategoryJsonb: Map<String, Int>,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val dailySpentJsonb: Map<String, Int>,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val finalStatsJsonb: Map<String, Int>,
    val actionCount: Int,
    val eventCount: Int,
    val eventResolvedCount: Int,
    val walkCount: Int,
) : BaseCreatedEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var reportSeq: Long? = null
        protected set

    /** 갱신은 ReportRepository.fillSummary(WHERE summary IS NULL)로만 한다 */
    var summary: String? = null
        protected set
}

/** 종료 리포트 INSERT(simulation 내부 이벤트). 커밋 후 비동기로 summary를 채운다(Q487) */
data class ReportCreated(val reportSeq: Long)
