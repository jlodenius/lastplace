package dev.lastplace.app.ui.streets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.ReparkingOption
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.ui.settings.ContextualNotificationPermission
import dev.lastplace.app.ui.settings.rememberReminderCapabilities
import dev.lastplace.app.ui.theme.ParkingLayout

@Composable
fun ReparkingOptionsScreen(
    onBack: () -> Unit,
    onOpenPreferredTimes: () -> Unit = {},
    onOpenMoveReminderSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: StreetListViewModel = viewModel(factory = StreetListViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
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
    ReparkingOptionsContent(
        state = state,
        mutationInProgress = mutationRunning,
        actionError = actionError,
        onBack = onBack,
        onOpenPreferredTimes = onOpenPreferredTimes,
        onSelectPlan = { viewModel.selectPlan(it) },
        onCancelPlan = { viewModel.cancelPlan(it.plan.sessionId, it.plan.id) },
        onArrived = { viewModel.arriveAtPlan(it.plan.sessionId, it.plan.id) },
        snackbarHost = snackbarHost,
        planReminderDetails = {
            ReminderDeliveryBlockers(capabilities, ReminderKind.PLANNED_MOVE)
            TextButton(onClick = onOpenMoveReminderSettings) { Text("Move reminder settings") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReparkingOptionsContent(
    state: HomeUiState,
    mutationInProgress: Boolean = false,
    actionError: String? = null,
    onBack: () -> Unit = {},
    onOpenPreferredTimes: () -> Unit = {},
    onSelectPlan: (ReparkingOption) -> Unit = {},
    onCancelPlan: (PlannedMoveUi) -> Unit = {},
    onArrived: (PlannedMoveUi) -> Unit = {},
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
    planReminderDetails: (@Composable () -> Unit)? = null,
) {
    val cards = buildReparkingOptionCards(state.reparkingOptions, state.plannedMove)
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
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ParkingLayout.contentWidth).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    val active = state.active
                    when {
                        state.isLoading -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Text("Loading parking…")
                        }
                        active == null -> Text("Register parking on a saved street to see re-parking options.")
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Parked on ${active.streetName}", style = MaterialTheme.typography.titleMedium)
                            if (active.hasDeadline) {
                                Text(active.remainingText, style = MaterialTheme.typography.headlineSmall)
                                Text("Move before ${active.deadlineText}")
                            } else {
                                Text("No cleaning deadline in saved hours")
                            }
                            Text("Based on saved cleaning hours, not live parking spaces. Planning never registers parking.")
                            when {
                                !state.hasPreferredTimes -> {
                                    Text("No preferred re-parking times selected")
                                    TextButton(onClick = onOpenPreferredTimes) { Text("Choose times") }
                                }
                                state.reparkingOptions.firstOrNull()?.justCleaned == true -> {
                                    Text("Up to three just-cleaned destinations at your preferred times, latest opportunity first. They may not be available now.")
                                }
                                active.hasDeadline -> {
                                    Text("No suitable opportunity at your preferred times is available before this deadline. Alternatives available now are ordered by longest stay.")
                                }
                            }
                            if (state.plannedMove != null) {
                                Text("Your selected plan stays fixed. Choosing another option replaces it, not your current parking.")
                            }
                            if (state.reparkingOptions.isEmpty() && active.hasDeadline) {
                                Text(if (state.plannedMove != null) "No alternative saved streets are available right now."
                                else "No other saved street is available. Check another location before your deadline.")
                            }
                        }
                    }
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
                if (!state.isLoading && state.active != null) {
                    items(cards, key = { it.streetId }) { card ->
                        val selected = card.plannedMove != null
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                card.plannedMove?.let { plan ->
                                    PlannedMoveDetails(
                                        plan = plan,
                                        onCancel = { onCancelPlan(plan) },
                                        onArrived = { onArrived(plan) },
                                        option = card.option.takeIf { card.isSelectedOption },
                                        isBusy = mutationInProgress,
                                        reminderDetails = planReminderDetails,
                                    )
                                }
                                card.option?.takeUnless { card.isSelectedOption }?.let { option ->
                                    if (selected) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                    Text(
                                        when {
                                            selected -> "New opportunity on this street"
                                            card.recommended -> "Recommended"
                                            else -> "Alternative"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                    ReparkingOptionDetails(option)
                                    OutlinedButton(
                                        onClick = { onSelectPlan(option) },
                                        enabled = !mutationInProgress,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Plan this move") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReparkingOptionDetails(option: ReparkingOption) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(option.street.name, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
        if (option.street.isFavourite) {
            Icon(Icons.Filled.Star, contentDescription = "Favourite destination", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(
        if (option.justCleaned) "Move at ${option.moveAt.format(parkingTimeFormatter)} · Just cleaned then"
        else "Move now · ${option.moveAt.format(parkingTimeFormatter)}",
    )
    ReparkingStayDetails(option)
}

@Composable
internal fun ReparkingStayDetails(option: ReparkingOption) {
    option.nextCleaningStart?.let {
        Text("Can stay ${freeForText(option.moveAt, it)}")
        Text("Next cleaning: ${it.format(parkingTimeFormatter)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } ?: Text("No cleaning deadline in saved hours")
}
