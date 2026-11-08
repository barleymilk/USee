package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.config.CommonWebAutoConfiguration
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.simulation.application.port.`in`.AbandonResult
import com.ssafy.animal.simulation.application.port.`in`.SessionSummary
import com.ssafy.animal.simulation.application.port.`in`.SessionUseCase
import com.ssafy.animal.simulation.application.port.`in`.StartSessionCommand
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.Personality
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

@WebMvcTest(SimulationSessionController::class)
@ImportAutoConfiguration(CommonWebAutoConfiguration::class)
class SimulationSessionControllerTest : WebTestSupport() {

    @MockitoBean
    lateinit var sessionUseCase: SessionUseCase

    private val startBody = """{"breedSeq":21,"animalName":"초코","expectedCost":150000}"""
    private val startCommand = StartSessionCommand(7, 21, "초코", 150000)

    // ===== 18.1 =====

    @Test
    fun `18_1 세션 시작 201`() {
        given(sessionUseCase.start(startCommand)).willReturn(detail())
        mockMvc.post("/api/simulation/v1/simulations") { asUser(); json(startBody) }.ok(201).andExpect {
            jsonPath("$.data.sessionSeq") { value(3001) }
            jsonPath("$.data.breed.breedName") { value("말티즈") }
            jsonPath("$.data.stats.satiety") { value(70) }
            jsonPath("$.data.equipment.foodBowl") { value(false) }
            jsonPath("$.data.pendingEvent") { value(null) }
        }
    }

    @Test
    fun `18_1 이름 6자 초과·누락, 음수 비용은 400`() {
        mockMvc.post("/api/simulation/v1/simulations") { asUser(); json("""{"breedSeq":21,"animalName":"일곱글자인이름","expectedCost":1}""") }
            .fails(400, "COMMON_INVALID_INPUT")
        mockMvc.post("/api/simulation/v1/simulations") { asUser(); json("""{"breedSeq":21,"expectedCost":-1}""") }
            .fails(400, "COMMON_INVALID_INPUT")
    }

    @Test
    fun `18_1 진행 중 409, 품종 없음 404, ETC 409`() {
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_ACTIVE_ALREADY_EXISTS)).given(sessionUseCase).start(startCommand)
        mockMvc.post("/api/simulation/v1/simulations") { asUser(); json(startBody) }.fails(409, "SIMULATION_ACTIVE_ALREADY_EXISTS")
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_BREED_NOT_FOUND)).given(sessionUseCase).start(startCommand)
        mockMvc.post("/api/simulation/v1/simulations") { asUser(); json(startBody) }.fails(404, "SIMULATION_BREED_NOT_FOUND")
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_SPECIES_NOT_SUPPORTED)).given(sessionUseCase).start(startCommand)
        mockMvc.post("/api/simulation/v1/simulations") { asUser(); json(startBody) }.fails(409, "SIMULATION_SPECIES_NOT_SUPPORTED")
    }

    @Test
    fun `18_1 보호소는 403, 토큰 없으면 401`() {
        mockMvc.post("/api/simulation/v1/simulations") { asShelter(); json(startBody) }.fails(403, "AUTH_FORBIDDEN")
        mockMvc.post("/api/simulation/v1/simulations") { json(startBody) }.fails(401, "AUTH_UNAUTHORIZED")
        then(sessionUseCase).should(never()).start(anyOf(StartSessionCommand::class.java))
    }

    // ===== 18.2~18.4 =====

    @Test
    fun `18_2 진행 중 세션 200, 없으면 200 data null`() {
        given(sessionUseCase.findActive(7)).willReturn(detail())
        mockMvc.get("/api/simulation/v1/simulations/me/active") { asUser() }.ok().andExpect { jsonPath("$.data.status") { value("IN_PROGRESS") } }
        given(sessionUseCase.findActive(7)).willReturn(null)
        mockMvc.get("/api/simulation/v1/simulations/me/active") { asUser() }.ok().andExpect { jsonPath("$.data") { value(null) } }
    }

    @Test
    fun `18_3 내 세션 목록 200`() {
        val summary = SessionSummary(3001, "초코", BREED, SpeciesType.DOG, Personality.FRIENDLY, SessionStatus.COMPLETED, 150000, 1000, NOW, NOW.plusDays(7), NOW.plusDays(7), true)
        given(sessionUseCase.findMine(7, SessionStatus.COMPLETED, Pageable.ofSize(20))).willReturn(PageImpl(listOf(summary), Pageable.ofSize(20), 1))
        mockMvc.get("/api/simulation/v1/simulations/me?status=COMPLETED") { asUser() }.ok().andExpect {
            jsonPath("$.data.content[0].hasReport") { value(true) }
            jsonPath("$.data.totalElements") { value(1) }
        }
    }

    @Test
    fun `18_4 세션 상세 200, 남의 세션 404`() {
        given(sessionUseCase.get(7, 3001)).willReturn(detail())
        mockMvc.get("/api/simulation/v1/simulations/3001") { asUser() }.ok()
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)).given(sessionUseCase).get(7, 3002)
        mockMvc.get("/api/simulation/v1/simulations/3002") { asUser() }.fails(404, "SIMULATION_NOT_FOUND")
    }

    // ===== 18.5 =====

    @Test
    fun `18_5 포기 200, 진행 중 아니면 409`() {
        given(sessionUseCase.abandon(7, 3001)).willReturn(AbandonResult(3001, SessionStatus.ABANDONED, NOW, report()))
        mockMvc.post("/api/simulation/v1/simulations/3001/abandon") { asUser() }.ok().andExpect {
            jsonPath("$.data.status") { value("ABANDONED") }
            jsonPath("$.data.report.isFinal") { value(true) }
            jsonPath("$.data.report.summary") { value(null) }
        }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS)).given(sessionUseCase).abandon(7, 3001)
        mockMvc.post("/api/simulation/v1/simulations/3001/abandon") { asUser() }.fails(409, "SIMULATION_NOT_IN_PROGRESS")
    }
}
