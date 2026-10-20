package dev.lastplace.app.ui.streets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lastplace.app.domain.ReparkingOption

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlannedMoveDetails(
    plan: PlannedMoveUi,
    onCancel: () -> Unit,
    onArrived: () -> Unit,
    onChangePlan: (() -> Unit)? = null,
    option: ReparkingOption? = null,
    compact: Boolean = false,
    isBusy: Boolean = false,
    reminderDetails: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Planned move", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(plan.streetName, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
            if (plan.isFavourite) {
                Icon(Icons.Filled.Star, contentDescription = "Favourite destination", modifier = Modifier.size(18.dp))
            }
        }
        Text(if (option?.justCleaned == true) "${plan.timeText} · Just cleaned then" else plan.timeText)
        option?.takeUnless { compact }?.let { ReparkingStayDetails(it) }
        plan.problem?.let { problem ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(problem, color = MaterialTheme.colorScheme.error)
            }
        }
        if (plan.timePassed) {
            Text(if (plan.problem == null) "The planned time has passed. Confirm if you moved, or choose a new plan."
            else "The planned time has passed.")
        }
        plan.remindersText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        reminderDetails?.invoke()
        if (plan.problem != null) {
            Text("Arrival is disabled. Change or cancel this plan.")
        } else if (!plan.isAvailableNow) {
            Text("Destination is unavailable now. Arrival is disabled until its saved cleaning hours allow it.")
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (compact) {
                OutlinedButton(onClick = onArrived, enabled = plan.isAvailableNow && plan.problem == null && !isBusy) {
                    Text("I've parked here")
                }
            } else {
                Button(onClick = onArrived, enabled = plan.isAvailableNow && plan.problem == null && !isBusy) {
                    Text("I've parked here")
                }
            }
            onChangePlan?.let {
                TextButton(onClick = it, enabled = !isBusy) { Text("Change plan") }
            }
            TextButton(onClick = onCancel, enabled = !isBusy) { Text("Cancel plan") }
        }
    }
}
