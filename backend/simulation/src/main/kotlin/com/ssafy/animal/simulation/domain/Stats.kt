package com.ssafy.animal.simulation.domain

/** 스탯 7종(Q485). 이름이 곧 JSON 키·DB 컬럼 이름이라 소문자로 둔다. 모두 높을수록 좋다 */
@Suppress("EnumEntryName")
enum class StatKey { satiety, hydration, cleanliness, bond, calmness, health, energy }

/** 0~100 정수 스탯 묶음. 연산 결과는 항상 0~100으로 보정한다 */
data class Stats(
    val satiety: Int,
    val hydration: Int,
    val cleanliness: Int,
    val bond: Int,
    val calmness: Int,
    val health: Int,
    val energy: Int,
) {
    operator fun get(key: StatKey) = when (key) {
        StatKey.satiety -> satiety
        StatKey.hydration -> hydration
        StatKey.cleanliness -> cleanliness
        StatKey.bond -> bond
        StatKey.calmness -> calmness
        StatKey.health -> health
        StatKey.energy -> energy
    }

    operator fun plus(delta: Map<StatKey, Int>): Stats {
        fun v(key: StatKey) = (get(key) + (delta[key] ?: 0)).coerceIn(MIN, MAX)
        return Stats(
            v(StatKey.satiety), v(StatKey.hydration), v(StatKey.cleanliness), v(StatKey.bond),
            v(StatKey.calmness), v(StatKey.health), v(StatKey.energy),
        )
    }

    /** this - before 중 0이 아닌 것만(API의 statDelta) */
    fun diff(before: Stats): Map<String, Int> =
        StatKey.entries.associate { it.name to get(it) - before[it] }.filterValues { it != 0 }

    fun toMap(): Map<String, Int> = StatKey.entries.associate { it.name to get(it) }

    companion object {
        const val MIN = 0
        const val MAX = 100

        fun of(values: Map<StatKey, Int>) = Stats(
            values.getValue(StatKey.satiety), values.getValue(StatKey.hydration), values.getValue(StatKey.cleanliness),
            values.getValue(StatKey.bond), values.getValue(StatKey.calmness), values.getValue(StatKey.health),
            values.getValue(StatKey.energy),
        ).plus(emptyMap())

        /** 시드 JSON(effects_jsonb 등)의 문자열 키를 스탯 키로. 모르는 키는 무시한다 */
        fun keys(effects: Map<String, Int>?): Map<StatKey, Int> =
            effects.orEmpty().mapNotNull { (k, v) -> StatKey.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()
    }
}
