package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.simulation.application.port.`in`.AbandonResult
import com.ssafy.animal.simulation.application.port.`in`.SessionDetail
import com.ssafy.animal.simulation.application.port.`in`.SessionSummary
import com.ssafy.animal.simulation.application.port.`in`.SessionUseCase
import com.ssafy.animal.simulation.application.port.`in`.StartSessionCommand
import com.ssafy.animal.simulation.application.port.out.CoreReader
import com.ssafy.animal.simulation.application.port.out.ReportRepository
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.Personality
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SimulationProperties
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SpeciesType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 18.1~18.5. 지연 계산은 조회에서도 저장하므로(M2) 조회도 쓰기 트랜잭션이다.
 * BusinessException에 롤백하지 않는 이유: 지연 계산으로 세션이 끝난 뒤 409를 내도 그 종료는 남아야 한다(검사는 변경 전에 끝냄).
 */
@Service
@Transactional(noRollbackFor = [BusinessException::class])
class SimulationSessionService(
    private val props: SimulationProperties,
    private val engine: SimulationEngine,
    private val views: SimulationViewAssembler,
    private val sessions: SessionRepository,
    private val reports: ReportRepository,
    private val coreReader: CoreReader,
) : SessionUseCase {

    /** 21.5의 1: 진행 중 세션 잠금 확인 → 품종·종 검증 → 스냅샷 INSERT → SESSION_START */
    override fun start(command: StartSessionCommand): SessionDetail {
        sessions.lockActiveByUser(command.userSeq)?.let {
            if (engine.refresh(it)) throw BusinessException(SimulationErrorCode.SIMULATION_ACTIVE_ALREADY_EXISTS)
        }
        val breed = coreReader.findBreed(command.breedSeq) ?: throw BusinessException(SimulationErrorCode.SIMULATION_BREED_NOT_FOUND)
        val species = SpeciesType.entries.firstOrNull { it.name == breed.speciesCode }
            ?: throw BusinessException(SimulationErrorCode.SIMULATION_SPECIES_NOT_SUPPORTED)
        val now = engine.now()
        val session = sessions.saveAndFlush(
            SimulationSession(
                userSeq = command.userSeq,
                breedSeq = breed.breedSeq,
                animalName = command.animalName,
                speciesType = species,
                personality = Personality.valueOf(breed.personality),
                expectedCost = command.expectedCost,
                startedDttm = now,
                endsDttm = now.plus(props.sessionLength),
                stats = engine.initialStats(),
            ),
        )
        engine.log(session, ActionCode.SESSION_START)
        return views.detail(session)
    }

    override fun findActive(userSeq: Int): SessionDetail? {
        val session = sessions.lockActiveByUser(userSeq) ?: return null
        engine.refresh(session)
        return views.detail(session)
    }

    @Transactional(readOnly = true)
    override fun findMine(userSeq: Int, status: SessionStatus?, pageable: Pageable): Page<SessionSummary> {
        val page = sessions.findPageByUser(userSeq, status, pageable)
        val breeds = views.breeds(page.content)
        val reported = reports.existingSessionSeqs(page.content.map { it.seq })
        return page.map {
            SessionSummary(
                sessionSeq = it.seq,
                animalName = it.animalName,
                breed = views.breed(it, breeds[it.breedSeq]),
                speciesType = it.speciesType,
                personality = it.personality,
                status = it.status,
                expectedCost = it.expectedCost,
                balance = it.balance,
                startedDttm = it.startedDttm,
                endsDttm = it.endsDttm,
                endedDttm = it.endedDttm,
                hasReport = it.seq in reported,
            )
        }
    }

    override fun get(userSeq: Int, sessionSeq: Long): SessionDetail {
        val session = lockOwned(userSeq, sessionSeq)
        engine.refresh(session)
        return views.detail(session)
    }

    /** 18.5: 지연 계산으로 이미 끝났으면 409, 아니면 ABANDONED + 리포트(summary는 아직 null) */
    override fun abandon(userSeq: Int, sessionSeq: Long): AbandonResult {
        val session = lockOwned(userSeq, sessionSeq)
        if (!engine.refresh(session)) throw BusinessException(SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS)
        val report = engine.end(session, SessionStatus.ABANDONED, engine.now())
        return AbandonResult(session.seq, session.status, session.endedDttm, views.report(session, report, isFinal = true))
    }

    override fun abandonByUser(userSeq: Int) {
        val session = sessions.lockActiveByUser(userSeq) ?: return
        if (engine.refresh(session)) engine.end(session, SessionStatus.ABANDONED, engine.now())
    }

    private fun lockOwned(userSeq: Int, sessionSeq: Long): SimulationSession =
        sessions.lock(sessionSeq)?.takeIf { it.userSeq == userSeq } ?: throw BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)
}

/** 본인 세션 잠금 + 진행 중 확인(변경 계열 공통). 지연 계산으로 방금 끝났어도 409 */
internal fun lockInProgress(sessions: SessionRepository, engine: SimulationEngine, userSeq: Int, sessionSeq: Long): SimulationSession {
    val session = sessions.lock(sessionSeq)?.takeIf { it.userSeq == userSeq }
        ?: throw BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)
    if (!engine.refresh(session)) throw BusinessException(SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS)
    return session
}
