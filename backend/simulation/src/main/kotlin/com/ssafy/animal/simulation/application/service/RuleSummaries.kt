package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.simulation.application.port.out.SummaryInput
import com.ssafy.animal.simulation.domain.SessionStatus

/** 집계값 기반 규칙 문구(Q444). LLM 어댑터가 없거나 실패했을 때 쓴다. FAILED도 죽음 표현을 쓰지 않는다(M6) */
object RuleSummaries {

    private val CATEGORY_LABELS = mapOf(
        "FOOD_BOWL" to "밥그릇", "WATER_BOWL" to "물그릇", "LITTER_BOX" to "화장실", "TOY" to "장난감", "FOOD" to "사료",
        "SNACK" to "간식", "LITTER" to "모래", "SHAMPOO" to "샴푸", "MEDICINE" to "약", "EVENT" to "병원비",
    )
    private val STAT_LABELS = mapOf(
        "satiety" to "포만감", "hydration" to "수분", "cleanliness" to "청결", "bond" to "친밀도",
        "calmness" to "안정감", "health" to "건강", "energy" to "활력",
    )
    private const val LOW_STAT = 40

    fun of(input: SummaryInput): String = buildList {
        add(
            when (input.status) {
                SessionStatus.COMPLETED -> "${input.animalName}와 일주일을 끝까지 함께했어요."
                SessionStatus.ABANDONED -> "${input.animalName}와의 모의 입양을 중간에 그만두었어요."
                SessionStatus.FAILED -> "돌봄이 부족해 ${input.animalName}와의 모의 입양이 중단되었어요."
                SessionStatus.IN_PROGRESS -> "${input.animalName}와 모의 입양을 진행하고 있어요."
            },
        )
        val difference = input.expectedCost - input.totalSpent
        add(if (difference >= 0) "예상 비용보다 %,d원 덜 썼어요.".format(difference) else "예상보다 %,d원을 더 썼어요.".format(-difference))
        input.spentByCategory.filterValues { it > 0 }.maxByOrNull { it.value }?.let {
            add("${CATEGORY_LABELS[it.key] ?: it.key} 지출이 가장 컸습니다.")
        }
        if (input.eventCount > 0) add("돌발 상황 ${input.eventCount}번 중 ${input.eventResolvedCount}번을 잘 해결했어요.")
        input.finalStats.filterValues { it < LOW_STAT }.minByOrNull { it.value }?.let {
            add("마지막에는 ${STAT_LABELS[it.key] ?: it.key} 관리가 더 필요했어요.")
        }
    }.joinToString(" ")
}
