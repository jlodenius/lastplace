package dev.lastplace.app.ui.streets

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReparkingOption
import dev.lastplace.app.reminders.Notifications
import dev.lastplace.app.reminders.ReminderCapabilities
import dev.lastplace.app.ui.settings.ContextualNotificationPermission
import dev.lastplace.app.ui.settings.SettingsSection
import dev.lastplace.app.ui.settings.openNotificationSettings
import dev.lastplace.app.ui.settings.openPreciseReminderSettings
import dev.lastplace.app.ui.settings.rememberReminderCapabilities
import dev.lastplace.app.ui.theme.LocalParkingStatusColors
import dev.lastplace.app.ui.theme.ParkingLayout

@Composable
fun StreetListScreen(
    onAddStreet: () -> Unit,
    onEditStreet: (Long) -> Unit,
    onOpenSettings: (SettingsSection) -> Unit,
    onOpenReparkingOptions: () -> Unit,
    onOpenReminderSettings: () -> Unit = { onOpenSettings(SettingsSection.DEADLINE_WARNINGS) },
    onOpenPreferredTimes: () -> Unit = { onOpenSettings(SettingsSection.REPARKING_TIMES) },
    editorResult: String? = null,
    onEditorResultHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: StreetListViewModel = viewModel(factory = StreetListViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val parkLoading by viewModel.parkLoading.collectAsState()
    val mutationRunning by viewModel.mutationRunning.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val notificationPrompt by viewModel.notificationPrompt.collectAsState()
    val capabilities = rememberReminderCapabilities(container.reminderScheduler)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbarHost = remember { SnackbarHostState() }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    ContextualNotificationPermission(notificationPrompt.takeIf { it }, viewModel::clearNotificationPrompt)

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(editorResult) {
        editorResult?.let {
            snackbarHost.showSnackbar(it)
            onEditorResultHandled()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.parkWhereIAm()
        else viewModel.locationPermissionDenied()
    }

    StreetListContent(
        state = state,
        parkLoading = parkLoading,
        gpsError = actionError,
        mutationInProgress = mutationRunning,
        snackbarHost = snackbarHost,
        onAddStreet = onAddStreet,
        onEditStreet = onEditStreet,
        onOpenSettings = { onOpenSettings(SettingsSection.DELIVERY) },
        onOpenReminderSettings = onOpenReminderSettings,
        onOpenPreferredTimes = onOpenPreferredTimes,
        onOpenReparkingOptions = onOpenReparkingOptions,
        onParkWhereIAm = {
            if (container.locationProvider.hasPermission()) viewModel.parkWhereIAm()
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onPark = { streetId, sessionId ->
            if (sessionId == null) viewModel.park(streetId) else viewModel.confirmPark(streetId, sessionId)
        },
        onEndParking = { viewModel.endParking(it) },
        onSelectPlan = { viewModel.selectPlan(it) },
        onCancelPlan = { viewModel.cancelPlan(it.plan.sessionId, it.plan.id) },
        onArrived = { viewModel.arriveAtPlan(it.plan.sessionId, it.plan.id) },
        onToggleFavourite = { viewModel.toggleFavourite(it) },
        onShowDaytimeOnly = viewModel::showDaytimeOnly,
        deadlineReminderContent = {
            if (state.active?.hasDeadline == true || state.deadlineReminderText != "No cleaning deadline in saved hours") {
                Text(state.deadlineReminderText)
            }
            ReminderDeliveryBlockers(capabilities, ReminderKind.PARKING_DEADLINE)
        },
        planReminderDetails = {
            ReminderDeliveryBlockers(capabilities, ReminderKind.PLANNED_MOVE)
            TextButton(onClick = { onOpenSettings(SettingsSection.PLANNED_MOVES) }) { Text("Move reminder settings") }
        },
    )
}

private sealed interface ParkingConfirmation {
    data class Change(val sessionId: Long, val fromName: String, val streetId: Long, val streetName: String) : ParkingConfirmation
    data class End(val sessionId: Long, val streetName: String) : ParkingConfirmation
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StreetListContent(
    state: HomeUiState,
    parkLoading: Boolean = false,
    gpsError: String? = null,
    mutationInProgress: Boolean = false,
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
    onAddStreet: () -> Unit = {},
    onEditStreet: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenReminderSettings: () -> Unit = onOpenSettings,
    onOpenPreferredTimes: () -> Unit = onOpenSettings,
    onOpenReparkingOptions: () -> Unit = {},
    onParkWhereIAm: () -> Unit = {},
    onPark: (Long, Long?) -> Unit = { _, _ -> },
    onEndParking: (Long) -> Unit = {},
    onSelectPlan: (ReparkingOption) -> Unit = {},
    onCancelPlan: (PlannedMoveUi) -> Unit = {},
    onArrived: (PlannedMoveUi) -> Unit = {},
    onToggleFavourite: (Long) -> Unit = {},
    onShowDaytimeOnly: (Boolean) -> Unit = {},
    deadlineReminderContent: @Composable () -> Unit = { Text("Updating reminder status…") },
    planReminderDetails: (@Composable () -> Unit)? = null,
) {
    var confirmation by remember { mutableStateOf<ParkingConfirmation?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parking") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (!state.isLoading && state.hasSavedStreets) {
                FloatingActionButton(onClick = onAddStreet) {
                    Icon(Icons.Filled.Add, contentDescription = "Add street")
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ParkingLayout.contentWidth).fillMaxWidth().testTag("parking-content"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    state.isLoading -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Text("Loading parking…")
                        }
                    }
                    !state.hasSavedStreets -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("No saved streets", style = MaterialTheme.typography.headlineSmall)
                            Text("Save the streets you use and enter their cleaning hours. Register parking to track when to move.")
                            Text("Enter cleaning hours from street signs, not the map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onAddStreet) { Text("Add your first street") }
                        }
                    }
                    else -> {
                        item {
                            val active = state.active
                            if (active != null) {
                                ActiveParkingCard(
                                    active = active,
                                    isBusy = mutationInProgress,
                                    onEndParking = { confirmation = ParkingConfirmation.End(active.sessionId, active.streetName) },
                                    onOpenReminderSettings = onOpenReminderSettings,
                                    reminderContent = deadlineReminderContent,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Not currently parked", style = MaterialTheme.typography.titleMedium)
                                    Button(onClick = onParkWhereIAm, enabled = !parkLoading && !mutationInProgress) {
                                        if (parkLoading) {
                                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).size(24.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                        }
                                        Text(if (parkLoading) "Finding your location…" else "Park where I am")
                                    }
                                    Text("Or choose a saved street below.")
                                }
                            }
                            gpsError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        state.plannedMove?.let { plan ->
                            item {
                                PlannedMoveCard(
                                    plan = plan,
                                    onCancel = { onCancelPlan(plan) },
                                    onArrived = { onArrived(plan) },
                                    onChangePlan = onOpenReparkingOptions,
                                    isBusy = mutationInProgress,
                                    reminderDetails = planReminderDetails,
                                )
                            }
                        } ?: state.active?.let { active ->
                            if (active.hasDeadline) {
                                item {
                                    Column(modifier = Modifier.padding(top = ParkingLayout.spacing), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Suggested move", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                        if (!state.hasPreferredTimes) {
                                            Text("No preferred re-parking times selected")
                                            TextButton(onClick = onOpenPreferredTimes) { Text("Choose times") }
                                        }
                                        val option = state.reparkingOptions.firstOrNull()
                                        if (state.hasPreferredTimes && option?.justCleaned != true) {
                                            Text("No suitable opportunity at your preferred times before this deadline.")
                                        }
                                        if (option != null) {
                                            Text(option.street.name, style = MaterialTheme.typography.titleMedium)
                                            Text(if (option.justCleaned) option.moveAt.format(parkingTimeFormatter) else "Move now · Longest stay")
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = { onSelectPlan(option) }, enabled = !mutationInProgress) { Text("Plan this move") }
                                                TextButton(onClick = onOpenReparkingOptions) { Text("View options") }
                                            }
                                        } else {
                                            Text("No other saved street is available. Check another location before your deadline.")
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Saved streets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                Text("Longest stay first · Based on saved cleaning hours", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !state.daytimeOnly, onClick = { onShowDaytimeOnly(false) }, label = { Text("All streets") })
                                    FilterChip(
                                        selected = state.daytimeOnly,
                                        onClick = { onShowDaytimeOnly(!state.daytimeOnly) },
                                        label = { Text("Daytime cleaning") },
                                        leadingIcon = { Icon(Icons.Filled.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    )
                                }
                                Text("Daytime cleaning: within 08:00–18:00. Other cleaning windows still apply.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.streets.isEmpty()) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("No saved streets have daytime cleaning.")
                                    TextButton(onClick = { onShowDaytimeOnly(false) }) { Text("Show all streets") }
                                }
                            }
                        } else {
                            items(state.streets, key = { it.id }) { row ->
                                SavedStreetRow(
                                    row = row,
                                    isCurrent = state.active?.streetId == row.id,
                                    isBusy = mutationInProgress,
                                    onEdit = { onEditStreet(row.id) },
                                    onToggleFavourite = { onToggleFavourite(row.id) },
                                    onPark = {
                                        val active = state.active
                                        if (active == null) onPark(row.id, null)
                                        else confirmation = ParkingConfirmation.Change(active.sessionId, active.streetName, row.id, row.name)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { pending ->
        val isChange = pending is ParkingConfirmation.Change
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(when (pending) {
                    is ParkingConfirmation.Change -> "Change parked location from ${pending.fromName} to ${pending.streetName}?"
                    is ParkingConfirmation.End -> "End parking on ${pending.streetName}?"
                })
            },
            text = {
                Text(if (isChange) "Your current session ends, its plan clears, and deadline warnings are recalculated."
                else "This clears the plan and both reminder groups for this parking session.")
            },
            confirmButton = {
                TextButton(
                    enabled = !mutationInProgress,
                    onClick = {
                        confirmation = null
                        when (pending) {
                            is ParkingConfirmation.Change -> onPark(pending.streetId, pending.sessionId)
                            is ParkingConfirmation.End -> onEndParking(pending.sessionId)
                        }
                    },
                ) {
                    Text(if (pending is ParkingConfirmation.Change) "I've parked on ${pending.streetName}" else "End parking")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedStreetRow(
    row: StreetRow,
    isCurrent: Boolean,
    isBusy: Boolean,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onPark: () -> Unit,
) {
    Surface(
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(
                    onClick = onToggleFavourite,
                    enabled = !isBusy,
                    modifier = Modifier.semantics {
                        contentDescription = if (row.isFavourite) "Remove ${row.name} from favourites" else "Favourite ${row.name}"
                        stateDescription = if (row.isFavourite) "Favourite" else "Not favourite"
                    },
                ) {
                    Icon(
                        if (row.isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!row.isAvailable) {
                Text("Cleaning now · Ends ${row.unavailableUntilText}", color = MaterialTheme.colorScheme.error)
                Text("Registration is unavailable during saved cleaning hours.")
            } else {
                Text(row.freeFor?.let { "Can stay $it" } ?: "No cleaning deadline in saved hours")
                row.nextCleaningText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            row.daytimeCleaningText?.let {
                Text("Daytime cleaning · $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit, enabled = !isBusy, modifier = Modifier.semantics { contentDescription = "Edit ${row.name}" }) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = onPark,
                    enabled = row.isAvailable && !isCurrent && !isBusy,
                    modifier = Modifier.semantics { contentDescription = if (isCurrent) "Parked on ${row.name}" else "I've parked on ${row.name}" },
                ) { Text(if (isCurrent) "Parked here" else "I've parked here") }
            }
        }
    }
}

@Composable
internal fun ActiveParkingCard(
    active: ActiveParking,
    isBusy: Boolean,
    onEndParking: () -> Unit,
    onOpenReminderSettings: () -> Unit,
    reminderContent: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val warning = LocalParkingStatusColors.current
    val container = when (active.urgency) {
        ParkingUrgency.Soon -> warning.warningContainer
        ParkingUrgency.Overdue -> colors.errorContainer
        else -> colors.surface
    }
    val foreground = when (active.urgency) {
        ParkingUrgency.Soon -> warning.onWarningContainer
        ParkingUrgency.Overdue -> colors.onErrorContainer
        else -> colors.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = foreground),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrentParkingHeader(active.streetName, isBusy, onEndParking)
            when (active.urgency) {
                ParkingUrgency.Soon, ParkingUrgency.Overdue -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (active.urgency == ParkingUrgency.Soon) Icons.Filled.WarningAmber else Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text(if (active.urgency == ParkingUrgency.Soon) "Move soon" else "Move now — cleaning deadline passed", style = MaterialTheme.typography.titleMedium)
                }
                else -> Unit
            }
            if (active.hasDeadline) {
                Text(
                    active.remainingText,
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (active.urgency == ParkingUrgency.Normal) colors.primary else foreground,
                )
                Text("Move before ${active.deadlineText}", style = MaterialTheme.typography.headlineSmall)
            } else {
                Text("No cleaning deadline in saved hours", style = MaterialTheme.typography.headlineSmall)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            reminderContent()
            TextButton(onClick = onOpenReminderSettings) { Text("Reminder settings") }
        }
    }
}

@Composable
internal fun ReminderDeliveryBlockers(capabilities: ReminderCapabilities, kind: ReminderKind) {
    val context = LocalContext.current
    val deadline = kind == ReminderKind.PARKING_DEADLINE
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!capabilities.appNotificationsAllowed) {
            Text("Notifications blocked for LastPlace.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { openNotificationSettings(context) }) { Text("Allow notifications") }
        }
        if (!capabilities.channelAllowed(kind)) {
            Text(if (deadline) "Deadline notifications blocked." else "Planned move notifications blocked.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                openNotificationSettings(context, if (deadline) Notifications.CHANNEL_REMINDERS else Notifications.CHANNEL_MOVES)
            }) { Text(if (deadline) "Allow deadline notifications" else "Allow move notifications") }
        }
        if (!capabilities.preciseAlarmsAllowed) {
            Text("Precise reminders unavailable. Android may delay reminders.")
            TextButton(onClick = { openPreciseReminderSettings(context) }) { Text("Allow precise reminders") }
        }
    }
}

@Composable
private fun CurrentParkingHeader(streetName: String, isBusy: Boolean, onEndParking: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints {
        if (maxWidth.value / fontScale < 360f) {
            Column {
                Text("Parked on $streetName", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onEndParking, enabled = !isBusy) { Text("End parking") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Parked on $streetName", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEndParking, enabled = !isBusy) { Text("End parking") }
            }
        }
    }
}
