package com.ssafy.animal.simulation.application.port.`in`

import com.ssafy.animal.common.auth.Role
import com.ssafy.animal.simulation.domain.ActionCode
import com.ssafy.animal.simulation.domain.ActionType
import com.ssafy.animal.simulation.domain.ItemCategory
import com.ssafy.animal.simulation.domain.OccurrenceStatus
import com.ssafy.animal.simulation.domain.Personality
import com.ssafy.animal.simulation.domain.SessionStatus
import com.ssafy.animal.simulation.domain.SpeciesScope
import com.ssafy.animal.simulation.domain.SpeciesType
import com.ssafy.animal.simulation.domain.Stats
import com.ssafy.animal.simulation.domain.TransactionType
import com.ssafy.animal.simulation.domain.TriggerContext
import com.ssafy.animal.simulation.domain.TriggerType
import com.ssafy.animal.simulation.domain.WalkStatus
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.OffsetDateTime

// 18장 응답 DTO [초안]. 컨트롤러가 그대로 내보낸다

data class BreedView(val breedSeq: Int, val breedName: String?, val speciesType: SpeciesType, val representativeImageUrl: String?)

data class StatsView(
    val satiety: Int,
    val hydration: Int,
    val cleanliness: Int,
    val bond: Int,
    val calmness: Int,
    val health: Int,
    val energy: Int,
    val calculatedDttm: OffsetDateTime,
) {
    companion object {
        fun of(stats: Stats, at: OffsetDateTime) =
            StatsView(stats.satiety, stats.hydration, stats.cleanliness, stats.bond, stats.calmness, stats.health, stats.energy, at)
    }
}

data class EquipmentView(val foodBowl: Boolean, val waterBowl: Boolean, val litterBox: Boolean, val toy: Boolean)

data class ScenarioBrief(
    val scenarioSeq: Long,
    val title: String,
    val description: String,
    val triggerContext: TriggerContext,
    val triggerType: TriggerType,
)

data class VetVisitView(val cost: Int)

/** SimulationEvent(18.12). 정답 행동·효과는 노출하지 않는다(Q486) */
data class EventView(
    val occurrenceSeq: Long,
    val scenario: ScenarioBrief,
    val vetVisit: VetVisitView?,
    val walkSeq: Long?,
    val status: OccurrenceStatus,
    val occurredDttm: OffsetDateTime,
    val expiresDttm: OffsetDateTime,
    val resolvedDttm: OffsetDateTime?,
    val resolvedActionCode: ActionCode?,
    val resultText: String?,
)

/** 18.1·18.2·18.4 세션 상세 */
data class SessionDetail(
    val sessionSeq: Long,
    val userSeq: Int,
    val animalName: String,
    val breed: BreedView,
    val speciesType: SpeciesType,
    val personality: Personality,
    val status: SessionStatus,
    val expectedCost: Int,
    val balance: Int,
    val startedDttm: OffsetDateTime,
    val endsDttm: OffsetDateTime,
    val endedDttm: OffsetDateTime?,
    val stats: StatsView,
    val lastActionDttm: OffsetDateTime?,
    val equipment: EquipmentView,
    val pendingEvent: EventView?,
    val activeWalkSeq: Long?,
)

/** 18.3 목록 항목 */
data class SessionSummary(
    val sessionSeq: Long,
    val animalName: String,
    val breed: BreedView,
    val speciesType: SpeciesType,
    val personality: Personality,
    val status: SessionStatus,
    val expectedCost: Int,
    val balance: Int,
    val startedDttm: OffsetDateTime,
    val endsDttm: OffsetDateTime,
    val endedDttm: OffsetDateTime?,
    val hasReport: Boolean,
)

data class AbandonResult(val sessionSeq: Long, val status: SessionStatus, val endedDttm: OffsetDateTime?, val report: ReportView)

data class ConsumedItem(val itemSeq: Long, val itemName: String, val remainingQuantity: Int)

data class ResolvedEvent(
    val occurrenceSeq: Long,
    val scenarioTitle: String,
    val resultText: String?,
    val eventStatDelta: Map<String, Int>,
    val eventCost: Int,
)

