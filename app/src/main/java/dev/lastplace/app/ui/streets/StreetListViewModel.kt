package dev.lastplace.app.ui.streets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lastplace.app.AppContainer
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.GeometryCodec
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReparkingOption
import dev.lastplace.app.domain.ReparkingPlanner
import dev.lastplace.app.domain.ReparkingStreet
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.parking.ParkingService
import dev.lastplace.app.reminders.ReminderCoordinator
import dev.lastplace.app.reminders.ReminderReadiness
import dev.lastplace.app.reminders.ReminderSchedulingState
import dev.lastplace.app.reminders.ReminderStatus
import dev.lastplace.app.reminders.currentDeadline
import dev.lastplace.app.reminders.planProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val MIN_MATCH_RADIUS_METERS = 60

data class ActiveParking(
    val sessionId: Long,
    val streetName: String,
    val deadlineText: String,
    val remainingText: String,
    val hasDeadline: Boolean,
    val streetId: Long = -1,
    val urgency: ParkingUrgency = ParkingUrgency.Normal,
)

data class PlannedMoveUi(
    val plan: PlannedMove,
    val streetName: String,
    val timeText: String,
    val problem: String?,
    val timePassed: Boolean,
    val isAvailableNow: Boolean,
    val remindersText: String?,
    val isFavourite: Boolean = false,
)

data class HomeUiState(
    val streets: List<StreetRow> = emptyList(),
    val active: ActiveParking? = null,
    val daytimeOnly: Boolean = false,
    val hasSavedStreets: Boolean = false,
    val reparkingOptions: List<ReparkingOption> = emptyList(),
    val plannedMove: PlannedMoveUi? = null,
    val deadlineWarningsEnabled: Boolean = true,
    val deadlineReminderText: String = "Updating reminder status…",
    val hasPreferredTimes: Boolean = false,
    val isLoading: Boolean = true,
)

