package dev.lastplace.app.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import dev.lastplace.app.domain.ReminderSpec

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(spec: ReminderSpec) {
        val pending = pendingIntent(spec.key, false) ?: return
        scheduleAlarm(spec.fireAt, pending)
    }

    fun cancel(spec: ReminderSpec) {
        pendingIntent(spec.key, true)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
        NotificationManagerCompat.from(context).cancel(spec.key, 0)
    }

    fun show(spec: ReminderSpec) = Notifications.show(context, spec)

    fun sendTestReminder() {
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleAlarm(System.currentTimeMillis() + 10_000, pending)
    }

    fun canSchedulePrecisely(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    private fun scheduleAlarm(at: Long, pending: PendingIntent) {
        if (canSchedulePrecisely()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
                // Permission can be revoked between the capability check and scheduling.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun pendingIntent(key: String, cancel: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .setData(Uri.Builder().scheme("lastplace").authority("reminders").appendPath(key).build()),
        (if (cancel) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT) or PendingIntent.FLAG_IMMUTABLE,
    )

    fun cancelLegacy(sessionId: Long) {
        for (offset in listOf(0, 1, 2, 4, 6, 12, 24, 48, 72)) {
            PendingIntent.getBroadcast(
                context, sessionId.toInt() * 100 + offset,
                Intent(context, ReminderReceiver::class.java).setAction("dev.lastplace.app.action.REMINDER"),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
            NotificationManagerCompat.from(context).cancel(sessionId.toInt() * 100 + offset)
        }
    }

    fun capabilities(): ReminderCapabilities {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun channelAllowed(id: String) = Build.VERSION.SDK_INT < 26 ||
            manager.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
        return ReminderCapabilities(
            appNotificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            deadlineChannelAllowed = channelAllowed(Notifications.CHANNEL_REMINDERS),
            moveChannelAllowed = channelAllowed(Notifications.CHANNEL_MOVES),
            preciseAlarmsAllowed = canSchedulePrecisely(),
        )
    }

    companion object {
        const val ACTION_REMINDER = "dev.lastplace.app.action.SCHEDULED_REMINDER"
        const val ACTION_TEST = "dev.lastplace.app.action.TEST_REMINDER"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
