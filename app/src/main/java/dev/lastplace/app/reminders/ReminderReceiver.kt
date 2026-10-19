package dev.lastplace.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderScheduler.ACTION_TEST) {
            Notifications.show(
                context,
                ReminderSpec(-1, ReminderKind.PARKING_DEADLINE, System.currentTimeMillis() + 3_600_000, 0, 60, "Test street"),
                test = true,
            )
            return
        }
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return
        val key = intent.data?.lastPathSegment ?: return
        val container = (context.applicationContext as ParkingApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.reminderCoordinator.deliver(key)
            } finally {
                pending.finish()
            }
        }
    }
}