class StreetListViewModel(
    private val streetRepository: StreetRepository,
    private val parkingRepository: ParkingRepository,
    private val parkingService: ParkingService,
    private val scheduleEngine: ScheduleEngine,
    private val streetMatcher: StreetMatcher,
    private val locationProvider: LocationProvider,
    private val settingsRepository: SettingsRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError
    private val _parkLoading = MutableStateFlow(false)
    val parkLoading: StateFlow<Boolean> = _parkLoading
    private val _mutationRunning = MutableStateFlow(false)
    val mutationRunning: StateFlow<Boolean> = _mutationRunning
    private val _notificationPrompt = MutableStateFlow(false)
    val notificationPrompt: StateFlow<Boolean> = _notificationPrompt
    private val refreshVersion = MutableStateFlow(0L)
    private val daytimeOnly = MutableStateFlow(false)
    private val reparkingPlanner = ReparkingPlanner(scheduleEngine)
    private val ticks = flow {
        while (true) {
            val now = ZonedDateTime.now(clock)
            emit(now)
            val withinMinute = now.toInstant().toEpochMilli().mod(60_000L)
            delay(if (withinMinute < 1_000L) 1_000L - withinMinute else 60_000L - withinMinute)
        }
    }
    private val currentTime = combine(ticks, refreshVersion) { _, _ -> ZonedDateTime.now(clock) }

    fun showDaytimeOnly(enabled: Boolean) { daytimeOnly.value = enabled }

    val uiState: StateFlow<HomeUiState> = combine(
        streetRepository.observeStreetsWithRules(),
        currentTime,
        daytimeOnly,
        combine(
            parkingRepository.observeActiveSession(), reminderCoordinator.plans,
            reminderCoordinator.readiness.onStart { emit(ReminderReadiness()) },
        ) { session, plans, readiness -> Triple(session, plans, readiness) },
        settingsRepository.settings,
    ) { streets, now, daytimeOnly, parkingState, settings ->
        val (active, plans, snapshot) = parkingState
        val deadlineInstant = active?.let { currentDeadline(it, streets, scheduleEngine, now.zone) }
        val plan = active?.let { session -> plans.firstOrNull { it.sessionId == session.id } }
        val readiness = snapshot.takeIf {
            it.sessionId == active?.id && it.settings == settings &&
                it.deadline == deadlineInstant?.toEpochMilli() && it.planId == plan?.id
        } ?: ReminderReadiness()
        HomeUiState(
            isLoading = false,
            streets = buildStreetRows(streets, now, scheduleEngine, daytimeOnly),
            active = active?.let { session ->
                val deadline = deadlineInstant?.atZone(now.zone)
                ActiveParking(
                    sessionId = session.id,
                    streetId = session.streetId,
                    streetName = streets.firstOrNull { it.street.id == session.streetId }?.street?.name ?: "Street removed",
                    deadlineText = deadline?.format(parkingTimeFormatter) ?: "No cleaning deadline in saved hours",
                    remainingText = deadline?.let { parkingCountdownText(now, it) }.orEmpty(),
                    hasDeadline = deadline != null,
                    urgency = parkingUrgency(now, deadline),
                )
            },
            daytimeOnly = daytimeOnly,
            hasSavedStreets = streets.isNotEmpty(),
            hasPreferredTimes = settings.preferredReparkingTimes.isNotEmpty(),
            deadlineWarningsEnabled = settings.deadlineWarningsEnabled,
            deadlineReminderText = reminderStatusText(readiness.status(ReminderKind.PARKING_DEADLINE, now.toInstant()), ReminderKind.PARKING_DEADLINE, now.zone),
            plannedMove = plan?.let {
                val target = streets.firstOrNull { street -> street.street.id == it.streetId }
                val problem = planProblem(it, requireNotNull(active), streets, scheduleEngine, now.zone)
                PlannedMoveUi(
                    plan = it,
                    streetName = target?.street?.name ?: it.streetName,
                    timeText = Instant.ofEpochMilli(it.moveAt).atZone(now.zone).format(parkingTimeFormatter),
                    problem = problem,
                    timePassed = now.toInstant().toEpochMilli() >= it.moveAt + 60_000,
                    isAvailableNow = target?.let { street -> scheduleEngine.availability(street.rules.map { rule -> rule.toWindow() }, now).isAvailable } == true,
                    remindersText = if (problem != null) "Plan reminders cancelled" else reminderStatusText(readiness.status(ReminderKind.PLANNED_MOVE, now.toInstant()), ReminderKind.PLANNED_MOVE, now.zone),
                    isFavourite = target?.street?.isFavourite == true,
                )
            },
            reparkingOptions = active?.let { session ->
                reparkingPlanner.options(
                    streets = streets.map {
                        ReparkingStreet(it.street.id, it.street.name, it.rules.map { rule -> rule.toWindow() }, it.street.isFavourite)
                    },
                    currentStreetId = session.streetId,
                    deadline = deadlineInstant?.atZone(now.zone),
                    preferredTimes = settings.preferredReparkingTimes,
                    now = now,
                )
            }.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun mutate(action: suspend () -> Unit) = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (!_mutationRunning.compareAndSet(false, true)) return@launch
        _actionError.value = null
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _actionError.value = "Couldn't complete that action. Please try again."
        } finally {
            _mutationRunning.value = false
        }
    }

    fun park(streetId: Long) = register(streetId, null)

    fun confirmPark(streetId: Long, expectedSessionId: Long) = register(streetId, expectedSessionId)

    private fun register(streetId: Long, expectedSessionId: Long?) = mutate {
        registered(parkingService.parkIfCurrent(streetId, expectedSessionId, clock.instant()), streetId)
    }

    private suspend fun registered(result: Long, streetId: Long) {
        if (result == -1L) {
            _actionError.value = "Parking changed or this street is unavailable now. Check the saved streets and choose again."
        } else {
            val name = streetRepository.getStreetWithRules(streetId)?.street?.name ?: "the selected street"
            _message.value = "Parked on $name"
            _notificationPrompt.value = true
        }
    }

    fun selectPlan(option: ReparkingOption) = mutate {
        val session = uiState.value.active ?: return@mutate
        val selected = reminderCoordinator.selectPlan(session.sessionId, option.street.id, option.moveAt.toInstant(), option.justCleaned)
        if (selected) _message.value = "Move planned. Your current parking has not changed."
        else _actionError.value = "That opportunity is no longer available. Please choose again."
    }

    fun cancelPlan(sessionId: Long, planId: String) = mutate {
        if (reminderCoordinator.cancelPlan(sessionId, planId)) {
            _message.value = "Plan cancelled. Current parking and deadline warnings are unchanged."
        } else _actionError.value = "The plan changed. Check your current plan and try again."
    }

    fun arriveAtPlan(sessionId: Long, planId: String) = mutate {
        val plan = reminderCoordinator.getPlan(sessionId)?.takeIf { it.id == planId }
        if (plan == null) _actionError.value = "The plan changed. Check your current plan and try again."
        else registered(parkingService.arriveAtPlan(sessionId, planId, clock.instant()), plan.streetId)
    }

    fun toggleFavourite(streetId: Long) = viewModelScope.launch { streetRepository.toggleFavourite(streetId) }

    fun endParking(sessionId: Long) = mutate {
        if (parkingService.endParking(sessionId)) _message.value = "Parking ended. Its plan and reminders were cleared."
        else _actionError.value = "Parking changed. Check the current location before ending it."
    }

    fun clearMessage() { _message.value = null }
    fun clearActionError() { _actionError.value = null }
    fun clearNotificationPrompt() { _notificationPrompt.value = false }
    fun locationPermissionDenied() {
        _actionError.value = "Location permission denied. You can choose a saved street below without location access."
    }

    fun refresh() {
        refreshVersion.value += 1
        viewModelScope.launch { reminderCoordinator.refresh() }
    }

    fun parkWhereIAm() = mutate {
        if (!locationProvider.hasPermission()) {
            locationPermissionDenied()
            return@mutate
        }
        if (parkingRepository.getActiveSession() != null) {
            _actionError.value = "You are already parked. Choose a saved street below to change your location."
            return@mutate
        }
        _parkLoading.value = true
        try {
            val location = locationProvider.current()
            if (location == null) {
                _actionError.value = "Couldn't get a GPS fix. Try again with a clearer view of the sky, or choose a saved street below."
                return@mutate
            }
            val targets = streetRepository.observeStreetsWithRules().first().map { swr ->
                StreetMatcher.Target(
                    swr.street.id, swr.street.name, LatLng(swr.street.lat, swr.street.lng),
                    maxOf(swr.street.matchRadiusMeters, MIN_MATCH_RADIUS_METERS), GeometryCodec.decode(swr.street.geometry),
                )
            }
            val match = streetMatcher.match(location, targets)
            if (match != null) registered(parkingService.parkIfCurrent(match.target.id, null, clock.instant()), match.target.id)
            else _actionError.value = "No reliable saved-street match. Choose the right saved street below; GPS may not distinguish named street sections."
        } finally {
            _parkLoading.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                StreetListViewModel(
                    container.streetRepository, container.parkingRepository, container.parkingService,
                    container.scheduleEngine, container.streetMatcher, container.locationProvider,
                    container.settingsRepository, container.reminderCoordinator,
                )
            }
        }
    }
}

