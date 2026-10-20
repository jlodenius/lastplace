package dev.lastplace.app.ui.addstreet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lastplace.app.AppContainer
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.GeometryCodec
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.osm.OsmRequestException
import dev.lastplace.app.osm.OsmService
import dev.lastplace.app.osm.StreetSuggestion
import dev.lastplace.app.parking.ParkingService
import dev.lastplace.app.parking.StreetDeletionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

data class RuleInput(
    val dayOfWeek: Int? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
) {
    val isComplete: Boolean
        get() = dayOfWeek in 1..7 && startMinute in 0..1439 && endMinute in 0..1439

    val missingFields: String?
        get() = buildList {
            if (dayOfWeek !in 1..7) add("weekday")
            if (startMinute !in 0..1439) add("start time")
            if (endMinute !in 0..1439) add("end time")
        }.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Choose $it." }

    fun summary(): String? {
        if (!isComplete) return null
        val day = DayOfWeek.of(requireNotNull(dayOfWeek))
        val start = requireNotNull(startMinute)
        val end = requireNotNull(endMinute)
        val dayName = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
        if (end > start) return "$dayName ${formatMinutes(start)}–${formatMinutes(end)}"
        val nextDay = day.plus(1).getDisplayName(TextStyle.FULL, Locale.getDefault())
        val explanation = if (end == start) "24-hour restriction" else "ends next day"
        return "$dayName ${formatMinutes(start)} to $nextDay ${formatMinutes(end)} ($explanation)"
    }
}

internal fun formatMinutes(minuteOfDay: Int): String =
    "%02d:%02d".format(Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)

sealed interface StreetSearchState {
    data object Idle : StreetSearchState
    data object ShortQuery : StreetSearchState
    data class Searching(val query: String) : StreetSearchState
    data class Results(val query: String, val streets: List<StreetSuggestion>) : StreetSearchState
    data class NoMatches(val query: String) : StreetSearchState
    data class Failed(val query: String, val reason: String) : StreetSearchState
}

data class AddStreetUiState(
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val matchRadiusMeters: Int = 60,
    val geometry: List<List<LatLng>> = emptyList(),
    val geometryLoading: Boolean = false,
    val isLocating: Boolean = false,
    val rules: List<RuleInput> = listOf(RuleInput()),
    val originalRules: List<RuleInput>? = null,
    val cleaningHoursAcknowledged: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val mutationInProgress: Boolean = false,
    val search: StreetSearchState = StreetSearchState.Idle,
    val error: String? = null,
    val locationError: String? = null,
    val mapError: String? = null,
    val deletionContext: StreetDeletionContext? = null,
    val saved: Boolean = false,
) {
    val hasLocation: Boolean get() = lat != null && lng != null
    val isMapped: Boolean get() = geometry.isNotEmpty()
    val needsAcknowledgment: Boolean get() = !isEditing || rules != originalRules
    val isBusy: Boolean get() = isLoading || mutationInProgress || geometryLoading || isLocating || saved
    val canSave: Boolean get() = !isBusy && !loadFailed && name.isNotBlank() &&
        (rules.isNotEmpty() || isEditing && originalRules?.isEmpty() == true) &&
        rules.all { it.isComplete } && (!needsAcknowledgment || cleaningHoursAcknowledged)
}

private data class FormSnapshot(
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val geometry: List<List<LatLng>>,
    val rules: List<RuleInput>,
)

private fun AddStreetUiState.snapshot() = FormSnapshot(name, lat, lng, geometry, rules)

