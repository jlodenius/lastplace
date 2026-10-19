package dev.lastplace.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.lastplace.app.MainActivity
import dev.lastplace.app.R
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_MOVES = "planned_moves"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_REMINDERS, "Parking deadline warnings", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Warns before cleaning starts where you are currently parked." })
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_MOVES, "Planned move reminders", NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Reminds you about the re-parking plan you selected." })
    }

    fun show(context: Context, spec: ReminderSpec, test: Boolean = false) {
        val move = spec.kind == ReminderKind.PLANNED_MOVE
        val time = Instant.ofEpochMilli(spec.targetAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault()))
        val overdue = System.currentTimeMillis() >= spec.targetAt
        val title = when {
            test -> "LastPlace test reminder"
            move -> if (overdue) "Time for your planned move" else "Planned move approaching"
            overdue -> "Parking deadline passed — move now"
            else -> "Parking deadline approaching"
        }
        val text = if (move) "Your selected move to ${spec.streetName}: $time. Confirm in the app after you have parked."
            else "Parked on ${spec.streetName}. Cleaning starts $time — leave before then."
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, if (move) CHANNEL_MOVES else CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
        if (move) builder.addAction(0, "View plan", openApp)
        else if (!test) {
            val moved = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, ParkingActionReceiver::class.java)
                    .setAction(ParkingActionReceiver.ACTION_MARK_MOVED)
                    .setData(Uri.parse("lastplace://ended/${spec.sessionId}"))
                    .putExtra(ReminderScheduler.EXTRA_SESSION_ID, spec.sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "I moved it", moved)
        }
        try {
            NotificationManagerCompat.from(context).notify(if (test) "test" else spec.key, 0, builder.build())
        } catch (_: SecurityException) {
            // Notification permission is controlled by Android, independently of in-app preferences.
        }
    }
}
