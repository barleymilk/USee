package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.auth.Role
import com.ssafy.animal.simulation.application.port.`in`.ActorSummary
import com.ssafy.animal.simulation.application.port.`in`.BreedView
import com.ssafy.animal.simulation.application.port.`in`.EquipmentView
import com.ssafy.animal.simulation.application.port.`in`.EventView
import com.ssafy.animal.simulation.application.port.`in`.ImageRef
import com.ssafy.animal.simulation.application.port.`in`.ReportListItem
import com.ssafy.animal.simulation.application.port.`in`.ReportView
import com.ssafy.animal.simulation.application.port.`in`.ScenarioBrief
import com.ssafy.animal.simulation.application.port.`in`.SessionDetail
import com.ssafy.animal.simulation.application.port.`in`.StatsView
import com.ssafy.animal.simulation.application.port.`in`.VetVisitView
import com.ssafy.animal.simulation.application.port.out.BreedRow
import com.ssafy.animal.simulation.application.port.out.CoreReader
import com.ssafy.animal.simulation.application.port.out.OccurrenceRepository
import com.ssafy.animal.simulation.application.port.out.ScenarioRepository
import com.ssafy.animal.simulation.application.port.out.UserRow
import com.ssafy.animal.simulation.application.port.out.WalkRepository
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.EventOccurrence
import com.ssafy.animal.simulation.domain.EventScenario
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.SimulationReport
import com.ssafy.animal.simulation.domain.SimulationSession
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 저장 키 → 공개 URL(Q443, {public-base-url}/{key}). 설정이 비어 있으면 null */
@Component
class ImageUrls(
    @Value("\${storage.public-base-url:\${STORAGE_PUBLIC_BASE_URL:}}") private val publicBaseUrl: String,
) {
    fun of(key: String?): String? = key?.takeIf { publicBaseUrl.isNotBlank() }?.let { "${publicBaseUrl.trimEnd('/')}/$it" }
}

/** 응답 DTO 조립(읽기만) */
@Component
class SimulationViewAssembler(
    private val engine: SimulationEngine,
    private val coreReader: CoreReader,
    private val occurrences: OccurrenceRepository,
    private val scenarios: ScenarioRepository,
    private val walks: WalkRepository,
    private val imageUrls: ImageUrls,
) {

    fun breed(session: SimulationSession, row: BreedRow?) =
        BreedView(session.breedSeq, row?.breedName, session.speciesType, imageUrls.of(row?.imageKey))

    fun breeds(sessions: Collection<SimulationSession>): Map<Int, BreedRow> = coreReader.findBreeds(sessions.map { it.breedSeq }.toSet())

    fun detail(session: SimulationSession): SessionDetail {
        val pending = occurrences.findPending(session.seq)
        return SessionDetail(
            sessionSeq = session.seq,
            userSeq = session.userSeq,
            animalName = session.animalName,
            breed = breed(session, coreReader.findBreed(session.breedSeq)),
            speciesType = session.speciesType,
            personality = session.personality,
            status = session.status,
            expectedCost = session.expectedCost,
            balance = session.balance,
            startedDttm = session.startedDttm,
            endsDttm = session.endsDttm,
            endedDttm = session.endedDttm,
            stats = StatsView.of(session.stats, session.statsCalculatedDttm),
            lastActionDttm = session.lastActionDttm,
            equipment = equipment(session.seq),
            pendingEvent = pending?.let { event(it, engine.scenarioOf(it)) },
            activeWalkSeq = walks.findActive(session.seq)?.walkSeq,
        )
    }

    fun equipment(sessionSeq: Long): EquipmentView {
        val owned = engine.owned(sessionSeq).filter { it.inventory.quantity > 0 }.map { it.item.category }.toSet()
        return EquipmentView(
            foodBowl = ItemCategory.FOOD_BOWL in owned,
            waterBowl = ItemCategory.WATER_BOWL in owned,
            litterBox = ItemCategory.LITTER_BOX in owned,
            toy = ItemCategory.TOY in owned,
        )
    }

    fun events(list: List<EventOccurrence>): List<EventView> {
        val byScenario = scenarios.findAllById(list.map { it.scenarioSeq }.toSet()).associateBy { it.scenarioSeq }
        return list.map { event(it, byScenario.getValue(it.scenarioSeq)) }
    }

    /** 정답 행동은 해결 전 숨기고, 병원 이벤트만 진료비를 미리 보여 준다(Q486) */
    fun event(occurrence: EventOccurrence, scenario: EventScenario) = EventView(
        occurrenceSeq = checkNotNull(occurrence.occurrenceSeq),
        scenario = ScenarioBrief(scenario.scenarioSeq, scenario.title, scenario.description, scenario.triggerContext, scenario.triggerType),
        vetVisit = VetVisitView(scenario.resolveCost).takeIf { scenario.resolveActionCode == ActionCode.VET_VISIT },
        walkSeq = occurrence.walkSeq,
        status = occurrence.status,
        occurredDttm = occurrence.occurredDttm,
        expiresDttm = occurrence.expiresDttm,
        resolvedDttm = occurrence.resolvedDttm,
        resolvedActionCode = scenario.resolveActionCode.takeIf { occurrence.status == OccurrenceStatus.RESOLVED },
        resultText = when (occurrence.status) {
            OccurrenceStatus.RESOLVED -> scenario.resultText
            OccurrenceStatus.EXPIRED -> scenario.neglectText
            OccurrenceStatus.PENDING -> null
        },
    )

    fun report(session: SimulationSession, report: SimulationReport, isFinal: Boolean) = ReportView(
        sessionSeq = session.seq,
        status = session.status,
        isFinal = isFinal,
        animalName = session.animalName,
        breed = breed(session, coreReader.findBreed(session.breedSeq)),
        speciesType = session.speciesType,
        personality = session.personality,
        startedDttm = session.startedDttm,
        endedDttm = session.endedDttm,
        expectedCost = report.expectedCost,
        totalSpent = report.totalSpent,
        difference = report.expectedCost - report.totalSpent,
        spentByCategory = report.spentByCategoryJsonb,
        dailySpent = report.dailySpentJsonb,
        finalStats = report.finalStatsJsonb,
        actionCount = report.actionCount,
        eventCount = report.eventCount,
        eventResolvedCount = report.eventResolvedCount,
        walkCount = report.walkCount,
        summary = if (isFinal) report.summary else null,
    )

    fun reportItem(session: SimulationSession, report: SimulationReport, breed: BreedRow?, user: ActorSummary?) = ReportListItem(
        sessionSeq = session.seq,
        animalName = session.animalName,
        breed = breed(session, breed),
        status = session.status,
        startedDttm = session.startedDttm,
        endedDttm = session.endedDttm,
        expectedCost = report.expectedCost,
        totalSpent = report.totalSpent,
        difference = report.expectedCost - report.totalSpent,
        user = user,
    )

    /** ActorSummary(1.4). 탈퇴 회원은 "(탈퇴한 사용자)"로 표기하고 사진을 숨긴다 */
    fun actor(userSeq: Int, row: UserRow?): ActorSummary {
        if (row == null || row.isDeleted) return ActorSummary(userSeq, DELETED_USER, row?.role?.let(::roleOf), null)
        val image = row.profileImageSeq?.let { ImageRef(it, imageUrls.of(row.profileImageKey), 0) }
        return ActorSummary(userSeq, row.nickname ?: DELETED_USER, roleOf(row.role), image)
    }

    private fun roleOf(role: String) = Role.entries.firstOrNull { it.name == role }

    companion object {
        const val DELETED_USER = "(탈퇴한 사용자)"
    }
}