class AddStreetViewModel(
    private val streetRepository: StreetRepository,
    private val osmService: OsmService,
    private val locationProvider: LocationProvider,
    private val streetId: Long,
    private val parkingService: ParkingService,
) : ViewModel() {
    private val _state = MutableStateFlow(AddStreetUiState(isEditing = streetId >= 0, isLoading = streetId >= 0))
    val state: StateFlow<AddStreetUiState> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var originalStreet: Street? = null
    private var baseline = _state.value.snapshot()
    private var geometryChanged = false
    private var nearBias: LatLng? = null
    private var searchJob: Job? = null
    private var searchVersion = 0L
    private var geometryJob: Job? = null
    private var geometryVersion = 0L

    init {
        if (streetId >= 0) loadExisting()
    }

    fun clearMessage() { _message.value = null }

    private fun edit(change: (AddStreetUiState) -> AddStreetUiState) {
        _state.update { old ->
            val updated = change(old)
            updated.copy(hasUnsavedChanges = updated.snapshot() != baseline)
        }
    }

    fun loadExisting() {
        if (streetId < 0 || _state.value.mutationInProgress) return
        _state.update { it.copy(isLoading = true, loadFailed = false, error = null) }
        viewModelScope.launch {
            try {
                val existing = streetRepository.getStreetWithRules(streetId)
                    ?: throw IllegalStateException("This street no longer exists.")
                originalStreet = existing.street
                val loaded = _state.value.copy(
                    isLoading = false,
                    name = existing.street.name,
                    lat = existing.street.lat.takeIf { it != 0.0 },
                    lng = existing.street.lng.takeIf { it != 0.0 },
                    matchRadiusMeters = existing.street.matchRadiusMeters,
                    geometry = GeometryCodec.decode(existing.street.geometry),
                    rules = existing.rules.map { RuleInput(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay) },
                    originalRules = existing.rules.map { RuleInput(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay) },
                )
                baseline = loaded.snapshot()
                nearBias = loaded.lat?.let { lat -> loaded.lng?.let { lng -> LatLng(lat, lng) } }
                _state.value = loaded.copy(hasUnsavedChanges = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, loadFailed = true, error = "Couldn't load street. ${e.message.orEmpty()}") }
            }
        }
    }

    fun onQueryChange(query: String) {
        if (_state.value.isBusy || _state.value.loadFailed) return
        edit { it.copy(name = query) }
        search(query, debounce = true)
    }

    fun retrySearch() {
        if (!_state.value.isBusy) search(_state.value.name, debounce = false)
    }

    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val version = ++searchVersion
        val trimmed = query.trim()
        _state.update {
            it.copy(search = when {
                trimmed.isEmpty() -> StreetSearchState.Idle
                trimmed.length < 3 -> StreetSearchState.ShortQuery
                else -> StreetSearchState.Searching(query)
            })
        }
        if (trimmed.length < 3) return
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            val result = try {
                val results = osmService.searchStreets(trimmed, nearBias)
                if (results.isEmpty()) StreetSearchState.NoMatches(query)
                else StreetSearchState.Results(query, results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StreetSearchState.Failed(query, e.message ?: "The search service is unavailable.")
            }
            if (version == searchVersion) _state.update { it.copy(search = result) }
        }
    }

    private fun hideSearch() {
        searchJob?.cancel()
        searchVersion++
        _state.update { it.copy(search = StreetSearchState.Idle) }
    }

    fun onSuggestionPicked(suggestion: StreetSuggestion) {
        val current = _state.value
        val results = current.search as? StreetSearchState.Results ?: return
        if (current.isBusy || suggestion !in results.streets) return
        hideSearch()
        edit { it.copy(name = suggestion.name, lat = suggestion.point.lat, lng = suggestion.point.lng, mapError = null) }
        loadGeometry(suggestion.name, suggestion.point)
    }

    fun locationPermissionDenied() {
        _state.update { it.copy(locationError = "Location permission denied. Search by street name or pick on the map instead.") }
    }

    fun useCurrentLocation() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isLocating = true, locationError = null) }
        viewModelScope.launch {
            try {
                val point = locationProvider.current()
                _state.update { it.copy(isLocating = false) }
                if (point == null) {
                    _state.update { it.copy(locationError = "No location fix. Try again, search by street name, or pick on the map.") }
                } else {
                    nearBias = point
                    onMapPointPicked(point)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLocating = false, locationError = "Couldn't get your location. Search by street name or pick on the map instead.") }
            }
        }
    }

    fun refreshStreetGeometry() {
        val s = _state.value
        val lat = s.lat ?: return
        val lng = s.lng ?: return
        onMapPointPicked(LatLng(lat, lng))
    }

    fun onMapPointPicked(point: LatLng) {
        if (_state.value.isLoading || _state.value.mutationInProgress || _state.value.saved) return
        hideSearch()
        geometryJob?.cancel()
        val version = ++geometryVersion
        edit { it.copy(lat = point.lat, lng = point.lng, geometryLoading = true, mapError = null, locationError = null) }
        geometryJob = viewModelScope.launch {
            try {
                val nearest = osmService.nearestStreet(point)
                val resolvedName = nearest?.name ?: locationProvider.reverseStreetName(point)
                if (resolvedName.isNullOrBlank()) {
                    if (version == geometryVersion) _state.update {
                        it.copy(geometryLoading = false, mapError = "Couldn't identify a street here. Search by name or pick on the map closer to the road.")
                    }
                    return@launch
                }
                val full = try {
                    osmService.fetchGeometry(resolvedName, point)
                } catch (e: OsmRequestException) {
                    if (nearest == null) throw e
                    emptyList()
                }
                val ways = when {
                    full.isNotEmpty() -> full
                    nearest != null -> listOf(nearest.geometry)
                    else -> emptyList()
                }
                if (version != geometryVersion) return@launch
                geometryChanged = true
                edit { it.copy(name = resolvedName, geometry = ways, geometryLoading = false) }
                when {
                    ways.isEmpty() -> _state.update { it.copy(mapError = "Couldn't fetch any street shape here. Try again or pick on the map closer to the road.") }
                    full.isEmpty() -> _state.update { it.copy(mapError = "Captured one segment only. Try again or pick on the map closer for the full street.") }
                    else -> _message.value = "Street shape captured."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == geometryVersion) _state.update {
                    it.copy(geometryLoading = false, mapError = "Map service error: ${e.message.orEmpty()}. Try again or search by street name.")
                }
            }
        }
    }

    private fun loadGeometry(name: String, point: LatLng) {
        geometryJob?.cancel()
        val version = ++geometryVersion
        _state.update { it.copy(geometryLoading = true) }
        geometryJob = viewModelScope.launch {
            try {
                val ways = osmService.fetchGeometry(name, point)
                if (version != geometryVersion) return@launch
                geometryChanged = true
                edit { it.copy(geometry = ways, geometryLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == geometryVersion) _state.update {
                    it.copy(geometryLoading = false, mapError = "Map service error: ${e.message.orEmpty()}. Try capturing the street shape again.")
                }
            }
        }
    }

    fun addRule() = changeRules { it + RuleInput() }
    fun removeRule(index: Int) = changeRules { it.filterIndexed { i, _ -> i != index } }
    fun updateRule(index: Int, rule: RuleInput) = changeRules { rules -> rules.mapIndexed { i, r -> if (i == index) rule else r } }

    private fun changeRules(change: (List<RuleInput>) -> List<RuleInput>) {
        if (_state.value.isBusy) return
        edit { old ->
            val rules = change(old.rules)
            if (rules == old.rules) old else old.copy(rules = rules, cleaningHoursAcknowledged = false)
        }
    }

    fun acknowledgeCleaningHours(checked: Boolean) {
        if (!_state.value.isBusy) _state.update { it.copy(cleaningHoursAcknowledged = checked) }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        hideSearch()
        _state.update { it.copy(mutationInProgress = true, error = null) }
        viewModelScope.launch {
            try {
                val previous = originalStreet
                val street = (previous ?: Street(name = "", lat = 0.0, lng = 0.0)).copy(
                    name = s.name.trim(),
                    lat = s.lat ?: 0.0,
                    lng = s.lng ?: 0.0,
                    matchRadiusMeters = s.matchRadiusMeters,
                    geometry = if (geometryChanged) GeometryCodec.encode(s.geometry) else previous?.geometry,
                )
                val rules = s.rules.map {
                    CleaningRule(
                        streetId = street.id,
                        dayOfWeek = requireNotNull(it.dayOfWeek),
                        startMinuteOfDay = requireNotNull(it.startMinute),
                        endMinuteOfDay = requireNotNull(it.endMinute),
                    )
                }
                if (s.isEditing) streetRepository.updateStreet(street, rules)
                else streetRepository.addStreet(street, rules)
                _message.value = if (s.isEditing) "Street changes saved." else "Street added."
                _state.update { it.copy(saved = true, mutationInProgress = false, hasUnsavedChanges = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(mutationInProgress = false, error = "Couldn't save this street. Your edits are still here; try again.") }
            }
        }
    }

    fun requestDelete() {
        if (streetId < 0 || _state.value.isBusy || _state.value.loadFailed) return
        _state.update { it.copy(mutationInProgress = true, error = null) }
        viewModelScope.launch {
            try {
                val context = parkingService.deletionContext(streetId)
                _state.update { it.copy(mutationInProgress = false, deletionContext = context, error = if (context == null) "This street no longer exists." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(mutationInProgress = false, error = "Couldn't prepare deletion. Try again.") }
            }
        }
    }

    fun cancelDelete() {
        if (!_state.value.mutationInProgress) _state.update { it.copy(deletionContext = null) }
    }

    fun confirmDelete() {
        val s = _state.value
        val context = s.deletionContext ?: return
        if (s.mutationInProgress) return
        _state.update { it.copy(mutationInProgress = true, error = null) }
        viewModelScope.launch {
            try {
                if (parkingService.deleteStreet(context)) {
                    _message.value = "Street deleted."
                    _state.update { it.copy(saved = true, mutationInProgress = false, hasUnsavedChanges = false, deletionContext = null) }
                } else {
                    _state.update { it.copy(mutationInProgress = false, deletionContext = null, error = "Parking, plan, or street details changed. Nothing was deleted. Review and try again.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(mutationInProgress = false, deletionContext = null, error = "Couldn't delete the street. Try again.") }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, streetId: Long) = viewModelFactory {
            initializer {
                AddStreetViewModel(container.streetRepository, container.osmService, container.locationProvider, streetId, container.parkingService)
            }
        }
    }
}
