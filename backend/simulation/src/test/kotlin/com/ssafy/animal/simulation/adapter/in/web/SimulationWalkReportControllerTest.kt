package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.config.CommonWebAutoConfiguration
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.simulation.application.port.`in`.ActionCommand
import com.ssafy.animal.simulation.application.port.`in`.HeartbeatResult
import com.ssafy.animal.simulation.application.port.`in`.ReportUseCase
import com.ssafy.animal.simulation.application.port.`in`.WalkEnded
import com.ssafy.animal.simulation.application.port.`in`.WalkStarted
import com.ssafy.animal.simulation.application.port.`in`.WalkUseCase
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.WalkStatus
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.mockito.Mockito.never
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(SimulationWalkController::class, SimulationReportController::class)
@ImportAutoConfiguration(CommonWebAutoConfiguration::class)
class SimulationWalkReportControllerTest : WebTestSupport() {

    @MockitoBean
    lateinit var walkUseCase: WalkUseCase

    @MockitoBean
    lateinit var reportUseCase: ReportUseCase

    private val walks = "/api/simulation/v1/simulations/3001/walks"

    // ===== 18.14 =====

    @Test
    fun `18_14 산책 시작 201`() {
        given(walkUseCase.start(7, 3001)).willReturn(WalkStarted(8001, WalkStatus.IN_PROGRESS, NOW, 60, STATS))
        mockMvc.post(walks) { asUser() }.ok(201).andExpect {
            jsonPath("$.data.walkSeq") { value(8001) }
            jsonPath("$.data.heartbeatIntervalSeconds") { value(60) }
        }
    }

    @Test
    fun `18_14 고양이 409, 이미 산책 중 409`() {
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_WALK_NOT_FOR_SPECIES)).given(walkUseCase).start(7, 3001)
        mockMvc.post(walks) { asUser() }.fails(409, "SIMULATION_WALK_NOT_FOR_SPECIES")
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_WALK_ALREADY_IN_PROGRESS)).given(walkUseCase).start(7, 3001)
        mockMvc.post(walks) { asUser() }.fails(409, "SIMULATION_WALK_ALREADY_IN_PROGRESS")
    }

    // ===== 18.15 =====

    @Test
    fun `18_15 하트비트 200, 끝난 산책 409, 없는 산책 404`() {
        given(walkUseCase.heartbeat(7, 3001, 8001)).willReturn(HeartbeatResult(8001, 12, STATS, null, null))
        mockMvc.post("$walks/8001/heartbeat") { asUser() }.ok().andExpect { jsonPath("$.data.elapsedMinutes") { value(12) } }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_WALK_NOT_IN_PROGRESS)).given(walkUseCase).heartbeat(7, 3001, 8002)
        mockMvc.post("$walks/8002/heartbeat") { asUser() }.fails(409, "SIMULATION_WALK_NOT_IN_PROGRESS")
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_WALK_NOT_FOUND)).given(walkUseCase).heartbeat(7, 3001, 9)
        mockMvc.post("$walks/9/heartbeat") { asUser() }.fails(404, "SIMULATION_WALK_NOT_FOUND")
    }

    // ===== 18.16 =====

    @Test
    fun `18_16 산책 중 행동 200, 간식 없음 409`() {
        val water = ActionCommand(ActionCode.GIVE_WATER, null)
        given(walkUseCase.act(7, 3001, 8001, water)).willReturn(actionResult(ActionType.WALK, ActionCode.GIVE_WATER))
        mockMvc.post("$walks/8001/actions") { asUser(); json("""{"actionCode":"GIVE_WATER"}""") }.ok().andExpect {
            jsonPath("$.data.actionType") { value("WALK") }
        }
        val snack = ActionCommand(ActionCode.GIVE_SNACK, 21)
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_ITEM_NOT_IN_INVENTORY)).given(walkUseCase).act(7, 3001, 8001, snack)
        mockMvc.post("$walks/8001/actions") { asUser(); json("""{"actionCode":"GIVE_SNACK","itemSeq":21}""") }
            .fails(409, "SIMULATION_ITEM_NOT_IN_INVENTORY")
    }

    // ===== 18.17 =====

    @Test
    fun `18_17 산책 종료 200, 진행 중 아님 409`() {
        val ended = WalkEnded(8001, WalkStatus.ENDED, NOW, NOW.plusMinutes(35), 35, mapOf("energy" to 25), STATS, 2)
        given(walkUseCase.end(7, 3001, 8001)).willReturn(ended)
        mockMvc.post("$walks/8001/end") { asUser() }.ok().andExpect {
            jsonPath("$.data.durationMinutes") { value(35) }
            jsonPath("$.data.statDelta.energy") { value(25) }
        }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS)).given(walkUseCase).end(7, 3001, 8002)
        mockMvc.post("$walks/8002/end") { asUser() }.fails(409, "SIMULATION_NOT_IN_PROGRESS")
    }

    // ===== 18.18 =====

    @Test
    fun `18_18 본인 리포트 200, 보호소는 isShelter로 조회, 없으면 404`() {
        given(reportUseCase.get(7, false, 3001)).willReturn(report(isFinal = false))
        mockMvc.get("/api/simulation/v1/simulations/3001/report") { asUser() }.ok().andExpect {
            jsonPath("$.data.isFinal") { value(false) }
            jsonPath("$.data.difference") { value(-32000) }
        }
        given(reportUseCase.get(1, true, 3001)).willReturn(report())
        mockMvc.get("/api/simulation/v1/simulations/3001/report") { asShelter() }.ok().andExpect { jsonPath("$.data.isFinal") { value(true) } }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)).given(reportUseCase).get(1, true, 3002)
        mockMvc.get("/api/simulation/v1/simulations/3002/report") { asShelter() }.fails(404, "SIMULATION_NOT_FOUND")
    }

    // ===== 18.19·18.20 =====

    @Test
    fun `18_19 내 리포트 목록 200, 보호소는 403`() {
        given(reportUseCase.findMine(7, Pageable.ofSize(20))).willReturn(PageImpl(listOf(reportItem()), Pageable.ofSize(20), 1))
        mockMvc.get("/api/simulation/v1/simulations/reports") { asUser() }.ok().andExpect {
            jsonPath("$.data.content[0].totalSpent") { value(182000) }
        }
        mockMvc.get("/api/simulation/v1/simulations/reports") { asShelter() }.fails(403, "AUTH_FORBIDDEN")
    }

    @Test
    fun `18_20 보호소 리포트 목록 200(userSeq 필터), USER는 403`() {
        given(reportUseCase.findForShelter(17, Pageable.ofSize(20))).willReturn(PageImpl(listOf(reportItem()), Pageable.ofSize(20), 1))
        mockMvc.get("/api/simulation/v1/simulations/reports/shelter?userSeq=17") { asShelter() }.ok().andExpect {
            jsonPath("$.data.content[0].sessionSeq") { value(3001) }
        }
        mockMvc.get("/api/simulation/v1/simulations/reports/shelter") { asUser() }.fails(403, "AUTH_FORBIDDEN")
        then(reportUseCase).should(never()).findForShelter(null, Pageable.ofSize(20))
    }
}
