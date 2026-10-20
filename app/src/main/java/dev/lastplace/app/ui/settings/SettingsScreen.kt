package dev.lastplace.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.reminders.ReminderReadiness
import dev.lastplace.app.reminders.ReminderSchedulingState
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, initialSection: SettingsSection = SettingsSection.DELIVERY) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by viewModel.settings.collectAsState()
    val reparkingTimes by viewModel.reparkingTimes.collectAsState()
    val readiness by container.reminderCoordinator.readiness.collectAsState(ReminderReadiness())
    val scroll = rememberScrollState()
    val sectionOffsets = remember { mutableStateMapOf<SettingsSection, Int>() }
    val targetOffset = sectionOffsets[initialSection]
    var permissionTrigger by remember { mutableStateOf<ReminderKind?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(initialSection, targetOffset) {
        targetOffset?.let { scroll.animateScrollTo(it) }
    }
    ContextualNotificationPermission(permissionTrigger, onHandled = { permissionTrigger = null })

    fun sectionModifier(section: SettingsSection) = Modifier.onGloballyPositioned {
        sectionOffsets[section] = it.positionInParent().y.roundToInt()
    }

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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (settings == null) {
                Text("Loading settings…", modifier = Modifier.padding(16.dp))
            } else {
                val preferences = requireNotNull(settings)
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).verticalScroll(scroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    ReminderDeliverySettings(
                        container.reminderScheduler,
                        onTestReminder = {
                            container.parkingService.sendTestReminder()
                            scope.launch { snackbar.showSnackbar("Test reminder requested for about 10 seconds from now. Delivery depends on Android settings.") }
                        },
                        modifier = sectionModifier(SettingsSection.DELIVERY),
                    )
                    Column(modifier = sectionModifier(SettingsSection.REPARKING_TIMES), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Preferred re-parking times", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text("Choose cleaning end times that work for you. New times start unselected.", style = MaterialTheme.typography.bodyMedium)
                        val choices = reparkingTimes
                        if (choices == null) {
                            Text("Loading preferred times…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                choices.forEach { choice ->
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
                            if (choices.isEmpty()) {
                                Text("Save a street with cleaning hours to see times here.", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (preferences.preferredReparkingTimes.isEmpty()) {
                                Text("No preferred re-parking times selected. While parked, you'll see longest-stay alternatives instead.", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Column(modifier = sectionModifier(SettingsSection.DEADLINE_WARNINGS)) {
                        ReminderPreferences(
                            kind = ReminderKind.PARKING_DEADLINE,
                            title = "Parking deadline warnings",
                            description = "Warn before cleaning starts on your current street, independently of any move plan.",
                            settings = preferences,
                            viewModel = viewModel,
                            onEnabled = { permissionTrigger = ReminderKind.PARKING_DEADLINE },
                        )
                        if (readiness.status(ReminderKind.PARKING_DEADLINE, Instant.now()).state == ReminderSchedulingState.NO_UPCOMING) {
                            Text("No upcoming deadline warnings. All selected warning times have passed; choose a shorter offset. Adding an already-past offset will not help.")
                        }
                    }
                    Column(modifier = sectionModifier(SettingsSection.PLANNED_MOVES)) {
                        ReminderPreferences(
                            kind = ReminderKind.PLANNED_MOVE,
                            title = "Planned move reminders",
                            description = "Remind you about the move you selected, not cleaning on your current street.",
                            settings = preferences,
                            viewModel = viewModel,
                            onEnabled = { permissionTrigger = ReminderKind.PLANNED_MOVE },
                        )
                        Text("Times already passed are skipped. Zero means at the planned move time. Adding an already-past offset will not help.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
