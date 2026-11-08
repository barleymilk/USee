package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.auth.AuthUser
import com.ssafy.animal.common.auth.Role
import com.ssafy.animal.common.response.PageResponse
import com.ssafy.animal.simulation.application.port.`in`.CareUseCase
import com.ssafy.animal.simulation.application.port.`in`.ReportUseCase
import com.ssafy.animal.simulation.application.port.`in`.SessionUseCase
import com.ssafy.animal.simulation.application.port.`in`.WalkUseCase
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 18장. "본인" 엔드포인트는 역할 대신 세션 소유로 검사하고 아니면 404 SIMULATION_NOT_FOUND(Q434·Q483)

private val AuthUser.seq get() = userSeq.toInt()

/** 18.1~18.5 세션 */
@RestController
@RequestMapping("/api/simulation/v1/simulations")
class SimulationSessionController(private val sessionUseCase: SessionUseCase) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('USER')")
    fun start(@AuthenticationPrincipal user: AuthUser, @Valid @RequestBody request: StartSimulationRequest) =
        sessionUseCase.start(request.toCommand(user.seq))

    /** 진행 중 세션이 없으면 200 + data:null */
    @GetMapping("/me/active")
    @PreAuthorize("hasRole('USER')")
    fun active(@AuthenticationPrincipal user: AuthUser) = sessionUseCase.findActive(user.seq)

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    fun mine(@AuthenticationPrincipal user: AuthUser, @RequestParam(required = false) status: SessionStatus?, pageable: Pageable) =
        PageResponse.of(sessionUseCase.findMine(user.seq, status, pageable))

    @GetMapping("/{sessionSeq}")
    fun get(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long) = sessionUseCase.get(user.seq, sessionSeq)

    @PostMapping("/{sessionSeq}/abandon")
    fun abandon(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long) = sessionUseCase.abandon(user.seq, sessionSeq)
}

/** 18.6~18.12 돌봄·상점·이력 */
@RestController
@RequestMapping("/api/simulation/v1/simulations")
class SimulationCareController(private val careUseCase: CareUseCase) {

    @PostMapping("/{sessionSeq}/actions")
    fun act(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long, @Valid @RequestBody request: ActionRequest) =
        careUseCase.act(user.seq, sessionSeq, request.toCommand())

    @GetMapping("/{sessionSeq}/action-logs")
    fun actionLogs(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable sessionSeq: Long,
        @RequestParam(required = false) actionType: ActionType?,
        pageable: Pageable,
    ) = PageResponse.of(careUseCase.actionLogs(user.seq, sessionSeq, actionType, pageable))

    /** 로그인만. 비페이지 전량 */
    @GetMapping("/shop/items")
    fun shopItems(@RequestParam(required = false) speciesType: SpeciesType?) = careUseCase.shopItems(speciesType)

    @PostMapping("/{sessionSeq}/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    fun purchase(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long, @Valid @RequestBody request: PurchaseRequest) =
        careUseCase.purchase(user.seq, sessionSeq, request.itemSeq!!, request.quantity!!)

    @GetMapping("/{sessionSeq}/inventory")
    fun inventory(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long) = careUseCase.inventory(user.seq, sessionSeq)

    @GetMapping("/{sessionSeq}/transactions")
    fun transactions(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable sessionSeq: Long,
        @RequestParam(required = false) transactionType: TransactionType?,
        pageable: Pageable,
    ) = PageResponse.of(careUseCase.transactions(user.seq, sessionSeq, transactionType, pageable))

    @GetMapping("/{sessionSeq}/events")
    fun events(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable sessionSeq: Long,
        @RequestParam(required = false) status: OccurrenceStatus?,
        pageable: Pageable,
    ) = PageResponse.of(careUseCase.events(user.seq, sessionSeq, status, pageable))
}

/** 18.14~18.17 산책 */
@RestController
@RequestMapping("/api/simulation/v1/simulations/{sessionSeq}/walks")
class SimulationWalkController(private val walkUseCase: WalkUseCase) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long) = walkUseCase.start(user.seq, sessionSeq)

    @PostMapping("/{walkSeq}/heartbeat")
    fun heartbeat(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long, @PathVariable walkSeq: Long) =
        walkUseCase.heartbeat(user.seq, sessionSeq, walkSeq)

    @PostMapping("/{walkSeq}/actions")
    fun act(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable sessionSeq: Long,
        @PathVariable walkSeq: Long,
        @Valid @RequestBody request: ActionRequest,
    ) = walkUseCase.act(user.seq, sessionSeq, walkSeq, request.toCommand())

    @PostMapping("/{walkSeq}/end")
    fun end(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long, @PathVariable walkSeq: Long) =
        walkUseCase.end(user.seq, sessionSeq, walkSeq)
}

/** 18.18~18.20 리포트. 보호소는 종료 리포트를 누구 것이든 본다(Q483) */
@RestController
@RequestMapping("/api/simulation/v1/simulations")
class SimulationReportController(private val reportUseCase: ReportUseCase) {

    @GetMapping("/{sessionSeq}/report")
    fun report(@AuthenticationPrincipal user: AuthUser, @PathVariable sessionSeq: Long) =
        reportUseCase.get(user.seq, user.role == Role.SHELTER, sessionSeq)

    @GetMapping("/reports")
    @PreAuthorize("hasRole('USER')")
    fun mine(@AuthenticationPrincipal user: AuthUser, pageable: Pageable) = PageResponse.of(reportUseCase.findMine(user.seq, pageable))

    @GetMapping("/reports/shelter")
    @PreAuthorize("hasRole('SHELTER')")
    fun forShelter(@RequestParam(required = false) userSeq: Int?, pageable: Pageable) =
        PageResponse.of(reportUseCase.findForShelter(userSeq, pageable))
}
