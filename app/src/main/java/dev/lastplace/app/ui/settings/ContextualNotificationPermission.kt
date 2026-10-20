package dev.lastplace.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun ContextualNotificationPermission(trigger: Any?, onHandled: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(trigger) {
        if (trigger != null) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("rationale_seen", false)
            ) {
                preferences.edit().putBoolean("rationale_seen", true).apply()
                explaining = true
            }
            onHandled()
        }
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("Allow parking reminders?") },
            text = {
                Text("LastPlace needs notification permission to show your parking deadline warnings and planned move reminders. Your parking is saved even if you choose not to allow notifications. You can change this later in Android notification settings.")
            },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explaining = false }) { Text("Not now") } },
        )
    }
}
