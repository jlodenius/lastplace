package dev.lastplace.app.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lastplace.app.reminders.ReminderScheduler

@Composable
internal fun ReminderDeliverySettings(scheduler: ReminderScheduler) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notice by remember { mutableStateOf(scheduler.deliveryNotice()) }
    var precise by remember { mutableStateOf(scheduler.canSchedulePrecisely()) }
    DisposableEffect(lifecycle, scheduler) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notice = scheduler.deliveryNotice()
                precise = scheduler.canSchedulePrecisely()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }) { Text("Android notification settings") }
    if (Build.VERSION.SDK_INT >= 31 && !precise) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        }) { Text("Allow precise reminders") }
    }
}
