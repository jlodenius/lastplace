package dev.lastplace.app.ui.streets

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.ReparkingOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReparkingOptionsScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ParkingApp).container
    val viewModel: StreetListViewModel = viewModel(factory = StreetListViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val cards = buildReparkingOptionCards(state.reparkingOptions, state.plannedMove)
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Re-parking options") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val active = state.active
                when {
                    active == null -> Text("Park on a saved street to see re-parking options.")
                    !active.hasDeadline -> Text("Your current street has no cleaning deadline. No move needed.")
                    else -> {
                        Text("Parked on ${active.streetName}", fontWeight = FontWeight.Bold)
                        Text("Move before ${active.deadlineText}")
                        Text(active.remainingText)
                        Text(
                            if (state.plannedMove != null) {
                                "Your selected plan and up to two alternatives. Choosing another option replaces your plan."
                            } else if (state.reparkingOptions.firstOrNull()?.justCleaned == true) {
                                "Up to three just-cleaned destinations at your preferred times, latest opportunity first. These are plans, not parking availability now."
                            } else {
                                "No preferred just-cleaned opportunity remains. These alternatives are available now, longest stay first."
                            },
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.reparkingOptions.isEmpty()) {
                            Text(
                                if (state.plannedMove != null) "No alternative streets are available right now."
                                else "No other saved street is available. Check another location before your deadline.",
                            )
                        }
                    }
                }
            }
            items(cards, key = { it.streetId }) { card ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = if (card.plannedMove != null) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.plannedMove?.let { plan ->
                            PlannedMoveDetails(
                                plan,
                                onCancel = { viewModel.cancelPlan() },
                                onArrived = { viewModel.arriveAtPlan() },
                                option = card.option.takeIf { card.isSelectedOption },
                            )
                        }
                        card.option?.takeUnless { card.isSelectedOption }?.let { option ->
                            if (card.plannedMove != null) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Text(
                                when {
                                    card.plannedMove != null -> "New opportunity on this street"
                                    card.recommended -> "Recommended"
                                    else -> "Alternative"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            ReparkingOptionDetails(option)
                            OutlinedButton(onClick = { viewModel.selectPlan(option) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (option.justCleaned) "Plan this move" else "Plan move now")
                            }
                        }
                    }
                }
            }
            if (cards.isNotEmpty()) {
                item {
                    Text(
                        "Selecting a plan does not register parking. Confirm after you have moved. Availability is based on saved schedules, not live parking spaces.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReparkingOptionDetails(option: ReparkingOption) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(option.street.name, modifier = Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium)
        if (option.street.isFavourite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favourite",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        if (option.justCleaned) "${option.moveAt.format(parkingTimeFormatter)} · Just cleaned then"
        else "Available now · Move now for the longest stay",
    )
    ReparkingStayDetails(option)
}

@Composable
internal fun ReparkingStayDetails(option: ReparkingOption) {
    Text(
        option.nextCleaningStart?.let {
            "Park for ${freeForText(option.moveAt, it)} · Until ${it.format(parkingTimeFormatter)}"
        } ?: "No scheduled cleaning",
        style = MaterialTheme.typography.bodySmall,
    )
}
