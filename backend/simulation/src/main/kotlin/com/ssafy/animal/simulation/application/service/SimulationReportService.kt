package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.event.EventPublisher
import com.ssafy.animal.common.event.SimulationPushRequested
import com.ssafy.animal.common.event.SimulationPushType
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.simulation.application.port.`in`.ReportListItem
import com.ssafy.animal.simulation.application.port.`in`.ReportUseCase
import com.ssafy.animal.simulation.application.port.`in`.ReportView
import com.ssafy.animal.simulation.application.port.out.CoreReader
import com.ssafy.animal.simulation.application.port.out.ReportRepository
import com.ssafy.animal.simulation.application.port.out.ReportSummarizer
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.application.port.out.SummaryInput
import com.ssafy.animal.simulation.domain.ReportCreated
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SimulationReport
import com.ssafy.animal.simulation.domain.SimulationSession
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener

/** 18.18~18.20. 보호소는 종료 리포트를 누구 것이든 본다(Q483) */
@Service
@Transactional(noRollbackFor = [BusinessException::class])
class SimulationReportService(
    private val engine: SimulationEngine,
    private val views: SimulationViewAssembler,
    private val sessions: SessionRepository,
    private val reports: ReportRepository,
    private val coreReader: CoreReader,
) : ReportUseCase {

    /** 본인: 진행 중이면 계산만 한 중간 리포트(isFinal=false, 저장 없음). 보호소: 종료 리포트만, 진행 중은 404 */
    override fun get(viewerUserSeq: Int, isShelter: Boolean, sessionSeq: Long): ReportView {
        val notFound = BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)
        if (isShelter) {
            val session = sessions.findById(sessionSeq) ?: throw notFound
            val report = reports.findBySession(sessionSeq) ?: throw notFound
            return views.report(session, report, isFinal = true)
        }
        val session = sessions.lock(sessionSeq)?.takeIf { it.userSeq == viewerUserSeq } ?: throw notFound
        if (engine.refresh(session)) return views.report(session, engine.newReport(session), isFinal = false)
        val report = reports.findBySession(sessionSeq) ?: throw notFound
        return views.report(session, report, isFinal = true)
    }

    @Transactional(readOnly = true)
    override fun findMine(userSeq: Int, pageable: Pageable) = list(userSeq, pageable, withUser = false)

    @Transactional(readOnly = true)
    override fun findForShelter(userSeq: Int?, pageable: Pageable) = list(userSeq, pageable, withUser = true)

    private fun list(userSeq: Int?, pageable: Pageable, withUser: Boolean): Page<ReportListItem> {
        val page = reports.findPage(userSeq, pageable)
        val sessionsOnPage = page.content.map { it.second }
        val breeds = views.breeds(sessionsOnPage)
        val users = if (withUser) coreReader.findUsers(sessionsOnPage.map { it.userSeq }.toSet()) else emptyMap()
        return page.map { (report, session) ->
            val user = if (withUser) views.actor(session.userSeq, users[session.userSeq]) else null
            views.reportItem(session, report, breeds[session.breedSeq], user)
        }
    }
}

/**
 * 리포트 summary 비동기 생성(Q444·Q487): 커밋 후 → 요약(실패 시 1회 재시도, 그래도 실패면 규칙 문구)
 * → WHERE summary IS NULL 갱신 → 갱신됐을 때만 SESSION_END 푸시. 스케줄러의 고아 복구도 [fill]을 부른다.
 */
@Component
class ReportSummaryService(
    private val reports: ReportRepository,
    private val sessions: SessionRepository,
    private val summarizer: ReportSummarizer,
    private val eventPublisher: EventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    fun onReportCreated(event: ReportCreated) = fill(event.reportSeq)

    /** 트랜잭션 없이 돈다: 요약(LLM) 호출을 DB 트랜잭션 밖에 둔다 */
    fun fill(reportSeq: Long) {
        val report = reports.findById(reportSeq) ?: return
        if (report.summary != null) return
        val session = sessions.findById(report.sessionSeq) ?: return
        val input = input(session, report)
        val summary = runCatching { summarizer.summarize(input) }
            .recoverCatching { summarizer.summarize(input) }
            .onFailure { log.warn("[simulation] 리포트 요약 실패, 규칙 문구로 대체 reportSeq={}", reportSeq, it) }
            .getOrElse { RuleSummaries.of(input) }
        if (!reports.fillSummary(reportSeq, summary)) return
        eventPublisher.publish(
            SimulationPushRequested(session.userSeq.toLong(), SimulationPushType.SESSION_END, PUSH_TITLE, "${session.animalName}와의 모의 입양이 끝났어요. 리포트를 확인해 보세요."),
        )
    }

    private fun input(session: SimulationSession, report: SimulationReport) = SummaryInput(
        animalName = session.animalName,
        status = session.status,
        expectedCost = report.expectedCost,
        totalSpent = report.totalSpent,
        spentByCategory = report.spentByCategoryJsonb,
        finalStats = report.finalStatsJsonb,
        actionCount = report.actionCount,
        eventCount = report.eventCount,
        eventResolvedCount = report.eventResolvedCount,
        walkCount = report.walkCount,
    )

    companion object {
        const val PUSH_TITLE = "모의 입양 종료"
    }
}
