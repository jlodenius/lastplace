package dev.lastplace.app.ui.streets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
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
    isBusy: Boolean = false,
    reminderDetails: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MoveCardHeading("Planned move")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(plan.streetName, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall)
            if (plan.isFavourite) {
                Icon(Icons.Filled.Star, contentDescription = "Favourite destination", modifier = Modifier.size(18.dp))
            }
        }
        Text(
            if (option?.justCleaned == true) "${plan.timeText} · Just cleaned then" else plan.timeText,
            style = MaterialTheme.typography.titleMedium,
        )
        option?.let { ReparkingStayDetails(it) }
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onArrived,
                enabled = plan.isAvailableNow && plan.problem == null && !isBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("I've parked here", textAlign = TextAlign.Center) }
            if (onChangePlan == null) {
                PlanSecondaryAction("Cancel plan", onCancel, !isBusy, Modifier.fillMaxWidth())
            } else {
                val fontScale = LocalDensity.current.fontScale
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth / fontScale < 280.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlanSecondaryAction("Change plan", onChangePlan, !isBusy, Modifier.fillMaxWidth())
                            PlanSecondaryAction("Cancel plan", onCancel, !isBusy, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PlanSecondaryAction("Change plan", onChangePlan, !isBusy, Modifier.weight(1f).fillMaxHeight())
                            PlanSecondaryAction("Cancel plan", onCancel, !isBusy, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanSecondaryAction(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label, textAlign = TextAlign.Center)
    }
}
