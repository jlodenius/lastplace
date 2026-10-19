package dev.lastplace.app.ui.streets

import dev.lastplace.app.domain.ReparkingOption

internal data class ReparkingOptionCard(
    val streetId: Long,
    val option: ReparkingOption?,
    val plannedMove: PlannedMoveUi? = null,
    val recommended: Boolean = false,
) {
    val isSelectedOption: Boolean
        get() {
            val plan = plannedMove ?: return false
            val candidate = option ?: return false
            return plan.problem == null && plan.plan.streetId == candidate.street.id &&
                plan.plan.justCleaned == candidate.justCleaned &&
                (if (candidate.justCleaned) plan.plan.moveAt == candidate.moveAt.toInstant().toEpochMilli() else !plan.timePassed)
        }
}

internal fun buildReparkingOptionCards(
    options: List<ReparkingOption>,
    plannedMove: PlannedMoveUi?,
): List<ReparkingOptionCard> {
    val cards = options.mapIndexed { index, option ->
        ReparkingOptionCard(option.street.id, option, recommended = index == 0)
    }
    if (plannedMove == null) return cards.take(3)
    val selected = cards.firstOrNull { it.streetId == plannedMove.plan.streetId }
        ?.copy(plannedMove = plannedMove)
        ?: ReparkingOptionCard(plannedMove.plan.streetId, option = null, plannedMove = plannedMove)
    return (listOf(selected) + cards.filterNot { it.streetId == selected.streetId }).take(3)
}
