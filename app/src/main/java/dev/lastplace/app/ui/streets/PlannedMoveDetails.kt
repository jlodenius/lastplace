package dev.lastplace.app.ui.streets

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun PlannedMoveDetails(plan: PlannedMoveUi, onCancel: () -> Unit, onArrived: () -> Unit) {
    Text("Planned move", fontWeight = FontWeight.Bold)
    Text("${plan.timeText} → ${plan.streetName}")
    plan.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (plan.timePassed) {
        Text("The planned time has passed. Confirm if you moved, or choose a new plan.", style = MaterialTheme.typography.bodySmall)
    }
    Text(plan.remindersText, style = MaterialTheme.typography.bodySmall)
    if (plan.isAvailableNow) {
        Button(onClick = onArrived) { Text("I've parked here") }
    } else {
        Text("Destination is not available to park now.", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onCancel) { Text("Cancel plan") }
}
