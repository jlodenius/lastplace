package dev.lastplace.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.domain.MAX_REMINDER_MINUTES
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.reminderOffsetLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderPreferences(
    kind: ReminderKind,
    title: String,
    description: String,
    settings: ReminderSettings,
    viewModel: SettingsViewModel,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val enabled = settings.enabled(kind)
    val offsets = settings.offsets(kind)
    val presets = if (kind == ReminderKind.PLANNED_MOVE) setOf(0L, 10, 30, 60, 120)
        else setOf(0L, 120, 360, 720, 1440, 2880)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked = enabled, onCheckedChange = { viewModel.setReminderEnabled(kind, it) })
    }
    Text(description, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (presets + offsets).sortedDescending().forEach { minutes ->
            FilterChip(
                selected = minutes in offsets,
                enabled = enabled,
                onClick = { viewModel.toggleOffset(kind, minutes) },
                label = { Text(reminderOffsetLabel(minutes)) },
            )
        }
    }
    if (enabled && offsets.isEmpty()) Text("No reminder times selected.", style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = enabled, onClick = { adding = true }) { Text("Add custom reminder") }
    if (adding) {
        CustomReminderDialog(
            onDismiss = { adding = false },
            onAdd = {
                viewModel.addOffset(kind, it)
                adding = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomReminderDialog(onDismiss: () -> Unit, onAdd: (Long) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var multiplier by rememberSaveable { mutableLongStateOf(1L) }
    val value = amount.toLongOrNull()
    val minutes = value?.takeIf { it in 0..MAX_REMINDER_MINUTES / multiplier }?.times(multiplier)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add reminder before the time") },
        text = {
            Column {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("How far before?") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = amount.isNotEmpty() && minutes == null,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Minutes" to 1L, "Hours" to 60L, "Days" to 1440L).forEach { (label, factor) ->
                        FilterChip(selected = multiplier == factor, onClick = { multiplier = factor }, label = { Text(label) })
                    }
                }
                Text("Use a whole number from 0 up to 365 days. Zero means at the time.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = minutes != null, onClick = { minutes?.let(onAdd) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
