package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.simulation.application.port.out.ReportRepository
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.application.port.out.WalkRepository
import com.ssafy.animal.simulation.domain.SimulationProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

/**
 * 10분 주기 작업 하나(S8, 21.5의 7). 세션마다 트랜잭션을 따로 열어 한 세션의 실패가 나머지를 막지 않게 한다.
 * ponytail: 인스턴스 1개 전제. 2개 이상이면 ShedLock을 붙인다(명세 18장 머리말).
 */
@Component
class SimulationScheduler(
    private val props: SimulationProperties,
    private val engine: SimulationEngine,
    private val sessions: SessionRepository,
    private val walks: WalkRepository,
    private val reports: ReportRepository,
    private val ticks: SimulationTicks,
    private val summaries: ReportSummaryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 10, initialDelay = 10, timeUnit = TimeUnit.MINUTES) // SimulationProperties.SCHEDULER_INTERVAL
    fun run() {
        val now = engine.now()
        sessions.findInProgressSeqs().forEach { each("session", it) { ticks.tickSession(it) } }
        walks.findStale(now.minus(props.walk.heartbeatTimeout)).forEach { walk -> each("walk", walk.walkSeq) { ticks.endStaleWalk(checkNotNull(walk.walkSeq)) } }
        reports.findSummaryMissing(now.minus(props.summaryRecoveryDelay)).forEach { each("report", it) { summaries.fill(it) } }
    }

    private fun each(kind: String, seq: Long?, block: () -> Unit) {
        runCatching(block).onFailure { log.error("[simulation] 스케줄러 {} {} 처리 실패", kind, seq, it) }
    }
}

/** 스케줄러 단위 작업(세션 하나 = 트랜잭션 하나) */
@Service
@Transactional(noRollbackFor = [BusinessException::class])
class SimulationTicks(
    private val engine: SimulationEngine,
    private val sessions: SessionRepository,
    private val walks: WalkRepository,
) {
    /** 지연 계산 → 종료 → 만료(방치) → CONDITION 판정(refresh) → RANDOM 추첨 */
    fun tickSession(sessionSeq: Long) {
        val session = sessions.lock(sessionSeq) ?: return
        val now = engine.now()
        if (engine.refresh(session, now)) engine.triggerRandomHome(session, now)
    }

    /** 하트비트가 끊긴 산책 자동 종료. 종료 시각은 마지막 하트비트 */
    fun endStaleWalk(walkSeq: Long) {
        val walk = walks.findById(walkSeq)?.takeIf { it.inProgress } ?: return
        val session = sessions.lock(walk.sessionSeq) ?: return
        if (!engine.refresh(session) || !walk.inProgress) return
        engine.endWalk(session, walk, walk.lastSeen)
    }
}
