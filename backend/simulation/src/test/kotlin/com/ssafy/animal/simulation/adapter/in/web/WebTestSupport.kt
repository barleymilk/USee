package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.auth.AuthUser
import com.ssafy.animal.common.auth.JwtVerifier
import com.ssafy.animal.common.auth.Role
import com.ssafy.animal.simulation.application.port.`in`.ActionResult
import com.ssafy.animal.simulation.application.port.`in`.BreedView
import com.ssafy.animal.simulation.application.port.`in`.EquipmentView
import com.ssafy.animal.simulation.application.port.`in`.ReportListItem
import com.ssafy.animal.simulation.application.port.`in`.ReportView
import com.ssafy.animal.simulation.application.port.`in`.SessionDetail
import com.ssafy.animal.simulation.application.port.`in`.StatsView
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.Personality
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SpeciesType
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.junit.jupiter.api.BeforeEach
import java.time.OffsetDateTime

/**
 * 컨트롤러 슬라이스 공통. JWT 검증기를 목으로 바꿔 "user"·"shelter" 토큰을 주체로 바꾼다
 * (simulation 테스트 클래스패스에는 jjwt가 없어 실제 토큰을 만들지 않는다).
 */
abstract class WebTestSupport {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var jwtVerifier: JwtVerifier

    @BeforeEach
    fun tokens() {
        given(jwtVerifier.verify(USER_TOKEN)).willReturn(AuthUser(7, Role.USER, null))
        given(jwtVerifier.verify(SHELTER_TOKEN)).willReturn(AuthUser(1, Role.SHELTER, 1))
    }

    fun MockHttpServletRequestDsl.asUser() = header(HttpHeaders.AUTHORIZATION, "Bearer $USER_TOKEN")

    fun MockHttpServletRequestDsl.asShelter() = header(HttpHeaders.AUTHORIZATION, "Bearer $SHELTER_TOKEN")

    fun MockHttpServletRequestDsl.json(body: String) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    fun ResultActionsDsl.fails(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.data") { doesNotExist() }
    }

    fun ResultActionsDsl.ok(status: Int = 200) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.code") { value("SUCCESS") }
    }

    /** Kotlin 비널 파라미터용 Mockito.any */
    @Suppress("UNCHECKED_CAST")
    fun <T> anyOf(type: Class<T>): T {
        Mockito.any(type)
        return null as T
    }

    companion object {
        const val USER_TOKEN = "user"
        const val SHELTER_TOKEN = "shelter"
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-11-01T14:00:00+09:00")
        val STATS = StatsView(70, 70, 80, 30, 50, 90, 60, NOW)
        val BREED = BreedView(21, "말티즈", SpeciesType.DOG, null)

        fun detail(status: SessionStatus = SessionStatus.IN_PROGRESS) = SessionDetail(
            3001, 7, "초코", BREED, SpeciesType.DOG, Personality.FRIENDLY, status, 150000, 150000, NOW, NOW.plusDays(7), null,
            STATS, null, EquipmentView(false, false, false, false), null, null,
        )

        fun report(isFinal: Boolean = true) = ReportView(
            3001, SessionStatus.COMPLETED, isFinal, "초코", BREED, SpeciesType.DOG, Personality.FRIENDLY, NOW, NOW.plusDays(7),
            150000, 182000, -32000, mapOf("FOOD" to 40000), mapOf("1" to 39000), mapOf("health" to 84), 41, 9, 6, 6, null,
        )

        fun reportItem() = ReportListItem(3001, "초코", BREED, SessionStatus.COMPLETED, NOW, NOW.plusDays(7), 150000, 182000, -32000, null)

        fun actionResult(type: ActionType = ActionType.CARE, code: ActionCode = ActionCode.FEED) =
            ActionResult(50001, type, code, null, null, mapOf("satiety" to 30), STATS, 118000, null)
    }
}
