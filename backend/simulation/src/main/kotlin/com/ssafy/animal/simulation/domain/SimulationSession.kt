package com.ssafy.animal.simulation.domain

import com.ssafy.animal.common.persistence.BaseTimeEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * DB 명세 35. simulation_sessions. 스탯 컬럼은 stats_calculated_dttm 시점의 값이고 조회·행동·스케줄러가 지연 계산해 갱신한다(M2).
 * user_seq·breed_seq는 core를 가리키는 FK 없는 논리 참조(INTEGER).
 */
@Entity
@Table(name = "simulation_sessions")
class SimulationSession(
    val userSeq: Int,
    val breedSeq: Int,
    val animalName: String,
    @field:Enumerated(EnumType.STRING)
    val speciesType: SpeciesType,
    @field:Enumerated(EnumType.STRING)
    val personality: Personality,
    val expectedCost: Int,
    val startedDttm: OffsetDateTime,
    val endsDttm: OffsetDateTime,
    stats: Stats,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var sessionSeq: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: SessionStatus = SessionStatus.IN_PROGRESS
        protected set

    var balance: Int = expectedCost
        protected set

    var endedDttm: OffsetDateTime? = null
        protected set

    var statsCalculatedDttm: OffsetDateTime = startedDttm
        protected set

    var lastActionDttm: OffsetDateTime? = null
        protected set

    // SMALLINT 컬럼(validate). 밖에서는 stats로만 다룬다
    private var satiety: Short = stats.satiety.toShort()
    private var hydration: Short = stats.hydration.toShort()
    private var cleanliness: Short = stats.cleanliness.toShort()
    private var bond: Short = stats.bond.toShort()
    private var calmness: Short = stats.calmness.toShort()
    private var health: Short = stats.health.toShort()
    private var energy: Short = stats.energy.toShort()

    var stats: Stats
        get() = Stats(satiety.toInt(), hydration.toInt(), cleanliness.toInt(), bond.toInt(), calmness.toInt(), health.toInt(), energy.toInt())
        set(value) {
            satiety = value.satiety.toShort()
            hydration = value.hydration.toShort()
            cleanliness = value.cleanliness.toShort()
            bond = value.bond.toShort()
            calmness = value.calmness.toShort()
            health = value.health.toShort()
            energy = value.energy.toShort()
        }

    val inProgress get() = status == SessionStatus.IN_PROGRESS

    fun recalculated(stats: Stats, calculatedAt: OffsetDateTime) {
        this.stats = stats
        statsCalculatedDttm = calculatedAt
    }

    /** 잔액 차감(음수 허용, M5) 후 잔액 */
    fun spend(amount: Int): Int {
        balance -= amount
        return balance
    }

    fun acted(at: OffsetDateTime) {
        lastActionDttm = at
    }

    fun end(status: SessionStatus, at: OffsetDateTime) {
        check(inProgress && status != SessionStatus.IN_PROGRESS)
        this.status = status
        endedDttm = at
    }
}
