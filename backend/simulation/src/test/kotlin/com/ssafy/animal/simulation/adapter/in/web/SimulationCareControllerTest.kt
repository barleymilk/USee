package com.ssafy.animal.simulation.adapter.`in`.web

import com.ssafy.animal.common.config.CommonWebAutoConfiguration
import com.ssafy.animal.common.exception.BusinessException
import com.ssafy.animal.common.exception.CommonErrorCode
import com.ssafy.animal.simulation.application.port.`in`.ActionCommand
import com.ssafy.animal.simulation.application.port.`in`.ActionLogView
import com.ssafy.animal.simulation.application.port.`in`.CareUseCase
import com.ssafy.animal.simulation.application.port.`in`.EquipmentView
import com.ssafy.animal.simulation.application.port.`in`.EventView
import com.ssafy.animal.simulation.application.port.`in`.InventoryItemView
import com.ssafy.animal.simulation.application.port.`in`.InventoryView
import com.ssafy.animal.simulation.application.port.`in`.ItemBrief
import com.ssafy.animal.simulation.application.port.`in`.PurchaseResult
import com.ssafy.animal.simulation.application.port.`in`.ScenarioBrief
import com.ssafy.animal.simulation.application.port.`in`.ShopItemView
import com.ssafy.animal.simulation.application.port.`in`.TransactionView
import com.ssafy.animal.simulation.application.port.`in`.VetVisitView
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.SimulationErrorCode
import com.ssafy.animal.simulation.domain.SpeciesScope
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import com.ssafy.animal.simulation.domain.TriggerType
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(SimulationCareController::class)
@ImportAutoConfiguration(CommonWebAutoConfiguration::class)
class SimulationCareControllerTest : WebTestSupport() {

    @MockitoBean
    lateinit var careUseCase: CareUseCase

    private val base = "/api/simulation/v1/simulations/3001"
    private val feed = ActionCommand(ActionCode.FEED, 11)

    // ===== 18.6 =====

    @Test
    fun `18_6 돌봄 행동 200`() {
        given(careUseCase.act(7, 3001, feed)).willReturn(actionResult())
        mockMvc.post("$base/actions") { asUser(); json("""{"actionCode":"FEED","itemSeq":11}""") }.ok().andExpect {
            jsonPath("$.data.actionType") { value("CARE") }
            jsonPath("$.data.statDelta.satiety") { value(30) }
            jsonPath("$.data.resolvedEvent") { value(null) }
        }
    }

    @Test
    fun `18_6 행동 실패 코드`() {
        val cases = listOf(
            SimulationErrorCode.SIMULATION_EQUIPMENT_MISSING,
            SimulationErrorCode.SIMULATION_ITEM_NOT_IN_INVENTORY,
            SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS,
            SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOR_SPECIES,
            SimulationErrorCode.SIMULATION_VET_NOT_NEEDED,
            SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOUND,
            SimulationErrorCode.SIMULATION_NOT_FOUND,
            CommonErrorCode.COMMON_INVALID_INPUT,
        )
        cases.forEach { code ->
            willThrow(BusinessException(code)).given(careUseCase).act(7, 3001, feed)
            mockMvc.post("$base/actions") { asUser(); json("""{"actionCode":"FEED","itemSeq":11}""") }.fails(code.status.value(), code.name)
        }
    }

    @Test
    fun `18_6 actionCode 누락은 400, 모르는 코드는 400`() {
        mockMvc.post("$base/actions") { asUser(); json("""{"itemSeq":11}""") }.fails(400, "COMMON_INVALID_INPUT")
        mockMvc.post("$base/actions") { asUser(); json("""{"actionCode":"DANCE"}""") }.fails(400, "COMMON_INVALID_JSON")
    }

    // ===== 18.7 =====

