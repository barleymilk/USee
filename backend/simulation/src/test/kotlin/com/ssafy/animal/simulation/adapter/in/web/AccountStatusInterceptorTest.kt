package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.config.CommonWebAutoConfiguration
import com.ssafy.animal.simulation.application.port.`in`.AbandonResult
import com.ssafy.animal.simulation.application.port.`in`.SessionUseCase
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.application.port.out.AccountRow
import com.ssafy.animal.simulation.application.port.out.CoreReader
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** 명세 1.1 Q433: 쓰기 요청만 core.users 재확인 */
@WebMvcTest(SimulationSessionController::class)
@ImportAutoConfiguration(CommonWebAutoConfiguration::class)
@Import(AccountStatusConfig::class)
class AccountStatusInterceptorTest : WebTestSupport() {

    @MockitoBean
    lateinit var sessionUseCase: SessionUseCase

    @MockitoBean
    lateinit var coreReader: CoreReader

    private val abandon = "/api/simulation/v1/simulations/3001/abandon"

    @Test
    fun `탈퇴 계정의 쓰기는 401 AUTH_TOKEN_INVALID`() {
        given(coreReader.findAccount(7)).willReturn(AccountRow(isDeleted = true, accountStatus = "ACTIVE"))
        mockMvc.post(abandon) { asUser() }.fails(401, "AUTH_TOKEN_INVALID")
        then(sessionUseCase).should(never()).abandon(7, 3001)
    }

    @Test
    fun `행이 없어도 401 AUTH_TOKEN_INVALID`() {
        mockMvc.post(abandon) { asUser() }.fails(401, "AUTH_TOKEN_INVALID")
    }

    @Test
    fun `정지 계정의 쓰기는 403 AUTH_ACCOUNT_UNAVAILABLE`() {
        given(coreReader.findAccount(7)).willReturn(AccountRow(isDeleted = false, accountStatus = "SUSPENDED"))
        mockMvc.post(abandon) { asUser() }.fails(403, "AUTH_ACCOUNT_UNAVAILABLE")
    }

    @Test
    fun `활성 계정의 쓰기는 통과한다`() {
        given(coreReader.findAccount(7)).willReturn(AccountRow(isDeleted = false, accountStatus = "ACTIVE"))
        given(sessionUseCase.abandon(7, 3001)).willReturn(AbandonResult(3001, SessionStatus.ABANDONED, NOW, report()))
        mockMvc.post(abandon) { asUser() }.andExpect { status { isOk() } }
    }

    @Test
    fun `GET은 재확인하지 않는다`() {
        mockMvc.get("/api/simulation/v1/simulations/me/active") { asUser() }.andExpect { status { isOk() } }
        then(coreReader).should(never()).findAccount(anyInt())
    }
}