/** 18.6·18.16 행동 결과 */
data class ActionResult(
    val actionLogSeq: Long,
    val actionType: ActionType,
    val actionCode: ActionCode,
    val walkSeq: Long?,
    val consumedItem: ConsumedItem?,
    val statDelta: Map<String, Int>,
    val stats: StatsView,
    val balance: Int,
    val resolvedEvent: ResolvedEvent?,
)

data class ItemBrief(val itemSeq: Long, val itemName: String, val category: ItemCategory?)

data class ActionLogView(
    val actionLogSeq: Long,
    val actionType: ActionType,
    val actionCode: ActionCode,
    val item: ItemBrief?,
    val walkSeq: Long?,
    val occurrenceSeq: Long?,
    val statDelta: Map<String, Int>?,
    val statsAfter: Map<String, Int>,
    val regDttm: OffsetDateTime?,
)

/** 18.8 펫샵 물품 */
data class ShopItemView(
    val itemSeq: Long,
    val itemName: String,
    val category: ItemCategory,
    @get:JsonProperty("isConsumable") val isConsumable: Boolean,
    val price: Int,
    val description: String?,
    val imageUrl: String?,
    val speciesScope: SpeciesScope,
    val effects: Map<String, Int>?,
)

data class PurchaseResult(
    val transactionSeq: Long,
    val item: ItemBrief,
    val quantity: Int,
    val amount: Int,
    val balanceAfter: Int,
    val inventoryQuantity: Int,
)

data class InventoryItemView(
    val itemSeq: Long,
    val itemName: String,
    val category: ItemCategory,
    @get:JsonProperty("isConsumable") val isConsumable: Boolean,
    val quantity: Int,
    val imageUrl: String?,
)

data class InventoryView(val balance: Int, val expectedCost: Int, val equipment: EquipmentView, val items: List<InventoryItemView>)

data class TransactionView(
    val transactionSeq: Long,
    val transactionType: TransactionType,
    val item: ItemBrief?,
    val occurrenceSeq: Long?,
    val quantity: Int,
    val amount: Int,
    val balanceAfter: Int,
    val description: String?,
    val regDttm: OffsetDateTime?,
)

data class WalkStarted(
    val walkSeq: Long,
    val status: WalkStatus,
    val startedDttm: OffsetDateTime,
    val heartbeatIntervalSeconds: Long,
    val stats: StatsView,
)

data class HeartbeatResult(
    val walkSeq: Long,
    val elapsedMinutes: Long,
    val stats: StatsView,
    val newEvent: EventView?,
    val pendingEvent: EventView?,
)

data class WalkEnded(
    val walkSeq: Long,
    val status: WalkStatus,
    val startedDttm: OffsetDateTime,
    val endedDttm: OffsetDateTime?,
    val durationMinutes: Long,
    val statDelta: Map<String, Int>,
    val stats: StatsView,
    val eventCount: Int,
)

/** 18.18 리포트(최종·중간 공통) */
data class ReportView(
    val sessionSeq: Long,
    val status: SessionStatus,
    @get:JsonProperty("isFinal") val isFinal: Boolean,
    val animalName: String,
    val breed: BreedView,
    val speciesType: SpeciesType,
    val personality: Personality,
    val startedDttm: OffsetDateTime,
    val endedDttm: OffsetDateTime?,
    val expectedCost: Int,
    val totalSpent: Int,
    val difference: Int,
    val spentByCategory: Map<String, Int>,
    val dailySpent: Map<String, Int>,
    val finalStats: Map<String, Int>,
    val actionCount: Int,
    val eventCount: Int,
    val eventResolvedCount: Int,
    val walkCount: Int,
    val summary: String?,
)

data class ImageRef(val imageSeq: Long, val imageUrl: String?, val displayOrder: Int)

data class ActorSummary(val userSeq: Int, val displayName: String, val role: Role?, val profileImage: ImageRef?)

/** 18.19·18.20 리포트 목록 항목. user는 18.20(보호소)에서만 채운다 */
data class ReportListItem(
    val sessionSeq: Long,
    val animalName: String,
    val breed: BreedView,
    val status: SessionStatus,
    val startedDttm: OffsetDateTime,
    val endedDttm: OffsetDateTime?,
    val expectedCost: Int,
    val totalSpent: Int,
    val difference: Int,
    val user: ActorSummary?,
)
