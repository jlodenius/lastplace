package dev.lastplace.app.ui.addstreet

import android.Manifest
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.navigation.NavController
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.ui.map.MapPickerResult
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStreetScreen(
    streetId: Long,
    navController: NavController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: AddStreetViewModel =
        viewModel(factory = AddStreetViewModel.factory(container, streetId))
    val state by viewModel.state.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    LaunchedEffect(message) {
        val msg = message
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    // Receive the point chosen on the map picker (passed back via the nav back stack).
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val pickedLat by (savedStateHandle?.getStateFlow(MapPickerResult.LAT, Double.NaN)
        ?: kotlinx.coroutines.flow.MutableStateFlow(Double.NaN)).collectAsState()
    LaunchedEffect(pickedLat) {
        val lat = savedStateHandle?.get<Double>(MapPickerResult.LAT)
        val lng = savedStateHandle?.get<Double>(MapPickerResult.LNG)
        if (lat != null && lng != null && !lat.isNaN()) {
            viewModel.onMapPointPicked(LatLng(lat, lng))
            savedStateHandle.remove<Double>(MapPickerResult.LAT)
            savedStateHandle.remove<Double>(MapPickerResult.LNG)
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.useCurrentLocation() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit street" else "Add street") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = { viewModel.delete() }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete street")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Street name (search)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Autocomplete results.
            if (suggestions.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        suggestions.forEach { suggestion ->
                            Text(
                                text = suggestion.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onSuggestionPicked(suggestion) }
                                    .padding(12.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Location + geometry status — explicit so it's obvious whether the *whole
            // street* was captured or just a point (which would barely match when parked).
            val statusText = when {
                state.geometryLoading -> "Loading street shape… (wait before saving)"
                state.isMapped -> {
                    val points = state.geometry.sumOf { it.size }
                    val segments = state.geometry.size
                    "Mapped ✓ — full street captured ($points points across $segments segments)"
                }
                state.hasLocation -> "Location set (point only) — won't reliably match when parked"
                else -> "No location yet (search, pick on map, or use current location)"
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val start = state.lat?.let { lat -> state.lng?.let { lng -> "$lat,$lng" } }
                        navController.navigate(
                            "map_picker" + if (start != null) "?start=$start" else "",
                        )
                    },
                ) { Text("Pick on map") }
                OutlinedButton(
                    onClick = {
                        if (container.locationProvider.hasPermission()) viewModel.useCurrentLocation()
                        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                ) { Text("Use current location") }
            }

            // Force a fresh OSM lookup at the saved point — re-resolves the name and
            // pulls the full street geometry. Stays visible while loading (with a
            // spinner) so taps don't feel ignored.
            if (state.hasLocation) {
                OutlinedButton(
                    onClick = {
                        if (!state.geometryLoading) viewModel.refreshStreetGeometry()
                    },
                    enabled = !state.geometryLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.geometryLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Looking up OSM…")
                    } else {
                        Text(if (state.isMapped) "Refresh street shape" else "Capture full street shape")
                    }
                }
            }

            Text("Cleaning hours", fontWeight = FontWeight.Bold)
            val canRemoveRule = state.rules.size > 1
            state.rules.forEachIndexed { index, rule ->
                RuleEditor(
                    rule = rule,
                    canRemove = canRemoveRule,
                    onChange = { viewModel.updateRule(index, it) },
                    onRemove = { viewModel.removeRule(index) },
                )
            }
            TextButton(onClick = viewModel::addRule) { Text("+ Add another cleaning time") }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isEditing) "Save changes" else "Add street") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(
    rule: RuleInput,
    canRemove: Boolean,
    onChange: (RuleInput) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = rule.dayOfWeek == day,
                        onClick = { onChange(rule.copy(dayOfWeek = day)) },
                        label = { Text(dayLabel(day)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        showTimePicker(context, rule.startMinute) { onChange(rule.copy(startMinute = it)) }
                    },
                ) { Text("From ${formatMinutes(rule.startMinute)}") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        showTimePicker(context, rule.endMinute) { onChange(rule.copy(endMinute = it)) }
                    },
                ) { Text("to ${formatMinutes(rule.endMinute)}") }
                if (canRemove) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove cleaning time")
                    }
                }
            }
        }
    }
}

private fun dayLabel(dayOfWeek: Int): String =
    DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.SHORT, Locale.getDefault())

private fun formatMinutes(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private fun showTimePicker(
    context: android.content.Context,
    initialMinute: Int,
    onPicked: (Int) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        initialMinute / 60,
        initialMinute % 60,
        true,
    ).show()
}
