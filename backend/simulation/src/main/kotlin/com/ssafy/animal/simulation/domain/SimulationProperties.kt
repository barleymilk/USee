package com.ssafy.animal.simulation.domain

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 튜닝값(Q429, DB 명세 §3 "코드 설정 가안"). 기본값이 가안 수치이고 simulation.* 프로퍼티(환경 변수 SIMULATION_*)로 덮어쓴다.
 * 시나리오별 발생 확률·정답 행동·효과·비용은 시드 컬럼이라 여기 없다(Q481·Q486).
 */
@ConfigurationProperties("simulation")
data class SimulationProperties(
    val tick: Duration = Duration.ofHours(1),
    val sessionLength: Duration = Duration.ofDays(7),
    val initialStats: Map<StatKey, Int> = mapOf(
        StatKey.satiety to 70, StatKey.hydration to 70, StatKey.cleanliness to 80, StatKey.bond to 30,
        StatKey.calmness to 50, StatKey.health to 90, StatKey.energy to 60,
    ),
    /** tick당 감소량(양수). health는 아래 조건부 감소만 */
    val decayPerTick: Map<StatKey, Int> = mapOf(
        StatKey.satiety to 4, StatKey.hydration to 5, StatKey.cleanliness to 2, StatKey.bond to 1,
        StatKey.calmness to 2, StatKey.health to 0, StatKey.energy to 3,
    ),
    /** satiety·hydration·cleanliness 중 하나라도 이 값 미만이면 health -[lowStatHealthPenalty] */
    val lowStatThreshold: Int = 20,
    val lowStatHealthPenalty: Int = 3,
    /** calmness가 이 값 이하이면 health 추가 -[lowCalmnessHealthPenalty] */
    val lowCalmnessThreshold: Int = 20,
    val lowCalmnessHealthPenalty: Int = 2,
    val personalities: Map<Personality, PersonalityRule> = mapOf(
        Personality.ACTIVE to PersonalityRule(
            decay = mapOf(StatKey.energy to 1.3, StatKey.satiety to 1.3),
            walkGain = 1.3,
        ),
        Personality.TIMID to PersonalityRule(decay = mapOf(StatKey.calmness to 1.5), gain = mapOf(StatKey.bond to 0.7)),
        Personality.AGGRESSIVE to PersonalityRule(decay = mapOf(StatKey.bond to 1.3)),
        Personality.FRIENDLY to PersonalityRule(gain = mapOf(StatKey.bond to 1.5), neglect = mapOf(StatKey.calmness to 1.3)),
    ),
    /** 물은 물품이 아니라 무료(18.6 WATER, 18.16 GIVE_WATER) */
    val waterEffect: Map<StatKey, Int> = mapOf(StatKey.hydration to 30),
    val giveWaterEffect: Map<StatKey, Int> = mapOf(StatKey.hydration to 20, StatKey.bond to 1),
    val event: EventRule = EventRule(),
    val walk: WalkRule = WalkRule(),
    /** 리포트 summary 고아 복구: 종료 후 이 시간이 지나도 NULL이면 다시 생성(Q487) */
    val summaryRecoveryDelay: Duration = Duration.ofMinutes(10),
) {
    fun rule(personality: Personality) = personalities[personality] ?: PersonalityRule()

    /**
     * 성격 배율(M4). AGGRESSIVE의 "산책 중 짖기·싸우기 가중치 2"는 배율이 아니라
     * personality_scope=AGGRESSIVE 시나리오(가중치 2) 시드로 표현한다.
     */
    data class PersonalityRule(
        /** tick 감소 배율 */
        val decay: Map<StatKey, Double> = emptyMap(),
        /** 물품·이벤트 해결·산책의 회복(양수) 배율 */
        val gain: Map<StatKey, Double> = emptyMap(),
        /** 방치 효과의 감소(음수) 배율 */
        val neglect: Map<StatKey, Double> = emptyMap(),
        /** 산책 회복(양수) 추가 배율 */
        val walkGain: Double = 1.0,
    )

    data class EventRule(
        val homeTtl: Duration = Duration.ofHours(6),
        val walkTtl: Duration = Duration.ofMinutes(5),
        /** cooldown_hours가 NULL인 시나리오의 재발생 억제 */
        val defaultCooldown: Duration = Duration.ofHours(24),
        /** HOME RANDOM 하루 평균 발생 수(가안 1~2회). 스케줄러 1회당 확률 = 이 값 / 하루 실행 횟수 */
        val homeRandomPerDay: Double = 1.5,
        /** 하트비트 1회당 WALK 이벤트 발생 확률. 60초 하트비트, 30분 산책이면 평균 1.5회 */
        val walkChancePerHeartbeat: Double = 0.05,
        val walkMaxPerWalk: Int = 3,
    )

    data class WalkRule(
        val heartbeatInterval: Duration = Duration.ofSeconds(60),
        val heartbeatTimeout: Duration = Duration.ofMinutes(10),
        /** 산책 1시간당 효과. 종료 시 길이 비율로 한 번에 반영(18.17) */
        val effectPerHour: Map<StatKey, Int> = mapOf(
            StatKey.energy to 42, StatKey.calmness to 20, StatKey.bond to 7, StatKey.satiety to -10, StatKey.hydration to -14,
        ),
        /** 이보다 긴 산책은 효과를 더 주지 않는다 */
        val effectMaxDuration: Duration = Duration.ofMinutes(60),
    )

    companion object {
        /** 스케줄러 주기(10분, S8). @Scheduled와 HOME RANDOM 확률 계산이 같이 쓴다 */
        val SCHEDULER_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}
