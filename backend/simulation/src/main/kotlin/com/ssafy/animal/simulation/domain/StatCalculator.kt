package com.ssafy.animal.simulation.domain

import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 스탯 지연 계산과 효과 반영(M2·M3·M4, 21.5의 2). 시간·저장과 무관한 순수 계산이다.
 */
class StatCalculator(private val props: SimulationProperties) {

    data class Advanced(
        val stats: Stats,
        /** 새 stats_calculated_dttm. 남은 1 tick 미만은 다음 계산으로 넘긴다 */
        val calculatedAt: OffsetDateTime,
        /** health가 0이 된 시점에서 멈췄으면 true(FAILED 판정) */
        val depleted: Boolean,
    )

    /**
     * [calculatedAt]부터 [until]까지 지난 tick만큼 감소를 적용한다. 배율 감소(예 4×1.3=5.2)는 세션 시작 기준 tick 번호로
     * 누적 내림 차이를 써서, 몇 번에 나눠 계산해도 같은 결과가 나온다.
     */
    fun advance(stats: Stats, startedAt: OffsetDateTime, calculatedAt: OffsetDateTime, until: OffsetDateTime, personality: Personality): Advanced {
        val rule = props.rule(personality)
        var at = calculatedAt
        var current = stats
        while (!at.plus(props.tick).isAfter(until)) {
            val tickNo = Duration.between(startedAt, at).dividedBy(props.tick)
            current = current + tickDelta(current, tickNo, rule)
            at = at.plus(props.tick)
            if (current.health == 0) return Advanced(current, at, true)
        }
        return Advanced(current, at, false)
    }

    private fun tickDelta(stats: Stats, tickNo: Long, rule: SimulationProperties.PersonalityRule): Map<StatKey, Int> {
        val delta = StatKey.entries.associateWith { key ->
            val perTick = (props.decayPerTick[key] ?: 0) * (rule.decay[key] ?: 1.0)
            -(floor(perTick * (tickNo + 1)) - floor(perTick * tickNo)).toInt()
        }.toMutableMap()
        val low = listOf(stats.satiety, stats.hydration, stats.cleanliness).any { it < props.lowStatThreshold }
        if (low) delta.merge(StatKey.health, -props.lowStatHealthPenalty, Int::plus)
        if (stats.calmness <= props.lowCalmnessThreshold) delta.merge(StatKey.health, -props.lowCalmnessHealthPenalty, Int::plus)
        return delta
    }

    /** 물품·이벤트 해결 효과: 회복(양수)에 성격 회복 배율 */
    fun gain(effects: Map<StatKey, Int>, personality: Personality): Map<StatKey, Int> {
        val rule = props.rule(personality)
        return effects.mapValues { (key, v) -> if (v > 0) scale(v, rule.gain[key]) else v }
    }

    /** 방치 효과: 감소(음수)에 성격 방치 배율 */
    fun neglect(effects: Map<StatKey, Int>, personality: Personality): Map<StatKey, Int> {
        val rule = props.rule(personality)
        return effects.mapValues { (key, v) -> if (v < 0) scale(v, rule.neglect[key]) else v }
    }

    /** 산책 효과(18.17): 1시간당 효과 × 길이 비율(상한 있음), 회복에 회복 배율 × 산책 회복 배율(종 배율 없음, Q488) */
    fun walk(duration: Duration, personality: Personality): Map<StatKey, Int> {
        val rule = props.rule(personality)
        val ratio = minOf(duration, props.walk.effectMaxDuration).toSeconds() / 3600.0
        return props.walk.effectPerHour.mapValues { (key, perHour) ->
            val v = perHour * ratio
            if (v > 0) (v * (rule.gain[key] ?: 1.0) * rule.walkGain).roundToInt() else v.roundToInt()
        }
    }

    private fun scale(value: Int, multiplier: Double?) = (value * (multiplier ?: 1.0)).roundToInt()
}
