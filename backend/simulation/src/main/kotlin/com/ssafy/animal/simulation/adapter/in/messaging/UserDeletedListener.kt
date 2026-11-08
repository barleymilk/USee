package com.ssafy.animal.simulation.adapter.`in`.messaging

import com.ssafy.animal.common.event.UserDeleted
import com.ssafy.animal.simulation.application.port.`in`.SessionUseCase
import org.springframework.amqp.core.ExchangeTypes
import org.springframework.amqp.rabbit.annotation.Exchange
import org.springframework.amqp.rabbit.annotation.Queue
import org.springframework.amqp.rabbit.annotation.QueueBinding
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** core-api 탈퇴(2.6) → 진행 중 세션 ABANDONED + 리포트(21.6). 처리는 유스케이스에 둔다 */
@Component
@ConditionalOnProperty(name = ["events.transport"], havingValue = "rabbit")
class UserDeletedListener(private val sessionUseCase: SessionUseCase) {

    @RabbitListener(
        bindings = [
            QueueBinding(
                value = Queue("simulation.UserDeleted", durable = "true"),
                exchange = Exchange("animal.events", type = ExchangeTypes.TOPIC),
                key = ["UserDeleted"],
            ),
        ],
    )
    fun onUserDeleted(event: UserDeleted) = sessionUseCase.abandonByUser(event.userSeq.toInt())
}
