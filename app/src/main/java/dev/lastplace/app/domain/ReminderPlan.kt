package dev.lastplace.app.domain

import java.time.Instant

enum class ReminderKind { PLANNED_MOVE, PARKING_DEADLINE }

data class ReminderSpec(
    val sessionId: Long,
    val kind: ReminderKind,
    val targetAt: Long,
    val fireAt: Long,
    val offsetMinutes: Long,
    val streetName: String,
    val planId: String? = null,
) {
    val key: String get() = "${kind.name}-$sessionId-$targetAt-${planId ?: "none"}-$offsetMinutes"
}

fun reminderPlan(
    sessionId: Long,
    kind: ReminderKind,
    targetAt: Instant,
    offsetsMinutes: Set<Long>,
    streetName: String,
    planId: String? = null,
): List<ReminderSpec> = offsetsMinutes.filter { it in 0..MAX_REMINDER_MINUTES }.sortedDescending().map { offset ->
    ReminderSpec(
        sessionId, kind, targetAt.toEpochMilli(), targetAt.minusSeconds(offset * 60).toEpochMilli(),
        offset, streetName, planId,
    )
}

const val MAX_REMINDER_MINUTES = 365L * 24 * 60

fun reminderOffsetLabel(minutes: Long): String = when {
    minutes == 0L -> "At the time"
    minutes % 1440 == 0L -> "${minutes / 1440} day${if (minutes == 1440L) "" else "s"} before"
    minutes % 60 == 0L -> "${minutes / 60} hour${if (minutes == 60L) "" else "s"} before"
    else -> "$minutes minute${if (minutes == 1L) "" else "s"} before"
}
