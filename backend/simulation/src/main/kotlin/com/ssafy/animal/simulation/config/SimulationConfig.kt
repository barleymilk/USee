package com.ssafy.animal.simulation.config

import com.ssafy.animal.simulation.domain.SimulationProperties
import com.ssafy.animal.simulation.domain.StatCalculator
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.ZoneId

/** 10분 스케줄러(S8), 리포트 요약 비동기(Q487), 튜닝값(Q429) */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(SimulationProperties::class)
class SimulationConfig {

    /** 서비스는 시각을 이 Clock으로만 얻는다(테스트에서 고정) */
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))

    @Bean
    fun statCalculator(props: SimulationProperties) = StatCalculator(props)
}
