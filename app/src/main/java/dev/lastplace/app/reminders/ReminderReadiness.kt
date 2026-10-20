package dev.lastplace.app.reminders

import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import java.time.Instant

enum class ReminderSchedulingState { UPDATING, OFF, NO_TIMES, NO_TARGET, CANCELLED, NO_UPCOMING, SCHEDULED, DUE }

data class ReminderStatus(
    val state: ReminderSchedulingState,
    val reminder: ReminderSpec? = null,
)

data class ReminderReadiness(
    val sessionId: Long? = null,
    val planId: String? = null,
    val updating: Boolean = true,
    val pending: List<ReminderSpec> = emptyList(),
    val settings: ReminderSettings = ReminderSettings(),
    val deadline: Long? = null,
    val planInvalidReason: String? = null,
) {
    fun status(kind: ReminderKind, now: Instant): ReminderStatus {
        val state = when {
            updating -> ReminderSchedulingState.UPDATING
            !settings.enabled(kind) -> ReminderSchedulingState.OFF
            settings.offsets(kind).isEmpty() -> ReminderSchedulingState.NO_TIMES
            sessionId == null -> ReminderSchedulingState.NO_TARGET
            kind == ReminderKind.PLANNED_MOVE && planInvalidReason != null -> ReminderSchedulingState.CANCELLED
            kind == ReminderKind.PLANNED_MOVE && planId == null -> ReminderSchedulingState.NO_TARGET
            kind == ReminderKind.PARKING_DEADLINE && deadline == null -> ReminderSchedulingState.NO_TARGET
            else -> null
        }
        if (state != null) return ReminderStatus(state)
        val next = pending.asSequence().filter {
            it.kind == kind && it.sessionId == sessionId &&
                (kind != ReminderKind.PLANNED_MOVE ||
                    it.planId == planId && now.toEpochMilli() <= it.targetAt + MOVE_DELIVERY_GRACE_MILLIS)
        }.minByOrNull { it.fireAt } ?: return ReminderStatus(ReminderSchedulingState.NO_UPCOMING)
        return ReminderStatus(
            if (next.fireAt <= now.toEpochMilli()) ReminderSchedulingState.DUE else ReminderSchedulingState.SCHEDULED,
            next,
        )
    }
}
