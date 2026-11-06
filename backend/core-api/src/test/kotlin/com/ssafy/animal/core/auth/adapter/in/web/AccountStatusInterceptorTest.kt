package com.ssafy.animal.core.auth.adapter.`in`.web

import com.ssafy.animal.common.auth.Role
import com.ssafy.animal.common.config.CommonWebAutoConfiguration
import com.ssafy.animal.core.user.application.port.out.UserRepository
import com.ssafy.animal.core.user.domain.AccountStatus
import com.ssafy.animal.core.user.domain.User
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.Base64
import java.util.Date

/** 명세 1.1: 역할 403(@PreAuthorize)과 Q433 쓰기 재확인 */
@WebMvcTest(AccountStatusInterceptorTest.ProbeController::class)
@ImportAutoConfiguration(CommonWebAutoConfiguration::class)
@Import(AccountStatusConfig::class, AccountStatusInterceptorTest.ProbeController::class) // 테스트 안의 클래스는 스캔에서 빠진다
class AccountStatusInterceptorTest {

    @RestController
    class ProbeController {
        @GetMapping("/api/core/v1/probe")
        fun read() = "ok"

        @PostMapping("/api/core/v1/probe")
        fun create() = "ok"

        @PatchMapping("/api/core/v1/probe")
        fun update() = "ok"

        @DeleteMapping("/api/core/v1/probe")
        fun remove() = "ok"

        @PostMapping("/api/core/v1/probe/shelter-only")
        @PreAuthorize("hasRole('SHELTER')")
        fun shelterOnly() = "ok"

        // application.yml security.permit-paths의 /api/core/v1/auth/kakao/** 아래
        @PostMapping("/api/core/v1/auth/kakao/probe")
        fun public() = "ok"
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var userRepository: UserRepository

    private fun user(deleted: Boolean = false, status: AccountStatus = AccountStatus.ACTIVE) =
        User(Role.USER, "반려가족", "김가람", LocalDate.of(1997, 5, 12), "01012345678").also {
            ReflectionTestUtils.setField(it, "isDeleted", deleted)
            ReflectionTestUtils.setField(it, "accountStatus", status)
        }

    private fun ResultActionsDsl.fails(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.traceId") { exists() }
    }

    @Test
    fun `역할이 맞지 않으면 403 AUTH_FORBIDDEN 봉투`() {
        given(userRepository.findById(42)).willReturn(user())
        mockMvc.post("/api/core/v1/probe/shelter-only") { header(HttpHeaders.AUTHORIZATION, BEARER) }
            .fails(403, "AUTH_FORBIDDEN")
    }

    @Test
    fun `탈퇴 계정의 쓰기는 401 AUTH_TOKEN_INVALID`() {
        given(userRepository.findById(42)).willReturn(user(deleted = true))
        mockMvc.post("/api/core/v1/probe") { header(HttpHeaders.AUTHORIZATION, BEARER) }.fails(401, "AUTH_TOKEN_INVALID")
        mockMvc.delete("/api/core/v1/probe") { header(HttpHeaders.AUTHORIZATION, BEARER) }.fails(401, "AUTH_TOKEN_INVALID")
    }

    @Test
    fun `행이 없어도 401 AUTH_TOKEN_INVALID`() {
        mockMvc.patch("/api/core/v1/probe") { header(HttpHeaders.AUTHORIZATION, BEARER) }.fails(401, "AUTH_TOKEN_INVALID")
    }

    @Test
    fun `정지 계정의 쓰기는 403 AUTH_ACCOUNT_UNAVAILABLE`() {
        given(userRepository.findById(42)).willReturn(user(status = AccountStatus.SUSPENDED))
        mockMvc.patch("/api/core/v1/probe") { header(HttpHeaders.AUTHORIZATION, BEARER) }.fails(403, "AUTH_ACCOUNT_UNAVAILABLE")
    }

    @Test
    fun `GET은 재확인하지 않아 탈퇴·정지 계정도 만료까지 조회한다`() {
        mockMvc.get("/api/core/v1/probe") { header(HttpHeaders.AUTHORIZATION, BEARER) }.andExpect { status { isOk() } }
        then(userRepository).should(never()).findById(anyInt())
    }

    @Test
    fun `활성 계정의 쓰기는 통과한다`() {
        given(userRepository.findById(42)).willReturn(user())
        mockMvc.post("/api/core/v1/probe") { header(HttpHeaders.AUTHORIZATION, BEARER) }.andExpect { status { isOk() } }
    }

    @Test
    fun `토큰 없는 비인증 경로는 재확인하지 않는다`() {
        mockMvc.post("/api/core/v1/auth/kakao/probe").andExpect { status { isOk() } }
        then(userRepository).should(never()).findById(anyInt())
    }

    companion object {
        private val keyPair = Jwts.SIG.RS256.keyPair().build()
        private val BEARER = "Bearer " + Jwts.builder().claim("userSeq", 42).claim("role", "USER").claim("typ", "access")
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(keyPair.private, Jwts.SIG.RS256).compact()

        @JvmStatic
        @DynamicPropertySource
        fun jwtProperties(registry: DynamicPropertyRegistry) {
            registry.add("jwt.public-key") { Base64.getEncoder().encodeToString(keyPair.public.encoded) }
        }
    }
}
