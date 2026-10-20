package dev.lastplace.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lastplace.app.reminders.Notifications
import dev.lastplace.app.reminders.ReminderCapabilities
import dev.lastplace.app.reminders.ReminderScheduler

@Composable
fun rememberReminderCapabilities(scheduler: ReminderScheduler): ReminderCapabilities {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var capabilities by remember(scheduler) { mutableStateOf(scheduler.capabilities()) }
    DisposableEffect(lifecycle, scheduler) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) capabilities = scheduler.capabilities()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return capabilities
}

fun openNotificationSettings(context: Context, channelId: String? = null) {
    context.startActivity(
        Intent(if (channelId == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .apply { if (channelId != null) putExtra(Settings.EXTRA_CHANNEL_ID, channelId) },
    )
}

fun openPreciseReminderSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
    }
}

@Composable
internal fun ReminderDeliverySettings(
    scheduler: ReminderScheduler,
    onTestReminder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberReminderCapabilities(scheduler)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reminder delivery", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (!capabilities.appNotificationsAllowed) {
            Text("Notifications blocked for LastPlace.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("App notifications allowed. This does not guarantee delivery.", style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { openNotificationSettings(context) }) { Text("Android notification settings") }
        if (!capabilities.deadlineChannelAllowed) {
            Text("Parking deadline notifications blocked.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { openNotificationSettings(context, Notifications.CHANNEL_REMINDERS) }) {
                Text("Allow deadline notifications")
            }
        }
        if (!capabilities.moveChannelAllowed) {
            Text("Planned move notifications blocked.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { openNotificationSettings(context, Notifications.CHANNEL_MOVES) }) {
                Text("Allow move notifications")
            }
        }
        if (!capabilities.preciseAlarmsAllowed) {
            Text("Precise reminders unavailable. Android may delay reminders.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { openPreciseReminderSettings(context) }) { Text("Allow precise reminders") }
        }
        OutlinedButton(onClick = onTestReminder) { Text("Request a test reminder") }
    }
}
