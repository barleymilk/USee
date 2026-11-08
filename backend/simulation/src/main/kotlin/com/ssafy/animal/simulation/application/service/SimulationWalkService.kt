package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.exception.CommonErrorCode
import com.ssafy.animal.common.response.ErrorDetail
import com.ssafy.animal.simulation.application.port.`in`.ActionCommand
import com.ssafy.animal.simulation.application.port.`in`.ActionResult
import com.ssafy.animal.simulation.application.port.`in`.HeartbeatResult
import com.ssafy.animal.simulation.application.port.`in`.StatsView
import com.ssafy.animal.simulation.application.port.`in`.WalkEnded
import com.ssafy.animal.simulation.application.port.`in`.WalkStarted
import com.ssafy.animal.simulation.application.port.`in`.WalkUseCase
import com.ssafy.animal.simulation.application.port.out.OccurrenceRepository
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.application.port.out.WalkRepository
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SimulationProperties
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SimulationWalk
import com.ssafy.animal.simulation.domain.SpeciesType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/** 18.14~18.17 산책(DOG 세션 전용, Q488). 롤백 정책은 SimulationSessionService와 같다 */
@Service
@Transactional(noRollbackFor = [BusinessException::class])
class SimulationWalkService(
    private val props: SimulationProperties,
    private val engine: SimulationEngine,
    private val views: SimulationViewAssembler,
    private val sessions: SessionRepository,
    private val walks: WalkRepository,
    private val occurrences: OccurrenceRepository,
) : WalkUseCase {

    override fun start(userSeq: Int, sessionSeq: Long): WalkStarted {
        val session = lockInProgress(sessions, engine, userSeq, sessionSeq)
        if (session.speciesType != SpeciesType.DOG) throw BusinessException(SimulationErrorCode.SIMULATION_WALK_NOT_FOR_SPECIES)
        if (walks.findActive(session.seq) != null) throw BusinessException(SimulationErrorCode.SIMULATION_WALK_ALREADY_IN_PROGRESS)
        val walk = walks.save(SimulationWalk(session.seq, engine.now()))
        engine.log(session, ActionCode.WALK_START, walkSeq = walk.walkSeq)
        return WalkStarted(
            walkSeq = walk.seq,
            status = walk.status,
            startedDttm = walk.startedDttm,
            heartbeatIntervalSeconds = props.walk.heartbeatInterval.toSeconds(),
            stats = StatsView.of(session.stats, session.statsCalculatedDttm),
        )
    }

    /** 18.15: 하트비트 기록 → (지연 계산은 lockInProgress에서) → WALK 이벤트 추첨. PENDING이 있으면 재전달 */
    override fun heartbeat(userSeq: Int, sessionSeq: Long, walkSeq: Long): HeartbeatResult {
        val session = lockInProgress(sessions, engine, userSeq, sessionSeq)
        val walk = activeWalk(session, walkSeq)
        val now = engine.now()
        walk.heartbeat(now)
        val created = engine.triggerWalk(session, walk, now)
        val pending = if (created == null) occurrences.findPending(session.seq) else null
        return HeartbeatResult(
            walkSeq = walk.seq,
            elapsedMinutes = Duration.between(walk.startedDttm, now).toMinutes(),
            stats = StatsView.of(session.stats, session.statsCalculatedDttm),
            newEvent = created?.let { views.event(it, engine.scenarioOf(it)) },
            pendingEvent = pending?.let { views.event(it, engine.scenarioOf(it)) },
        )
    }

    /** 18.16: GIVE_WATER(무료)·GIVE_SNACK(SNACK 1개). VET_VISIT 등 돌봄 행동은 400 */
    override fun act(userSeq: Int, sessionSeq: Long, walkSeq: Long, command: ActionCommand): ActionResult {
        if (command.actionCode !in ActionCode.WALK_USER_ACTIONS) {
            throw BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, errors = listOf(ErrorDetail("actionCode", "산책 중에는 GIVE_WATER·GIVE_SNACK만 할 수 있습니다.")))
        }
        val session = lockInProgress(sessions, engine, userSeq, sessionSeq)
        return engine.act(session, command.actionCode, command.itemSeq, activeWalk(session, walkSeq))
    }

    override fun end(userSeq: Int, sessionSeq: Long, walkSeq: Long): WalkEnded {
        val session = lockInProgress(sessions, engine, userSeq, sessionSeq)
        val walk = activeWalk(session, walkSeq)
        val outcome = engine.endWalk(session, walk, engine.now())
        return WalkEnded(
            walkSeq = walk.seq,
            status = walk.status,
            startedDttm = walk.startedDttm,
            endedDttm = walk.endedDttm,
            durationMinutes = Duration.between(walk.startedDttm, checkNotNull(walk.endedDttm)).toMinutes(),
            statDelta = outcome.statDelta,
            stats = StatsView.of(session.stats, session.statsCalculatedDttm),
            eventCount = outcome.eventCount,
        )
    }

    private fun activeWalk(session: SimulationSession, walkSeq: Long): SimulationWalk {
        val walk = walks.findById(walkSeq)?.takeIf { it.sessionSeq == session.seq }
            ?: throw BusinessException(SimulationErrorCode.SIMULATION_WALK_NOT_FOUND)
        if (!walk.inProgress) throw BusinessException(SimulationErrorCode.SIMULATION_WALK_NOT_IN_PROGRESS)
        return walk
    }
}
