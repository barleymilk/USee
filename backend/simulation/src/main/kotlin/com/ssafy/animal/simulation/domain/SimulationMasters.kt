package com.ssafy.animal.simulation.domain

import com.ssafy.animal.common.persistence.BaseTimeEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal

// 36·41번 마스터는 Flyway 시드로만 넣고 앱은 읽기만 한다(Q412)

/** DB 명세 36. simulation_shop_items */
@Entity
@Immutable
@Table(name = "simulation_shop_items")
class ShopItem(
    @field:Id
    val itemSeq: Long,
    val itemName: String,
    @field:Enumerated(EnumType.STRING)
    val category: ItemCategory,
    val isConsumable: Boolean,
    val price: Int,
    val description: String?,
    val imageKey: String?,
    @field:Enumerated(EnumType.STRING)
    val speciesScope: SpeciesScope,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val effectsJsonb: Map<String, Int>?,
    val isActive: Boolean,
    val displayOrder: Int,
) : BaseTimeEntity() {
    val effects get() = Stats.keys(effectsJsonb)
}

/** DB 명세 41. simulation_event_scenarios. 정답 행동 1개(Q486) */
@Entity
@Immutable
@Table(name = "simulation_event_scenarios")
class EventScenario(
    @field:Id
    val scenarioSeq: Long,
    val title: String,
    val description: String,
    @field:Enumerated(EnumType.STRING)
    val triggerContext: TriggerContext,
    @field:Enumerated(EnumType.STRING)
    val triggerType: TriggerType,
    /** 예 {"stat":"health","op":"<","value":40} */
    @field:JdbcTypeCode(SqlTypes.JSON)
    val triggerConditionJsonb: Map<String, Any>?,
    val cooldownHours: Short?,
    val triggerProbability: BigDecimal,
    @field:Enumerated(EnumType.STRING)
    val speciesScope: SpeciesScope,
    @field:Enumerated(EnumType.STRING)
    val personalityScope: Personality?,
    val weight: Short,
    @field:Enumerated(EnumType.STRING)
    val resolveActionCode: ActionCode,
    val resolveCost: Int,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val resolveEffectsJsonb: Map<String, Int>?,
    val resultText: String?,
    @field:JdbcTypeCode(SqlTypes.JSON)
    val neglectEffectsJsonb: Map<String, Int>?,
    val neglectText: String?,
    val isActive: Boolean,
) : BaseTimeEntity() {

    val resolveEffects get() = Stats.keys(resolveEffectsJsonb)
    val neglectEffects get() = Stats.keys(neglectEffectsJsonb)

    fun appliesTo(species: SpeciesType, personality: Personality) =
        isActive && speciesScope.covers(species) && (personalityScope == null || personalityScope == personality)

    /** CONDITION 조건식 판정. 형식이 틀리면 발생시키지 않는다 */
    fun conditionMet(stats: Stats): Boolean {
        val condition = triggerConditionJsonb ?: return false
        val key = StatKey.entries.firstOrNull { it.name == condition["stat"] } ?: return false
        val value = (condition["value"] as? Number)?.toInt() ?: return false
        val actual = stats[key]
        return when (condition["op"]) {
            "<" -> actual < value
            "<=" -> actual <= value
            ">" -> actual > value
            ">=" -> actual >= value
            "=", "==" -> actual == value
            else -> false
        }
    }
}
