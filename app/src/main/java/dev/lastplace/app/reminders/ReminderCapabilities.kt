package dev.lastplace.app.reminders

import dev.lastplace.app.domain.ReminderKind

data class ReminderCapabilities(
    val appNotificationsAllowed: Boolean = true,
    val deadlineChannelAllowed: Boolean = true,
    val moveChannelAllowed: Boolean = true,
    val preciseAlarmsAllowed: Boolean = true,
) {
    fun channelAllowed(kind: ReminderKind): Boolean = when (kind) {
        ReminderKind.PARKING_DEADLINE -> deadlineChannelAllowed
        ReminderKind.PLANNED_MOVE -> moveChannelAllowed
    }

    fun notificationsAllowed(kind: ReminderKind): Boolean = appNotificationsAllowed && channelAllowed(kind)
}
