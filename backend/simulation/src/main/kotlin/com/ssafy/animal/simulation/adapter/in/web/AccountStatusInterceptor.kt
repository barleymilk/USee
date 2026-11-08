package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.auth.AuthUser
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.exception.CommonErrorCode
import com.ssafy.animal.simulation.application.port.out.CoreReader
import com.ssafy.animal.simulation.domain.AccountErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 명세 1.1 Q433(core-api AccountStatusInterceptor와 같은 규칙): 인증된 쓰기 요청(POST·PATCH·DELETE)만 처리 전에
 * core.users를 다시 읽어 탈퇴 401 AUTH_TOKEN_INVALID, 정지 403 AUTH_ACCOUNT_UNAVAILABLE. GET은 재확인하지 않는다.
 * simulation은 STOMP가 없어 SEND 재확인은 해당 없음.
 */
class AccountStatusInterceptor(
    private val coreReader: CoreReader,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod || request.method !in WRITE_METHODS) return true
        val authUser = SecurityContextHolder.getContext().authentication?.principal as? AuthUser ?: return true

        // ponytail: 쓰기마다 PK 조회 1회, 캐시 없음. 부하가 문제 되면 짧은 TTL 캐시
        val account = coreReader.findAccount(authUser.userSeq.toInt())
        if (account == null || account.isDeleted) throw BusinessException(CommonErrorCode.AUTH_TOKEN_INVALID)
        if (account.accountStatus != ACTIVE) throw BusinessException(AccountErrorCode.AUTH_ACCOUNT_UNAVAILABLE)
        return true
    }

    companion object {
        private val WRITE_METHODS = setOf("POST", "PATCH", "DELETE")
        private const val ACTIVE = "ACTIVE"
    }
}

/** WebMvcConfigurer를 @Bean으로 내서 @WebMvcTest 슬라이스가 자동으로 집어 가지 않게 한다(CoreReader 없는 테스트 보호) */
@Configuration(proxyBeanMethods = false)
class AccountStatusConfig {
    @Bean
    fun accountStatusWebMvcConfigurer(coreReader: CoreReader) = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(AccountStatusInterceptor(coreReader))
        }
    }
}
