package com.ssafy.animal.simulation.application.service

import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.exception.CommonErrorCode
import com.ssafy.animal.common.response.ErrorDetail
import com.ssafy.animal.simulation.application.port.`in`.ActionCommand
import com.ssafy.animal.simulation.application.port.`in`.ActionLogView
import com.ssafy.animal.simulation.application.port.`in`.ActionResult
import com.ssafy.animal.simulation.application.port.`in`.CareUseCase
import com.ssafy.animal.simulation.application.port.`in`.EventView
import com.ssafy.animal.simulation.application.port.`in`.InventoryItemView
import com.ssafy.animal.simulation.application.port.`in`.InventoryView
import com.ssafy.animal.simulation.application.port.`in`.ItemBrief
import com.ssafy.animal.simulation.application.port.`in`.PurchaseResult
import com.ssafy.animal.simulation.application.port.`in`.ShopItemView
import com.ssafy.animal.simulation.application.port.`in`.TransactionView
import com.ssafy.animal.simulation.application.port.out.ActionLogRepository
import com.ssafy.animal.simulation.application.port.out.InventoryRepository
import com.ssafy.animal.simulation.application.port.out.OccurrenceRepository
import com.ssafy.animal.simulation.application.port.out.SessionRepository
import com.ssafy.animal.simulation.application.port.out.ShopItemRepository
import com.ssafy.animal.simulation.application.port.out.TransactionRepository
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.InventoryItem
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SimulationSession
import com.ssafy.animal.simulation.domain.SimulationTransaction
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 18.6~18.12. 롤백 정책은 SimulationSessionService와 같다 */
@Service
@Transactional(noRollbackFor = [BusinessException::class])
class SimulationCareService(
    private val engine: SimulationEngine,
    private val views: SimulationViewAssembler,
    private val imageUrls: ImageUrls,
    private val sessions: SessionRepository,
    private val shopItems: ShopItemRepository,
    private val inventory: InventoryRepository,
    private val transactions: TransactionRepository,
    private val actionLogs: ActionLogRepository,
    private val occurrences: OccurrenceRepository,
) : CareUseCase {

    /** 18.6 돌봄 행동. 산책 행동 코드는 18.16으로만 */
    override fun act(userSeq: Int, sessionSeq: Long, command: ActionCommand): ActionResult {
        if (command.actionCode.type != ActionType.CARE) throw invalid("actionCode", "돌봄 행동 코드가 아닙니다.")
        val session = lockInProgress(sessions, engine, userSeq, sessionSeq)
        return engine.act(session, command.actionCode, command.itemSeq, walk = null)
    }

    @Transactional(readOnly = true)
    override fun actionLogs(userSeq: Int, sessionSeq: Long, actionType: ActionType?, pageable: Pageable): Page<ActionLogView> {
        val session = findOwned(userSeq, sessionSeq)
        val page = actionLogs.findPage(session.seq, actionType, pageable)
        val items = shopItems.findAllById(page.content.mapNotNull { it.itemSeq }.toSet()).associateBy { it.itemSeq }
        return page.map { log ->
            ActionLogView(
                actionLogSeq = checkNotNull(log.actionLogSeq),
                actionType = log.actionType,
                actionCode = log.actionCode,
                item = log.itemSeq?.let { seq -> ItemBrief(seq, items[seq]?.itemName.orEmpty(), null) },
                walkSeq = log.walkSeq,
                occurrenceSeq = log.occurrenceSeq,
                statDelta = log.statDeltaJsonb,
                statsAfter = log.statsAfterJsonb,
                regDttm = log.regDttm,
            )
        }
    }

    @Transactional(readOnly = true)
    override fun shopItems(speciesType: SpeciesType?): List<ShopItemView> = shopItems.findActive(speciesType).map {
        ShopItemView(
            itemSeq = it.itemSeq,
            itemName = it.itemName,
            category = it.category,
            isConsumable = it.isConsumable,
            price = it.price,
            description = it.description,
            imageUrl = imageUrls.of(it.imageKey),
            speciesScope = it.speciesScope,
            effects = it.effectsJsonb,
        )
    }

    /** 18.9: 세션 잠금 → PURCHASE INSERT → 잔액(음수 허용) → 인벤토리 UPSERT. 기구는 수량 1·재구매 409 */
    override fun purchase(userSeq: Int, sessionSeq: Long, itemSeq: Long, quantity: Int): PurchaseResult {
        val session = lockInProgress(sessions, engine, userSeq, sessionSeq)
        val item = engine.requireItem(null, itemSeq, session, activeOnly = true)
        val owned = inventory.find(session.seq, item.itemSeq)
        if (item.category.equipment) {
            if (owned != null && owned.quantity > 0) throw BusinessException(SimulationErrorCode.SIMULATION_EQUIPMENT_ALREADY_OWNED)
            if (quantity != 1) throw invalid("quantity", "기구는 1개만 살 수 있습니다.")
        }
        val amount = Math.multiplyExact(item.price, quantity)
        val balance = session.spend(amount)
        val transaction = transactions.save(
            SimulationTransaction(session.seq, TransactionType.PURCHASE, item.itemSeq, null, quantity, amount, balance, "${item.itemName} ${quantity}개"),
        )
        val row = (owned ?: InventoryItem(session.seq, item.itemSeq, 0)).also { it.add(quantity) }
        inventory.save(row)
        return PurchaseResult(
            transactionSeq = checkNotNull(transaction.transactionSeq),
            item = ItemBrief(item.itemSeq, item.itemName, item.category),
            quantity = quantity,
            amount = amount,
            balanceAfter = balance,
            inventoryQuantity = row.quantity,
        )
    }

    @Transactional(readOnly = true)
    override fun inventory(userSeq: Int, sessionSeq: Long): InventoryView {
        val session = findOwned(userSeq, sessionSeq)
        val owned = engine.owned(session.seq)
        return InventoryView(
            balance = session.balance,
            expectedCost = session.expectedCost,
            equipment = views.equipment(session.seq),
            items = owned.sortedBy { it.item.displayOrder }.map {
                InventoryItemView(it.item.itemSeq, it.item.itemName, it.item.category, it.item.isConsumable, it.inventory.quantity, imageUrls.of(it.item.imageKey))
            },
        )
    }

    @Transactional(readOnly = true)
    override fun transactions(userSeq: Int, sessionSeq: Long, type: TransactionType?, pageable: Pageable): Page<TransactionView> {
        val session = findOwned(userSeq, sessionSeq)
        val page = transactions.findPage(session.seq, type, pageable)
        val items = shopItems.findAllById(page.content.mapNotNull { it.itemSeq }.toSet()).associateBy { it.itemSeq }
        return page.map { t ->
            TransactionView(
                transactionSeq = checkNotNull(t.transactionSeq),
                transactionType = t.transactionType,
                item = t.itemSeq?.let { seq -> ItemBrief(seq, items[seq]?.itemName.orEmpty(), items[seq]?.category) },
                occurrenceSeq = t.occurrenceSeq,
                quantity = t.quantity,
                amount = t.amount,
                balanceAfter = t.balanceAfter,
                description = t.description,
                regDttm = t.regDttm,
            )
        }
    }

    /** 18.12: 만료 감지(방치 효과)도 여기서 한다(Q441) */
    override fun events(userSeq: Int, sessionSeq: Long, status: OccurrenceStatus?, pageable: Pageable): Page<EventView> {
        val session = sessions.lock(sessionSeq)?.takeIf { it.userSeq == userSeq }
            ?: throw BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)
        engine.refresh(session)
        val page = occurrences.findPage(session.seq, status, pageable)
        return PageImpl(views.events(page.content), pageable, page.totalElements)
    }

    private fun findOwned(userSeq: Int, sessionSeq: Long): SimulationSession =
        sessions.findById(sessionSeq)?.takeIf { it.userSeq == userSeq } ?: throw BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)

    private fun invalid(field: String, reason: String) =
        BusinessException(CommonErrorCode.COMMON_INVALID_INPUT, errors = listOf(ErrorDetail(field, reason)))
}
