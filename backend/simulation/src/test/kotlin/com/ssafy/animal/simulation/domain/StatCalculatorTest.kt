package com.ssafy.animal.simulation.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatCalculatorTest {

    private val props = SimulationProperties()
    private val calculator = StatCalculator(props)
    private val start = OffsetDateTime.parse("2026-11-01T14:00:00+09:00")
    private val initial = Stats(70, 70, 80, 30, 50, 90, 60)

    /** 지금 시각은 Clock으로만 정한다 */
    private fun clockAt(elapsed: Duration): Clock = Clock.fixed(Instant.from(start.plus(elapsed)), ZoneId.of("Asia/Seoul"))

    private fun advance(stats: Stats, from: OffsetDateTime, clock: Clock, personality: Personality = Personality.FRIENDLY) =
        calculator.advance(stats, start, from, OffsetDateTime.now(clock), personality)

    @Test
    fun `tick 1회마다 가안 감소량을 적용하고 health는 조건이 없으면 그대로다`() {
        val result = advance(initial, start, clockAt(Duration.ofHours(1)))
        assertEquals(Stats(66, 65, 78, 29, 48, 90, 57), result.stats)
        assertEquals(start.plusHours(1), result.calculatedAt)
        assertFalse(result.depleted)
    }

    @Test
    fun `1 tick 미만은 계산하지 않고 남은 시간을 다음 계산으로 넘긴다`() {
        val result = advance(initial, start, clockAt(Duration.ofMinutes(119)))
        assertEquals(start.plusHours(1), result.calculatedAt)
        val next = advance(result.stats, result.calculatedAt, clockAt(Duration.ofMinutes(120)))
        assertEquals(advance(initial, start, clockAt(Duration.ofHours(2))).stats, next.stats)
    }

    @Test
    fun `성격 배율 감소는 나눠 계산해도 한 번에 계산한 것과 같다`() {
        // ACTIVE: satiety 4×1.3=5.2/tick → 5 tick이면 26
        val once = advance(initial, start, clockAt(Duration.ofHours(5)), Personality.ACTIVE)
        var split = Stats(70, 70, 80, 30, 50, 90, 60)
        var at = start
        for (h in 1L..5L) {
            val r = advance(split, at, clockAt(Duration.ofHours(h)), Personality.ACTIVE)
            split = r.stats
            at = r.calculatedAt
        }
        assertEquals(70 - 26, once.stats.satiety)
        assertEquals(once.stats, split)
    }

    @Test
    fun `포만감·수분·청결 중 하나가 20 미만이면 health -3, 안정감 20 이하면 추가 -2`() {
        val low = Stats(10, 70, 80, 30, 20, 90, 60)
        val result = advance(low, start, clockAt(Duration.ofHours(1)))
        assertEquals(90 - 3 - 2, result.stats.health)
    }

    @Test
    fun `health가 0이 되면 그 tick에서 멈추고 depleted`() {
        val weak = Stats(0, 0, 0, 0, 0, 4, 0)
        val result = advance(weak, start, clockAt(Duration.ofDays(3)))
        assertEquals(0, result.stats.health)
        assertEquals(start.plusHours(1), result.calculatedAt)
        assertTrue(result.depleted)
    }

    @Test
    fun `스탯은 0~100으로 보정한다`() {
        assertEquals(100, (initial + mapOf(StatKey.satiety to 50)).satiety)
        assertEquals(0, (initial + mapOf(StatKey.bond to -50)).bond)
    }

    @Test
    fun `회복 배율은 양수에만, 방치 배율은 음수에만 적용한다`() {
        assertEquals(mapOf(StatKey.bond to 8, StatKey.calmness to -5), calculator.gain(mapOf(StatKey.bond to 5, StatKey.calmness to -5), Personality.FRIENDLY))
        assertEquals(mapOf(StatKey.calmness to -13, StatKey.bond to 2), calculator.neglect(mapOf(StatKey.calmness to -10, StatKey.bond to 2), Personality.FRIENDLY))
    }

    @Test
    fun `산책 35분 효과는 명세 예시와 같고 60분을 넘으면 늘지 않는다`() {
        val walk = calculator.walk(Duration.ofMinutes(35), Personality.AGGRESSIVE)
        assertEquals(
            mapOf(StatKey.energy to 25, StatKey.calmness to 12, StatKey.bond to 4, StatKey.satiety to -6, StatKey.hydration to -8),
            walk,
        )
        assertEquals(calculator.walk(Duration.ofMinutes(60), Personality.AGGRESSIVE), calculator.walk(Duration.ofMinutes(90), Personality.AGGRESSIVE))
        // ACTIVE 산책 회복 1.3
        assertEquals(55, calculator.walk(Duration.ofMinutes(60), Personality.ACTIVE)[StatKey.energy])
    }
}
