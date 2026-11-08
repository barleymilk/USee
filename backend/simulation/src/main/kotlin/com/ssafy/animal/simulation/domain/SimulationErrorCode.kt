package com.ssafy.animal.simulation.domain

import com.ssafy.animal.common.exception.ErrorCode
import org.springframework.http.HttpStatus

/** API 명세 19장 SIMULATION_* 전부 */
enum class SimulationErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND, "모의 입양 세션을 찾을 수 없습니다."),
    SIMULATION_BREED_NOT_FOUND(HttpStatus.NOT_FOUND, "품종을 찾을 수 없습니다."),
    SIMULATION_ACTIVE_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 진행 중인 모의 입양이 있습니다."),
    SIMULATION_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 모의 입양이 아닙니다."),
    SIMULATION_SPECIES_NOT_SUPPORTED(HttpStatus.CONFLICT, "모의 입양은 개와 고양이만 할 수 있습니다."),
    SIMULATION_SHOP_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "펫샵 물품을 찾을 수 없습니다."),
    SIMULATION_SHOP_ITEM_NOT_FOR_SPECIES(HttpStatus.CONFLICT, "이 동물에게 쓸 수 없는 물품입니다."),
    SIMULATION_EQUIPMENT_ALREADY_OWNED(HttpStatus.CONFLICT, "이미 가지고 있는 기구입니다."),
    SIMULATION_EQUIPMENT_MISSING(HttpStatus.CONFLICT, "필요한 기구가 없습니다."),
    SIMULATION_ITEM_NOT_IN_INVENTORY(HttpStatus.CONFLICT, "가지고 있는 물품이 없습니다."),
    SIMULATION_VET_NOT_NEEDED(HttpStatus.CONFLICT, "지금은 동물병원에 갈 필요가 없습니다."),
    SIMULATION_WALK_NOT_FOR_SPECIES(HttpStatus.CONFLICT, "산책은 강아지만 할 수 있습니다."),
    SIMULATION_WALK_NOT_FOUND(HttpStatus.NOT_FOUND, "산책을 찾을 수 없습니다."),
    SIMULATION_WALK_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이미 산책 중입니다."),
    SIMULATION_WALK_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 산책이 아닙니다."),
}

/**
 * 쓰기 요청 계정 재확인(Q433). core-api의 AuthErrorCode와 같은 응답 code를 내야 하지만 simulation은 core-api를 의존하지 않아 따로 둔다.
 * ponytail: common의 CommonErrorCode로 옮기면 지운다(코디네이터 요청).
 */
enum class AccountErrorCode(
    override val status: HttpStatus,
    override val message: String,
) : ErrorCode {
    AUTH_ACCOUNT_UNAVAILABLE(HttpStatus.FORBIDDEN, "이용할 수 없는 계정입니다."),
}
