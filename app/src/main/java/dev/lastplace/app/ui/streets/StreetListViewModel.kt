package dev.lastplace.app.ui.streets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lastplace.app.AppContainer
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.GeometryCodec
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReparkingOption
import dev.lastplace.app.domain.ReparkingPlanner
import dev.lastplace.app.domain.ReparkingStreet
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.parking.ParkingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** Lower bound for the per-street match radius; absorbs GPS noise + street width. */
private const val MIN_MATCH_RADIUS_METERS = 60

data class ActiveParking(
    val sessionId: Long,
    val streetName: String,
    val deadlineText: String,
    val remainingText: String,
    val hasDeadline: Boolean,
)

data class HomeUiState(
    val streets: List<StreetRow> = emptyList(),
    val active: ActiveParking? = null,
    val daytimeOnly: Boolean = false,
    val hasSavedStreets: Boolean = false,
    val reparkingOptions: List<ReparkingOption> = emptyList(),
)

class StreetListViewModel(
    private val streetRepository: StreetRepository,
    private val parkingRepository: ParkingRepository,
    private val parkingService: ParkingService,
    private val scheduleEngine: ScheduleEngine,
    private val streetMatcher: StreetMatcher,
    private val locationProvider: LocationProvider,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _parkLoading = MutableStateFlow(false)
    val parkLoading: StateFlow<Boolean> = _parkLoading

    private val daytimeOnly = MutableStateFlow(false)
    private val reparkingPlanner = ReparkingPlanner(scheduleEngine)
    private val currentTime = flow {
        while (true) {
            val now = ZonedDateTime.now(clock)
            emit(now)
            delay(60_000L - now.toInstant().toEpochMilli().mod(60_000L))
        }
    }

    fun showDaytimeOnly(enabled: Boolean) { daytimeOnly.value = enabled }

    val uiState: StateFlow<HomeUiState> =
        combine(
            streetRepository.observeStreetsWithRules(),
            currentTime,
            daytimeOnly,
            parkingRepository.observeActiveSession(),
            settingsRepository.settings,
        ) { streets, now, daytimeOnly, active, settings ->
            val rows = buildStreetRows(streets, now, scheduleEngine, daytimeOnly)
            val activeUi = active?.let { session ->
                val name = streets.firstOrNull { it.street.id == session.streetId }?.street?.name
                    ?: "Unknown street"
                val deadline = session.deadline?.let {
                    Instant.ofEpochMilli(it).atZone(now.zone)
                }
                ActiveParking(
                    sessionId = session.id,
                    streetName = name,
                    deadlineText = deadline?.format(parkingTimeFormatter) ?: "No cleaning deadline",
                    remainingText = deadline?.let { remainingText(now, it) } ?: "",
                    hasDeadline = deadline != null,
                )
            }
            HomeUiState(
                streets = rows,
                active = activeUi,
                daytimeOnly = daytimeOnly,
                hasSavedStreets = streets.isNotEmpty(),
                reparkingOptions = active?.let { session ->
                    reparkingPlanner.options(
                        streets = streets.map {
                            ReparkingStreet(
                                id = it.street.id,
                                name = it.street.name,
                                windows = it.rules.map { rule -> rule.toWindow() },
                                isFavourite = it.street.isFavourite,
                            )
                        },
                        currentStreetId = session.streetId,
                        deadline = session.deadline?.let { Instant.ofEpochMilli(it).atZone(now.zone) },
                        preferredTimes = settings.preferredReparkingTimes,
                        now = now,
                    )
                }.orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun park(streetId: Long) = viewModelScope.launch {
        if (parkingService.park(streetId, parkedAt = clock.instant()) == -1L) {
            _message.value = "This street isn't available to park on right now"
        }
    }

    fun toggleFavourite(streetId: Long) = viewModelScope.launch { streetRepository.toggleFavourite(streetId) }

    fun endParking(sessionId: Long) = viewModelScope.launch { parkingService.endParking(sessionId) }

    fun clearMessage() { _message.value = null }

    /** Detect which saved street the phone is on and park there. */
    fun parkWhereIAm() = viewModelScope.launch {
        if (!locationProvider.hasPermission()) {
            _message.value = "Location permission needed"
            return@launch
        }
        if (_parkLoading.value) return@launch // ignore re-taps while a fix is in flight
        _parkLoading.value = true
        try {
            val location = locationProvider.current()
            if (location == null) {
                _message.value = "Couldn't get a GPS fix — try again with a clearer view of the sky"
                return@launch
            }
            val targets = streetRepository.observeStreetsWithRules().first().map { swr ->
                StreetMatcher.Target(
                    id = swr.street.id,
                    name = swr.street.name,
                    point = LatLng(swr.street.lat, swr.street.lng),
                    matchRadiusMeters = maxOf(swr.street.matchRadiusMeters, MIN_MATCH_RADIUS_METERS),
                    geometry = GeometryCodec.decode(swr.street.geometry),
                )
            }
            val match = streetMatcher.match(location, targets)
            if (match != null) {
                _message.value = if (parkingService.park(match.target.id, parkedAt = clock.instant()) == -1L) {
                    "${match.target.name} isn't available to park on right now"
                } else {
                    "Parked on ${match.target.name}"
                }
            } else {
                _message.value = "No saved street nearby — use \"Park here\" on the right one"
            }
        } finally {
            _parkLoading.value = false
        }
    }

    companion object {
        private fun remainingText(now: ZonedDateTime, deadline: ZonedDateTime): String {
            val d = Duration.between(now, deadline)
            if (d.isNegative || d.isZero) return "overdue — move now"
            return "in ${freeForText(now, deadline)}"
        }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                StreetListViewModel(
                    streetRepository = container.streetRepository,
                    parkingRepository = container.parkingRepository,
                    parkingService = container.parkingService,
                    scheduleEngine = container.scheduleEngine,
                    streetMatcher = container.streetMatcher,
                    locationProvider = container.locationProvider,
                    settingsRepository = container.settingsRepository,
                )
            }
        }
    }
}
