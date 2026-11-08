package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.simulation.application.port.`in`.ActionCommand
import com.ssafy.animal.simulation.application.port.`in`.StartSessionCommand
import com.ssafy.animal.simulation.domain.ActionCode
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** 18.1. animalName 필수 1~6자(한글 제한은 프론트, Q484), expectedCost 0 이상 */
data class StartSimulationRequest(
    @field:NotNull
    val breedSeq: Int?,
    @field:NotBlank
    @field:Size(max = 6)
    val animalName: String?,
    @field:NotNull
    @field:Min(0)
    val expectedCost: Int?,
) {
    fun toCommand(userSeq: Int) = StartSessionCommand(userSeq, breedSeq!!, animalName!!.trim(), expectedCost!!)
}

/** 18.6·18.16 */
data class ActionRequest(
    @field:NotNull
    val actionCode: ActionCode?,
    val itemSeq: Long?,
) {
    fun toCommand() = ActionCommand(actionCode!!, itemSeq)
}

/** 18.9. ponytail: 수량 상한 99는 금액 넘침 방지용 가안 */
data class PurchaseRequest(
    @field:NotNull
    val itemSeq: Long?,
    @field:NotNull
    @field:Min(1)
    @field:Max(99)
    val quantity: Int?,
)
