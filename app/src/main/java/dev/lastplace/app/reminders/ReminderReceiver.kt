package dev.lastplace.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.lastplace.app.MainActivity
import dev.lastplace.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fires when a scheduled reminder alarm goes off; posts the "move your car" notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getLongExtra(ReminderScheduler.EXTRA_SESSION_ID, -1L)
        val streetName = intent.getStringExtra(ReminderScheduler.EXTRA_STREET_NAME) ?: return
        val deadlineMillis = intent.getLongExtra(ReminderScheduler.EXTRA_DEADLINE_MILLIS, 0L)
        val offsetHours = intent.getIntExtra(ReminderScheduler.EXTRA_OFFSET_HOURS, 0)

        val deadlineText = Instant.ofEpochMilli(deadlineMillis)
            .atZone(ZoneId.systemDefault())
            .format(TIME_FORMAT)
        val text = "Parked on $streetName — move within ${offsetHours}h " +
            "(cleaning starts $deadlineText) to avoid a fine."

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val markMoved = PendingIntent.getBroadcast(
            context,
            sessionId.toInt() * 100 + 99,
            Intent(context, ParkingActionReceiver::class.java).apply {
                action = ParkingActionReceiver.ACTION_MARK_MOVED
                putExtra(ReminderScheduler.EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle("Move your car")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "I moved it", markMoved)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify(sessionId.toInt() * 100 + offsetHours, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing we can do here.
        }
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())
    }
}
