package dev.lastplace.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_REMINDERS = "reminders"

    /** minSdk 26, so notification channels always exist. */
    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            "Move-your-car reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns you before a street-cleaning window so you can move the car."
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
