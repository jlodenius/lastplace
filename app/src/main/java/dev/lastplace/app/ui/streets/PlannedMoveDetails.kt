package dev.lastplace.app.ui.streets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lastplace.app.domain.ReparkingOption

@Composable
internal fun PlannedMoveDetails(
    plan: PlannedMoveUi,
    onCancel: () -> Unit,
    onArrived: () -> Unit,
    onChangePlan: (() -> Unit)? = null,
    option: ReparkingOption? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Planned move", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan.streetName, modifier = Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium)
            if (plan.isFavourite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favourite",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(if (option?.justCleaned == true) "${plan.timeText} · Just cleaned then" else plan.timeText)
        option?.let { ReparkingStayDetails(it) }
        plan.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (plan.timePassed) {
            Text("The planned time has passed. Confirm if you moved, or choose a new plan.", style = MaterialTheme.typography.bodySmall)
        }
        plan.remindersText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!plan.isAvailableNow) {
            Text("Destination is not available to park now.", style = MaterialTheme.typography.bodySmall)
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onArrived, enabled = plan.isAvailableNow, modifier = Modifier.fillMaxWidth()) {
                Text("I've parked here")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onChangePlan?.let {
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    ) { Text("Change plan") }
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                ) { Text("Cancel plan") }
            }
        }
    }
}
