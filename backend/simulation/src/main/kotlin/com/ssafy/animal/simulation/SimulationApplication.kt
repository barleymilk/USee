package com.ssafy.animal.simulation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

// 역할 검사는 메서드 단위 @PreAuthorize(명세 1.1). 거부는 GlobalExceptionHandler가 403 AUTH_FORBIDDEN으로 바꾼다
@EnableMethodSecurity
@SpringBootApplication
class SimulationApplication

fun main(args: Array<String>) {
    runApplication<SimulationApplication>(*args)
}
