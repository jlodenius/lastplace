package dev.lastplace.app.ui.streets

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.ReparkingOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetListScreen(
    onAddStreet: () -> Unit,
    onEditStreet: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReparkingOptions: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: StreetListViewModel = viewModel(factory = StreetListViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val parkLoading by viewModel.parkLoading.collectAsState()

    LaunchedEffectMessage(message) {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.parkWhereIAm()
        else Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My streets") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddStreet) {
                Icon(Icons.Filled.Add, contentDescription = "Add street")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item {
                state.active?.let { active ->
                    ActiveParkingCard(
                        active = active,
                        recommendation = state.reparkingOptions.firstOrNull(),
                        onMoved = { viewModel.endParking(active.sessionId) },
                        onOpenOptions = onOpenReparkingOptions,
                        plannedMove = state.plannedMove,
                        deadlineWarningsEnabled = state.deadlineWarningsEnabled,
                        onPlan = { state.reparkingOptions.firstOrNull()?.let(viewModel::selectPlan) },
                        onCancelPlan = { viewModel.cancelPlan() },
                        onArrived = { viewModel.arriveAtPlan() },
                    )
                }

                if (state.active == null) {
                    ExtendedFloatingActionButton(
                        text = { Text(if (parkLoading) "Detecting…" else "Park where I am") },
                        icon = {
                            if (parkLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.LocationOn, contentDescription = null)
                            }
                        },
                        onClick = {
                            if (!parkLoading) {
                                if (container.locationProvider.hasPermission()) viewModel.parkWhereIAm()
                                else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }

                if (state.hasSavedStreets) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !state.daytimeOnly,
                            onClick = { viewModel.showDaytimeOnly(false) },
                            label = { Text("All streets") },
                        )
                        FilterChip(
                            selected = state.daytimeOnly,
                            onClick = { viewModel.showDaytimeOnly(!state.daytimeOnly) },
                            label = { Text("Daytime cleaning") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.WbSunny,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                    Text(
                        "Daytime cleaning: within 08:00–18:00",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.streets.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.hasSavedStreets) "No streets with daytime cleaning."
                            else "No streets yet. Tap + to add one.",
                        )
                    }
                }
            } else {
                items(state.streets, key = { it.id }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditStreet(row.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { viewModel.toggleFavourite(row.id) }) {
                                    Icon(
                                        if (row.isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (row.isFavourite) {
                                            "Remove ${row.name} from favourites"
                                        } else "Favourite ${row.name}",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            StreetSubtitle(row)
                            row.daytimeCleaningText?.let { times ->
                                Spacer(Modifier.height(6.dp))
                                DaytimeCleaningBadge(times)
                            }
                        }
                        TextButton(
                            onClick = { viewModel.park(row.id) },
                            enabled = row.isAvailable,
                        ) { Text("Park here") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StreetSubtitle(row: StreetRow) {
    if (!row.isAvailable) {
        Text(
            "Cleaning now · Available ${row.unavailableUntilText}",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val highlight = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
    )
    val text = buildAnnotatedString {
        val freeFor = row.freeFor
        if (freeFor == null) {
            withStyle(highlight) { append("Park anytime") }
        } else {
            append("Free for ")
            withStyle(highlight) { append(freeFor) }
            row.nextCleaningText?.let { append("  ·  $it") }
        }
    }
    Text(text)
}

@Composable
private fun DaytimeCleaningBadge(times: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.WbSunny, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Daytime cleaning · $times", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LaunchedEffectMessage(message: String?, onShow: (String) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let(onShow)
    }
}

@Composable
private fun ActiveParkingCard(
    active: ActiveParking,
    recommendation: ReparkingOption?,
    onMoved: () -> Unit,
    onOpenOptions: () -> Unit,
    plannedMove: PlannedMoveUi?,
    deadlineWarningsEnabled: Boolean,
    onPlan: () -> Unit,
    onCancelPlan: () -> Unit,
    onArrived: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parked on ${active.streetName}", fontWeight = FontWeight.Bold)
            Text(if (active.hasDeadline) "Move before ${active.deadlineText}" else active.deadlineText)
            Text(active.remainingText, style = MaterialTheme.typography.bodyMedium)
            if (!deadlineWarningsEnabled) {
                Text("Parking deadline warnings off", style = MaterialTheme.typography.bodySmall)
            }
            if (plannedMove != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                PlannedMoveDetails(plannedMove, onCancelPlan, onArrived)
                TextButton(onClick = onOpenOptions) { Text("Change plan / view options") }
            } else if (recommendation != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    if (recommendation.justCleaned) "Suggested easy re-park" else "Best alternative · longest stay",
                    fontWeight = FontWeight.Bold,
                )
                ReparkingOptionDetails(recommendation)
                TextButton(onClick = onPlan) { Text(if (recommendation.justCleaned) "Plan this move" else "Plan move now") }
                TextButton(onClick = onOpenOptions) { Text("View re-parking options") }
            } else if (active.hasDeadline) {
                Text("No other saved street is available. Move before your deadline.", modifier = Modifier.padding(top = 8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onMoved) { Text(if (plannedMove == null) "I moved it" else "End current parking") }
            }
        }
    }
}
