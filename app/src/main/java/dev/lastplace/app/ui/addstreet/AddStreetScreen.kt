package dev.lastplace.app.ui.addstreet

import android.Manifest
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.osm.StreetSuggestion
import dev.lastplace.app.ui.map.MapPickerResult
import dev.lastplace.app.ui.theme.ParkingLayout
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun AddStreetScreen(
    streetId: Long,
    navController: NavController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val model: AddStreetViewModel = viewModel(factory = AddStreetViewModel.factory(container, streetId))
    val state by model.state.collectAsState()
    val message by model.message.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            navController.previousBackStackEntry?.savedStateHandle
                ?.set("street_editor_result", model.message.value ?: "Street updated.")
            onBack()
        }
    }

    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val pickedLat by (savedStateHandle?.getStateFlow(MapPickerResult.LAT, Double.NaN)
        ?: remember { MutableStateFlow(Double.NaN) }).collectAsState()
    LaunchedEffect(pickedLat) {
        val lat = savedStateHandle?.get<Double>(MapPickerResult.LAT)
        val lng = savedStateHandle?.get<Double>(MapPickerResult.LNG)
        if (lat != null && lng != null && !lat.isNaN()) {
            model.onMapPointPicked(LatLng(lat, lng))
            savedStateHandle.remove<Double>(MapPickerResult.LAT)
            savedStateHandle.remove<Double>(MapPickerResult.LNG)
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) model.useCurrentLocation()
        else model.locationPermissionDenied()
    }

    AddStreetEditor(
        state = state,
        onBack = onBack,
        onNameChange = model::onQueryChange,
        onSuggestion = model::onSuggestionPicked,
        onRetrySearch = model::retrySearch,
        onUseLocation = {
            if (container.locationProvider.hasPermission()) model.useCurrentLocation()
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onPickMap = {
            val start = state.lat?.let { lat -> state.lng?.let { lng -> "$lat,$lng" } }
            navController.navigate("map_picker" + if (start != null) "?start=$start" else "")
        },
        onRefreshGeometry = model::refreshStreetGeometry,
        onAddRule = model::addRule,
        onRuleChange = model::updateRule,
        onRemoveRule = model::removeRule,
        onAcknowledge = model::acknowledgeCleaningHours,
        onSave = model::save,
        onRequestDelete = model::requestDelete,
        onCancelDelete = model::cancelDelete,
        onConfirmDelete = model::confirmDelete,
        onRetryLoad = model::loadExisting,
        message = message,
        onMessageShown = model::clearMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddStreetEditor(
    state: AddStreetUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit = {},
    onSuggestion: (StreetSuggestion) -> Unit = {},
    onRetrySearch: () -> Unit = {},
    onUseLocation: () -> Unit = {},
    onPickMap: () -> Unit = {},
    onRefreshGeometry: () -> Unit = {},
    onAddRule: () -> Unit = {},
    onRuleChange: (Int, RuleInput) -> Unit = { _, _ -> },
    onRemoveRule: (Int) -> Unit = {},
    onAcknowledge: (Boolean) -> Unit = {},
    onSave: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val requestBack = {
        if (!state.mutationInProgress && !state.saved) {
            if (state.hasUnsavedChanges) discardDialog = true else onBack()
        }
    }
    BackHandler(onBack = requestBack)
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your changes to this street and its cleaning hours have not been saved.", modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { discardDialog = false; onBack() }) { Text("Discard changes") } },
            dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("Keep editing") } },
        )
    }
    state.deletionContext?.let { deletion ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Delete ${deletion.street.street.name}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This removes this saved entry and its cleaning hours.")
                    if (deletion.isParkedStreet) {
                        Text("Parking on this street will end. Its plan and both reminder groups will be cleared.")
                    } else if (deletion.isPlannedDestination) {
                        Text("Your selected move destination will be unavailable and its move reminders will be cancelled. Current parking and deadline warnings will remain.")
                    }
                    if (state.mutationInProgress) Progress("Deleting street…")
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.mutationInProgress) { Text("Delete street") }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.mutationInProgress) { Text("Keep street") }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit street" else "Add street") },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !state.mutationInProgress && !state.saved) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = onRequestDelete, enabled = !state.isBusy && !state.loadFailed) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete street")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = ParkingLayout.contentWidth).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(ParkingLayout.gutter),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.isLoading) {
                    Progress("Loading saved street…")
                } else if (state.loadFailed) {
                    ErrorMessage(state.error ?: "Couldn't load street.")
                    OutlinedButton(onClick = onRetryLoad) { Text("Retry loading") }
                } else {
                    SectionHeading("Street")
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = onNameChange,
                        label = { Text("Street name / search") },
                        supportingText = { Text("Keep a distinct name for a street section, such as Ynglingagatan vändplan.") },
                        enabled = !state.isBusy,
                        isError = state.name.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.name.isBlank()) Text("Enter a street name before saving.")
                    SearchFeedback(state.search, !state.isBusy, onSuggestion, onRetrySearch)

                    val statusText = when {
                        state.isMapped -> {
                            val points = state.geometry.sumOf { it.size }
                            "Mapped ✓ — full street captured ($points points across ${state.geometry.size} segments)"
                        }
                        state.hasLocation -> "Location set (point only) — won't reliably match when parked"
                        else -> "No location yet (search, pick on map, or use current location)"
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickMap, enabled = !state.isBusy) { Text("Pick on map") }
                        OutlinedButton(onClick = onUseLocation, enabled = !state.isBusy) { Text("Use current location") }
                    }
                    if (state.isLocating) Progress("Getting your location…")
                    state.locationError?.let {
                        ErrorMessage(it)
                        Text("Manual alternative: enter a street name above. Location permission is not needed to save it.")
                    }
                    if (state.geometryLoading) Progress("Loading street shape… Wait before saving.")
                    state.mapError?.let { ErrorMessage(it) }
                    if (state.hasLocation) {
                        OutlinedButton(onClick = onRefreshGeometry, enabled = !state.isBusy) {
                            Text(if (state.isMapped) "Refresh street shape" else "Capture full street shape")
                        }
                    }
                    if (state.isEditing) {
                        Text("Changing the name or hours keeps the saved shape. Map and capture actions can replace the name and shape.", style = MaterialTheme.typography.bodyMedium)
                    }
                    SectionHeading("Cleaning hours")
                    Text("Enter the weekly cleaning restriction from the street sign. The map does not provide cleaning hours.")
                    state.rules.forEachIndexed { index, rule ->
                        RuleEditor(
                            number = index + 1,
                            rule = rule,
                            canRemove = state.rules.size > 1,
                            enabled = !state.isBusy,
                            onChange = { onRuleChange(index, it) },
                            onRemove = { onRemoveRule(index) },
                        )
                    }
                    if (state.rules.isEmpty()) Text("No cleaning restrictions are saved for this street.")
                    TextButton(onClick = onAddRule, enabled = !state.isBusy) { Text("Add another cleaning time") }
                    if (state.needsAcknowledgment) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                                value = state.cleaningHoursAcknowledged,
                                enabled = !state.isBusy,
                                role = Role.Checkbox,
                                onValueChange = onAcknowledge,
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = state.cleaningHoursAcknowledged, onCheckedChange = null, enabled = !state.isBusy)
                            Text("I've checked these cleaning hours", modifier = Modifier.weight(1f).padding(start = 8.dp))
                        }
                        if (!state.cleaningHoursAcknowledged) Text("Check the entered hours against the street sign, then acknowledge them to save.")
                    }
                    state.error?.let { ErrorMessage(it) }
                    if (state.mutationInProgress) Progress("Updating street…")
                    Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isEditing) "Save changes" else "Add street")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ErrorMessage(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Progress(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

@Composable
private fun SearchFeedback(
    search: StreetSearchState,
    enabled: Boolean,
    onSuggestion: (StreetSuggestion) -> Unit,
    onRetry: () -> Unit,
) {
    when (search) {
        StreetSearchState.Idle, StreetSearchState.ShortQuery -> Text("Enter at least 3 characters to search, or keep a manual name.")
        is StreetSearchState.Searching -> Progress("Searching streets…")
        is StreetSearchState.NoMatches -> Text("No streets found for “${search.query}”. Try another search or keep the name and pick on the map.")
        is StreetSearchState.Failed -> {
            ErrorMessage("Street search failed: ${search.reason}")
            OutlinedButton(onClick = onRetry, enabled = enabled) { Text("Retry search") }
            Text("Your form is unchanged. You can also enter a name manually or pick on the map.")
        }
        is StreetSearchState.Results -> Card(Modifier.fillMaxWidth()) {
            Column {
                search.streets.forEachIndexed { index, suggestion ->
                    TextButton(onClick = { onSuggestion(suggestion) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(suggestion.label, modifier = Modifier.fillMaxWidth().padding(4.dp))
                    }
                    if (index < search.streets.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(
    number: Int,
    rule: RuleInput,
    canRemove: Boolean,
    enabled: Boolean,
    onChange: (RuleInput) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Cleaning time $number", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (canRemove) IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove cleaning time $number")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = rule.dayOfWeek == day,
                        onClick = { onChange(rule.copy(dayOfWeek = day)) },
                        enabled = enabled,
                        label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showTimePicker(context, rule.startMinute ?: 8 * 60) { onChange(rule.copy(startMinute = it)) } },
                    enabled = enabled,
                ) { Text(rule.startMinute?.let { "From ${formatMinutes(it)}" } ?: "Choose start time") }
                OutlinedButton(
                    onClick = { showTimePicker(context, rule.endMinute ?: 14 * 60) { onChange(rule.copy(endMinute = it)) } },
                    enabled = enabled,
                ) { Text(rule.endMinute?.let { "To ${formatMinutes(it)}" } ?: "Choose end time") }
            }
            Text(rule.summary() ?: requireNotNull(rule.missingFields), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun showTimePicker(context: android.content.Context, initialMinute: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        initialMinute / 60,
        initialMinute % 60,
        true,
    ).show()
}
