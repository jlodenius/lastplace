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
import dev.lastplace.app.osm.OsmService
import dev.lastplace.app.osm.StreetSuggestion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One editable cleaning window in the form. */
data class RuleInput(
    val dayOfWeek: Int = 2,         // default Tuesday
    val startMinute: Int = 8 * 60,  // 08:00
    val endMinute: Int = 14 * 60,   // 14:00
)

data class AddStreetUiState(
    val isEditing: Boolean = false,
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val matchRadiusMeters: Int = 40,
    val geometry: List<List<LatLng>> = emptyList(),
    val geometryLoading: Boolean = false,
    val rules: List<RuleInput> = listOf(RuleInput()),
    val saved: Boolean = false,
) {
    val hasLocation: Boolean get() = lat != null && lng != null
    val isMapped: Boolean get() = geometry.isNotEmpty()
    val canSave: Boolean get() = name.isNotBlank() && rules.isNotEmpty()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AddStreetViewModel(
    private val streetRepository: StreetRepository,
    private val osmService: OsmService,
    private val locationProvider: LocationProvider,
    private val streetId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AddStreetUiState(isEditing = streetId >= 0))
    val state: StateFlow<AddStreetUiState> = _state.asStateFlow()

    private var nearBias: LatLng? = null
    private val queryFlow = MutableStateFlow("")

    /** Live autocomplete suggestions for the current query. */
    val suggestions: StateFlow<List<StreetSuggestion>> =
        queryFlow
            .debounce(300)
            .mapLatest { query ->
                if (query.length < 3) emptyList() else osmService.searchStreets(query, nearBias)
            }
            .catch { emit(emptyList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { nearBias = locationProvider.current() }
        if (streetId >= 0) loadExisting()
    }

    private fun loadExisting() = viewModelScope.launch {
        val existing = streetRepository.getStreetWithRules(streetId) ?: return@launch
        _state.update {
            it.copy(
                name = existing.street.name,
                lat = existing.street.lat.takeIf { v -> v != 0.0 },
                lng = existing.street.lng.takeIf { v -> v != 0.0 },
                matchRadiusMeters = existing.street.matchRadiusMeters,
                geometry = GeometryCodec.decode(existing.street.geometry),
                rules = existing.rules
                    .map { r -> RuleInput(r.dayOfWeek, r.startMinuteOfDay, r.endMinuteOfDay) }
                    .ifEmpty { listOf(RuleInput()) },
            )
        }
    }

    /** User typing in the name/search field. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(name = query) }
        queryFlow.value = query
    }

    /** User tapped an autocomplete result. */
    fun onSuggestionPicked(suggestion: StreetSuggestion) {
        queryFlow.value = "" // hide the dropdown
        _state.update {
            it.copy(name = suggestion.name, lat = suggestion.point.lat, lng = suggestion.point.lng)
        }
        loadGeometry(suggestion.name, suggestion.point)
    }

    /** Use the current GPS position as the street point (reverse-geocodes + loads geometry). */
    fun useCurrentLocation() = viewModelScope.launch {
        locationProvider.current()?.let { onMapPointPicked(it) }
    }

    /**
     * A point chosen on the map (or current location). Identifies the street via OSM (not the
     * device geocoder, which mis-names), then loads the whole street's geometry.
     */
    fun onMapPointPicked(point: LatLng) {
        queryFlow.value = ""
        _state.update { it.copy(lat = point.lat, lng = point.lng, geometryLoading = true) }
        viewModelScope.launch {
            val name = osmService.nearestStreetName(point)
                ?: locationProvider.reverseStreetName(point)
            if (name.isNullOrBlank()) {
                _state.update { it.copy(geometryLoading = false) }
                return@launch
            }
            val ways = osmService.fetchGeometry(name, point)
            _state.update { it.copy(name = name, geometry = ways, geometryLoading = false) }
        }
    }

    private fun loadGeometry(name: String, point: LatLng) {
        viewModelScope.launch {
            _state.update { it.copy(geometryLoading = true) }
            val ways = osmService.fetchGeometry(name, point)
            _state.update { it.copy(geometry = ways, geometryLoading = false) }
        }
    }

    fun addRule() = _state.update { it.copy(rules = it.rules + RuleInput()) }

    fun removeRule(index: Int) = _state.update {
        it.copy(rules = it.rules.filterIndexed { i, _ -> i != index })
    }

    fun updateRule(index: Int, rule: RuleInput) = _state.update {
        it.copy(rules = it.rules.mapIndexed { i, r -> if (i == index) rule else r })
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val street = Street(
                id = if (s.isEditing) streetId else 0,
                name = s.name.trim(),
                lat = s.lat ?: 0.0,
                lng = s.lng ?: 0.0,
                matchRadiusMeters = s.matchRadiusMeters,
                geometry = GeometryCodec.encode(s.geometry),
            )
            val rules = s.rules.map {
                CleaningRule(
                    streetId = street.id,
                    dayOfWeek = it.dayOfWeek,
                    startMinuteOfDay = it.startMinute,
                    endMinuteOfDay = it.endMinute,
                )
            }
            if (s.isEditing) streetRepository.updateStreet(street, rules)
            else streetRepository.addStreet(street, rules)
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        if (streetId < 0) return
        viewModelScope.launch {
            streetRepository.getStreetWithRules(streetId)?.let {
                streetRepository.deleteStreet(it.street)
            }
            _state.update { it.copy(saved = true) }
        }
    }

    companion object {
        fun factory(container: AppContainer, streetId: Long) = viewModelFactory {
            initializer {
                AddStreetViewModel(
                    streetRepository = container.streetRepository,
                    osmService = container.osmService,
                    locationProvider = container.locationProvider,
                    streetId = streetId,
                )
            }
        }
    }
}