    @Test
    fun `18_7 행동 이력 200, 남의 세션 404`() {
        val log = ActionLogView(50001, ActionType.CARE, ActionCode.FEED, ItemBrief(11, "건식 사료 1kg", null), null, null, mapOf("satiety" to 30), mapOf("satiety" to 94), NOW)
        given(careUseCase.actionLogs(7, 3001, ActionType.CARE, Pageable.ofSize(20))).willReturn(PageImpl(listOf(log), Pageable.ofSize(20), 1))
        mockMvc.get("$base/action-logs?actionType=CARE") { asUser() }.ok().andExpect {
            jsonPath("$.data.content[0].item.itemName") { value("건식 사료 1kg") }
            jsonPath("$.data.content[0].statsAfter.satiety") { value(94) }
        }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)).given(careUseCase).actionLogs(7, 3002, null, Pageable.ofSize(20))
        mockMvc.get("/api/simulation/v1/simulations/3002/action-logs") { asUser() }.fails(404, "SIMULATION_NOT_FOUND")
    }

    // ===== 18.8 =====

    @Test
    fun `18_8 펫샵 물품 목록 200(보호소도 로그인이면 허용)`() {
        val item = ShopItemView(1, "스테인리스 밥그릇", ItemCategory.FOOD_BOWL, false, 12000, null, "https://cdn/x.png", SpeciesScope.ALL, null)
        given(careUseCase.shopItems(SpeciesType.DOG)).willReturn(listOf(item))
        mockMvc.get("/api/simulation/v1/simulations/shop/items?speciesType=DOG") { asShelter() }.ok().andExpect {
            jsonPath("$.data[0].category") { value("FOOD_BOWL") }
            jsonPath("$.data[0].isConsumable") { value(false) }
        }
        mockMvc.get("/api/simulation/v1/simulations/shop/items").fails(401, "AUTH_UNAUTHORIZED")
    }

    // ===== 18.9 =====

    @Test
    fun `18_9 구매 201`() {
        given(careUseCase.purchase(7, 3001, 11, 5)).willReturn(PurchaseResult(70001, ItemBrief(11, "건식 사료 1kg", ItemCategory.FOOD), 5, 40000, 110000, 5))
        mockMvc.post("$base/purchases") { asUser(); json("""{"itemSeq":11,"quantity":5}""") }.ok(201).andExpect {
            jsonPath("$.data.balanceAfter") { value(110000) }
            jsonPath("$.data.inventoryQuantity") { value(5) }
        }
    }

    @Test
    fun `18_9 수량 0은 400, 실패 코드`() {
        mockMvc.post("$base/purchases") { asUser(); json("""{"itemSeq":11,"quantity":0}""") }.fails(400, "COMMON_INVALID_INPUT")
        listOf(
            SimulationErrorCode.SIMULATION_EQUIPMENT_ALREADY_OWNED,
            SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOR_SPECIES,
            SimulationErrorCode.SIMULATION_SHOP_ITEM_NOT_FOUND,
            SimulationErrorCode.SIMULATION_NOT_IN_PROGRESS,
        ).forEach { code ->
            willThrow(BusinessException(code)).given(careUseCase).purchase(7, 3001, 1, 1)
            mockMvc.post("$base/purchases") { asUser(); json("""{"itemSeq":1,"quantity":1}""") }.fails(code.status.value(), code.name)
        }
    }

    // ===== 18.10·18.11 =====

    @Test
    fun `18_10 인벤토리 200, 404`() {
        val view = InventoryView(110000, 150000, EquipmentView(true, false, false, false), listOf(InventoryItemView(11, "건식 사료 1kg", ItemCategory.FOOD, true, 0, null)))
        given(careUseCase.inventory(7, 3001)).willReturn(view)
        mockMvc.get("$base/inventory") { asUser() }.ok().andExpect {
            jsonPath("$.data.equipment.foodBowl") { value(true) }
            jsonPath("$.data.items[0].quantity") { value(0) }
        }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)).given(careUseCase).inventory(7, 3002)
        mockMvc.get("/api/simulation/v1/simulations/3002/inventory") { asUser() }.fails(404, "SIMULATION_NOT_FOUND")
    }

    @Test
    fun `18_11 지출 내역 200`() {
        val t = TransactionView(70001, TransactionType.EVENT, null, 90001, 1, 30000, 80000, "진료비", NOW)
        given(careUseCase.transactions(7, 3001, TransactionType.EVENT, Pageable.ofSize(20))).willReturn(PageImpl(listOf(t), Pageable.ofSize(20), 1))
        mockMvc.get("$base/transactions?transactionType=EVENT") { asUser() }.ok().andExpect {
            jsonPath("$.data.content[0].occurrenceSeq") { value(90001) }
        }
    }

    // ===== 18.12 =====

    @Test
    fun `18_12 발생 이벤트 200, 정답 행동은 노출하지 않고 병원 이벤트만 진료비`() {
        val event = EventView(
            90001, ScenarioBrief(5, "축 처져 있어요", "설명", TriggerContext.HOME, TriggerType.CONDITION), VetVisitView(30000), null,
            OccurrenceStatus.PENDING, NOW, NOW.plusHours(6), null, null, null,
        )
        given(careUseCase.events(7, 3001, OccurrenceStatus.PENDING, Pageable.ofSize(20))).willReturn(PageImpl(listOf(event), Pageable.ofSize(20), 1))
        mockMvc.get("$base/events?status=PENDING") { asUser() }.ok().andExpect {
            jsonPath("$.data.content[0].vetVisit.cost") { value(30000) }
            jsonPath("$.data.content[0].scenario.resolveActionCode") { doesNotExist() }
        }
        willThrow(BusinessException(SimulationErrorCode.SIMULATION_NOT_FOUND)).given(careUseCase).events(7, 3002, null, Pageable.ofSize(20))
        mockMvc.get("/api/simulation/v1/simulations/3002/events") { asUser() }.fails(404, "SIMULATION_NOT_FOUND")
    }
}
