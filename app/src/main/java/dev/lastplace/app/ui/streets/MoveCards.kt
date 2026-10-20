package dev.lastplace.app.ui.streets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lastplace.app.domain.ReparkingOption

@Composable
private fun MoveCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun MoveCardHeading(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
internal fun PlannedMoveCard(
    plan: PlannedMoveUi,
    onCancel: () -> Unit,
    onArrived: () -> Unit,
    onChangePlan: () -> Unit,
    isBusy: Boolean = false,
    reminderDetails: (@Composable () -> Unit)? = null,
) {
    MoveCard(Modifier.testTag("planned-move-card")) {
        PlannedMoveDetails(
            plan = plan,
            onCancel = onCancel,
            onArrived = onArrived,
            onChangePlan = onChangePlan,
            isBusy = isBusy,
            reminderDetails = reminderDetails,
        )
    }
}

@Composable
internal fun SuggestedMoveCard(
    option: ReparkingOption?,
    hasPreferredTimes: Boolean,
    onPlan: (ReparkingOption) -> Unit,
    onOpenOptions: () -> Unit,
    onChooseTimes: () -> Unit,
    isBusy: Boolean = false,
) {
    MoveCard(Modifier.testTag("suggested-move-card")) {
        MoveCardHeading("Suggested move")
        if (option != null) {
            Text(option.street.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (option.justCleaned) "Move at ${option.moveAt.format(parkingTimeFormatter)}" else "Move now · Longest stay",
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text("No available destination", style = MaterialTheme.typography.headlineSmall)
            Text("Check another location before your deadline.")
        }
        if (!hasPreferredTimes) {
            Text("No preferred re-parking times selected")
            TextButton(onClick = onChooseTimes, enabled = !isBusy) { Text("Choose times") }
        } else if (option?.justCleaned == false) {
            Text("No preferred-time move available.", style = MaterialTheme.typography.bodyMedium)
        }
        if (option != null) {
            Button(
                onClick = { onPlan(option) },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Plan this move") }
            OutlinedButton(
                onClick = onOpenOptions,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("View options") }
        }
    }
}
