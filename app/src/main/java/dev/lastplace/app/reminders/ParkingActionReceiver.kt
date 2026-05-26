package dev.lastplace.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.lastplace.app.ParkingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the "I moved it" notification action: ends the active session + clears alarms. */
class ParkingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_MOVED) return
        val sessionId = intent.getLongExtra(ReminderScheduler.EXTRA_SESSION_ID, -1L)
        if (sessionId < 0) return

        val container = (context.applicationContext as ParkingApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.parkingService.endParking(sessionId)
                NotificationManagerCompat.from(context).cancelAll()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_MOVED = "dev.lastplace.app.action.MARK_MOVED"
    }
}
