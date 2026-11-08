package com.ssafy.animal.simulation.domain

// DB 명세 v19 35~44의 CHECK 값. 컬럼은 VARCHAR라 @Enumerated(STRING)으로 매핑한다

enum class SessionStatus { IN_PROGRESS, COMPLETED, ABANDONED, FAILED }

enum class SpeciesType { DOG, CAT }

/** 상점 물품·시나리오의 종 범위 */
enum class SpeciesScope {
    DOG, CAT, ALL;

    fun covers(species: SpeciesType) = this == ALL || name == species.name
}

enum class Personality { ACTIVE, TIMID, AGGRESSIVE, FRIENDLY }

enum class ItemCategory(val equipment: Boolean) {
    FOOD_BOWL(true), WATER_BOWL(true), LITTER_BOX(true), TOY(true),
    FOOD(false), SNACK(false), LITTER(false), SHAMPOO(false), MEDICINE(false),
}

enum class WalkStatus { IN_PROGRESS, ENDED }

enum class OccurrenceStatus { PENDING, RESOLVED, EXPIRED }

enum class TriggerContext { HOME, WALK }

enum class TriggerType { RANDOM, CONDITION }

enum class TransactionType { PURCHASE, EVENT }

enum class ActionType { CARE, WALK, EVENT, SYSTEM }

/**
 * action_logs.action_code(39번). 18.6 돌봄 행동은 [type]=CARE, 18.16 산책 행동은 WALK.
 * [equipment]는 보유해야 하는 기구, [consumable]은 1개 소모하는 물품 분류(18.6 표, M9).
 */
enum class ActionCode(
    val type: ActionType,
    val equipment: ItemCategory? = null,
    val consumable: ItemCategory? = null,
) {
    FEED(ActionType.CARE, ItemCategory.FOOD_BOWL, ItemCategory.FOOD),
    WATER(ActionType.CARE, ItemCategory.WATER_BOWL),
    CLEAN_LITTER(ActionType.CARE, ItemCategory.LITTER_BOX, ItemCategory.LITTER),
    BATHE(ActionType.CARE, consumable = ItemCategory.SHAMPOO),
    PLAY(ActionType.CARE, ItemCategory.TOY),
    SNACK(ActionType.CARE, consumable = ItemCategory.SNACK),
    MEDICINE(ActionType.CARE, consumable = ItemCategory.MEDICINE),
    VET_VISIT(ActionType.CARE),
    GIVE_WATER(ActionType.WALK),
    GIVE_SNACK(ActionType.WALK, consumable = ItemCategory.SNACK),
    WALK_START(ActionType.WALK),
    WALK_END(ActionType.WALK),
    EVENT_EXPIRE(ActionType.EVENT),
    SESSION_START(ActionType.SYSTEM),
    SESSION_END(ActionType.SYSTEM),
    ;

    companion object {
        /** 18.16에서 사용자가 보낼 수 있는 산책 행동 */
        val WALK_USER_ACTIONS = setOf(GIVE_WATER, GIVE_SNACK)
    }
}
