package com.ssafy.animal.simulation.application.port.`in`

import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

data class StartSessionCommand(val userSeq: Int, val breedSeq: Int, val animalName: String, val expectedCost: Int)

data class ActionCommand(val actionCode: ActionCode, val itemSeq: Long?)

/** 18.1~18.5 세션, UserDeleted */
interface SessionUseCase {
    fun start(command: StartSessionCommand): SessionDetail

    /** 진행 중 세션이 없으면 null. 이 호출에서 종료되면 종료 상태 상세를 한 번 돌려준다 */
    fun findActive(userSeq: Int): SessionDetail?
    fun findMine(userSeq: Int, status: SessionStatus?, pageable: Pageable): Page<SessionSummary>
    fun get(userSeq: Int, sessionSeq: Long): SessionDetail
    fun abandon(userSeq: Int, sessionSeq: Long): AbandonResult

    /** 탈퇴(UserDeleted): 진행 중 세션 ABANDONED + 리포트 */
    fun abandonByUser(userSeq: Int)
}

/** 18.6~18.12 돌봄·상점·이력 */
interface CareUseCase {
    fun act(userSeq: Int, sessionSeq: Long, command: ActionCommand): ActionResult
    fun actionLogs(userSeq: Int, sessionSeq: Long, actionType: ActionType?, pageable: Pageable): Page<ActionLogView>
    fun shopItems(speciesType: SpeciesType?): List<ShopItemView>
    fun purchase(userSeq: Int, sessionSeq: Long, itemSeq: Long, quantity: Int): PurchaseResult
    fun inventory(userSeq: Int, sessionSeq: Long): InventoryView
    fun transactions(userSeq: Int, sessionSeq: Long, type: TransactionType?, pageable: Pageable): Page<TransactionView>
    fun events(userSeq: Int, sessionSeq: Long, status: OccurrenceStatus?, pageable: Pageable): Page<EventView>
}

/** 18.14~18.17 산책 */
interface WalkUseCase {
    fun start(userSeq: Int, sessionSeq: Long): WalkStarted
    fun heartbeat(userSeq: Int, sessionSeq: Long, walkSeq: Long): HeartbeatResult
    fun act(userSeq: Int, sessionSeq: Long, walkSeq: Long, command: ActionCommand): ActionResult
    fun end(userSeq: Int, sessionSeq: Long, walkSeq: Long): WalkEnded
}

/** 18.18~18.20 리포트 */
interface ReportUseCase {
    /** 본인은 진행 중이면 중간 리포트, 보호소는 종료 리포트만(Q483) */
    fun get(viewerUserSeq: Int, isShelter: Boolean, sessionSeq: Long): ReportView
    fun findMine(userSeq: Int, pageable: Pageable): Page<ReportListItem>
    fun findForShelter(userSeq: Int?, pageable: Pageable): Page<ReportListItem>
}
