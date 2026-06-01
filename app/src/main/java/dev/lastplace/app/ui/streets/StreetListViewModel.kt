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
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.parking.ParkingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StreetRow(
    val id: Long,
    val name: String,
    val freeFor: String?,           // e.g. "3d 5h"; null when there are no cleaning hours
    val nextCleaningText: String?,  // e.g. "cleaning Tue 8 Jun, 08:00"
)

data class ActiveParking(
    val sessionId: Long,
    val streetName: String,
    val deadlineText: String,
    val remainingText: String,
)

data class HomeUiState(
    val streets: List<StreetRow> = emptyList(),
    val active: ActiveParking? = null,
)

class StreetListViewModel(
    private val streetRepository: StreetRepository,
    private val parkingRepository: ParkingRepository,
    private val parkingService: ParkingService,
    private val scheduleEngine: ScheduleEngine,
    private val streetMatcher: StreetMatcher,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _parkLoading = MutableStateFlow(false)
    val parkLoading: StateFlow<Boolean> = _parkLoading

    val uiState: StateFlow<HomeUiState> =
        combine(
            streetRepository.observeStreetsWithRules(),
            parkingRepository.observeActiveSession(),
        ) { streets, active ->
            val now = ZonedDateTime.now()
            // Always rank by how much free time each street has — most useful when choosing
            // where to park, and still informative when already parked (where to move to next).
            // Streets with no cleaning hours (park anytime) sort to the very top.
            val ordered = streets
                .map { swr -> swr to scheduleEngine.nextWindowStart(swr.rules.map { it.toWindow() }, now) }
                .sortedByDescending { it.second?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE }
            val rows = ordered.map { (swr, next) ->
                StreetRow(
                    id = swr.street.id,
                    name = swr.street.name,
                    freeFor = next?.let { freeForText(now, it) },
                    nextCleaningText = next?.let { "cleaning ${it.format(FORMATTER)}" },
                )
            }
            val activeUi = active?.let { session ->
                val name = streets.firstOrNull { it.street.id == session.streetId }?.street?.name
                    ?: "Unknown street"
                val deadline = session.deadline?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                }
                ActiveParking(
                    sessionId = session.id,
                    streetName = name,
                    deadlineText = deadline?.format(FORMATTER) ?: "No cleaning deadline",
                    remainingText = deadline?.let { remainingText(now, it) } ?: "",
                )
            }
            HomeUiState(rows, activeUi)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun park(streetId: Long) = viewModelScope.launch { parkingService.park(streetId) }

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
                    matchRadiusMeters = swr.street.matchRadiusMeters,
                    geometry = GeometryCodec.decode(swr.street.geometry),
                )
            }
            val match = streetMatcher.match(location, targets)
            if (match != null) {
                parkingService.park(match.target.id)
                _message.value = "Parked on ${match.target.name}"
            } else {
                _message.value = "No saved street nearby — use \"Park here\" on the right one"
            }
        } finally {
            _parkLoading.value = false
        }
    }

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

        private fun remainingText(now: ZonedDateTime, deadline: ZonedDateTime): String {
            val d = Duration.between(now, deadline)
            if (d.isNegative) return "overdue — move now"
            return "in ${freeForText(now, deadline)}"
        }

        private fun freeForText(now: ZonedDateTime, until: ZonedDateTime): String {
            val d = Duration.between(now, until)
            if (d.isNegative || d.isZero) return "0m"
            val days = d.toDays()
            val hours = d.toHours() % 24
            val minutes = d.toMinutes() % 60
            return when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h ${minutes}m"
                else -> "${minutes}m"
            }
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
                )
            }
        }
    }
}
