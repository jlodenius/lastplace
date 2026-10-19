package dev.lastplace.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by viewModel.settings.collectAsState()
    val reparkingTimes by viewModel.reparkingTimes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Preferred re-parking times", fontWeight = FontWeight.Bold)
            Text(
                "Select all times that work for you. These are cleaning end times on your saved streets. New times start unselected.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reparkingTimes.forEach { choice ->
                    FilterChip(
                        selected = choice.selected,
                        onClick = { viewModel.toggleReparkingTime(choice.time) },
                        label = { Text(choice.time.toString()) },
                        leadingIcon = if (choice.selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
            if (reparkingTimes.isEmpty()) {
                Text("Save a street with cleaning hours to see times here.", style = MaterialTheme.typography.bodySmall)
            } else if (reparkingTimes.none { it.selected }) {
                Text(
                    "No preferred times selected. While parked, you'll see longest-stay alternatives instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text("Remind me before the deadline", fontWeight = FontWeight.Bold)
            Text(
                "Pick how far ahead to be warned.",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingsViewModel.OFFSET_OPTIONS.forEach { hours ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleOffset(hours) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = hours in settings.offsetsHours,
                        onCheckedChange = { viewModel.toggleOffset(hours) },
                    )
                    Text("${hours}h before")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Auto-detect parking", fontWeight = FontWeight.Bold)
                    Text(
                        "Detect when you stop driving (coming soon).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.autoDetectEnabled,
                    onCheckedChange = viewModel::setAutoDetect,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            OutlinedButton(onClick = {
                container.parkingService.sendTestReminder()
                Toast.makeText(context, "Test reminder will fire in ~10 s", Toast.LENGTH_SHORT).show()
            }) {
                Text("Send a test reminder")
            }
        }
    }
}
