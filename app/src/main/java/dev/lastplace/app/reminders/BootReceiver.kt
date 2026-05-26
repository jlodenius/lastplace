package dev.lastplace.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lastplace.app.ParkingApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarms are cleared on reboot; re-schedule reminders for the active session. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as ParkingApp).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.parkingService.rescheduleActive()
            } finally {
                pending.finish()
            }
        }
    }
}
