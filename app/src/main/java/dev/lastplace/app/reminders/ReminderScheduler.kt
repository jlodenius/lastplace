package dev.lastplace.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Duration
import java.time.Instant

/**
 * Schedules exact "move your car" alarms with [AlarmManager]. Exact-and-allow-while-idle
 * alarms fire even in Doze, so reminders still arrive when the phone has been idle.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(
        sessionId: Long,
        streetName: String,
        deadline: Instant,
        reminderTimes: List<Instant>,
    ) {
        reminderTimes.forEach { time ->
            val offsetHours = Duration.between(time, deadline).toHours().toInt().coerceAtLeast(0)
            val pendingIntent = buildPendingIntent(
                sessionId = sessionId,
                offsetHours = offsetHours,
                streetName = streetName,
                deadlineMillis = deadline.toEpochMilli(),
                forCancel = false,
            ) ?: return@forEach

            val triggerAt = time.toEpochMilli()
            val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                // Permission was revoked; fall back to an inexact (but Doze-tolerant) alarm.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    /** Cancels any alarms previously scheduled for [sessionId]. */
    fun cancelAll(sessionId: Long) {
        CANDIDATE_OFFSETS.forEach { offset ->
            buildPendingIntent(sessionId, offset, streetName = null, deadlineMillis = 0L, forCancel = true)
                ?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
        }
    }

    private fun buildPendingIntent(
        sessionId: Long,
        offsetHours: Int,
        streetName: String?,
        deadlineMillis: Long,
        forCancel: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            // Extras don't affect PendingIntent matching, so cancel can omit them.
            streetName?.let {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_STREET_NAME, it)
                putExtra(EXTRA_DEADLINE_MILLIS, deadlineMillis)
                putExtra(EXTRA_OFFSET_HOURS, offsetHours)
            }
        }
        val flags = if (forCancel) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode(sessionId, offsetHours), intent, flags)
    }

    companion object {
        const val ACTION_REMINDER = "dev.lastplace.app.action.REMINDER"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STREET_NAME = "street_name"
        const val EXTRA_DEADLINE_MILLIS = "deadline_millis"
        const val EXTRA_OFFSET_HOURS = "offset_hours"

        // Superset of offsets we might have scheduled; used to cancel reliably.
        private val CANDIDATE_OFFSETS = listOf(72, 48, 24, 12, 6, 4, 2, 1)

        private fun requestCode(sessionId: Long, offsetHours: Int): Int =
            sessionId.toInt() * 100 + offsetHours
    }
}