internal fun reminderStatusText(status: ReminderStatus, kind: ReminderKind, zone: ZoneId): String {
    val deadline = kind == ReminderKind.PARKING_DEADLINE
    val label = if (deadline) "Deadline warning" else "Planned move reminder"
    return when (status.state) {
        ReminderSchedulingState.UPDATING -> "Updating reminder status…"
        ReminderSchedulingState.OFF -> if (deadline) "Deadline warnings off" else "Planned move reminders off"
        ReminderSchedulingState.NO_TIMES -> if (deadline) "No warning times selected" else "No move reminder times selected"
        ReminderSchedulingState.NO_TARGET -> if (deadline) "No cleaning deadline in saved hours" else "No selected move"
        ReminderSchedulingState.CANCELLED -> "Plan reminders cancelled"
        ReminderSchedulingState.NO_UPCOMING -> if (deadline) "No upcoming deadline warnings. Adding an already-past warning time will not help." else "No upcoming plan reminders"
        ReminderSchedulingState.SCHEDULED, ReminderSchedulingState.DUE -> {
            val time = Instant.ofEpochMilli(requireNotNull(status.reminder).fireAt).atZone(zone).format(parkingTimeFormatter)
            if (status.state == ReminderSchedulingState.DUE) "$label due or delayed: $time"
            else "$label scheduled: $time"
        }
    }
}
