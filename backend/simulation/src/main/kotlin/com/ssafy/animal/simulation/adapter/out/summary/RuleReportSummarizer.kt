package com.ssafy.animal.simulation.adapter.out.summary

import com.ssafy.animal.simulation.application.port.out.ReportSummarizer
import com.ssafy.animal.simulation.application.port.out.SummaryInput
import com.ssafy.animal.simulation.application.service.RuleSummaries
import org.springframework.stereotype.Component

/**
 * 기본 요약 어댑터: 집계값 규칙 문구. LLM 키가 생기면 ReportSummarizer를 구현한 LLM 어댑터(타임아웃 20초, Q444 가안)로 바꾼다.
 */
@Component
class RuleReportSummarizer : ReportSummarizer {
    override fun summarize(input: SummaryInput) = RuleSummaries.of(input)
}
