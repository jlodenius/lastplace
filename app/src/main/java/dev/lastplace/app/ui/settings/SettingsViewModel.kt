package dev.lastplace.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lastplace.app.AppContainer
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

data class ReparkingTimeChoice(val time: LocalTime, val selected: Boolean)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val streetRepository: StreetRepository,
) : ViewModel() {

    val settings: StateFlow<ReminderSettings?> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val reparkingTimes: StateFlow<List<ReparkingTimeChoice>?> = combine(
        streetRepository.observeStreetsWithRules(),
        repository.settings,
    ) { streets, settings ->
        streets.flatMap { street -> street.rules.map { it.toWindow().end } }
            .distinct().sorted().map { ReparkingTimeChoice(it, it in settings.preferredReparkingTimes) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleReparkingTime(time: LocalTime) {
        viewModelScope.launch {
            if (reparkingTimes.value?.any { it.time == time } == true) repository.toggleReparkingTime(time)
        }
    }

    fun toggleOffset(kind: ReminderKind, minutes: Long) {
        viewModelScope.launch { repository.toggleReminderOffset(kind, minutes) }
    }

    fun addOffset(kind: ReminderKind, minutes: Long) {
        viewModelScope.launch { repository.addReminderOffset(kind, minutes) }
    }

    fun setReminderEnabled(kind: ReminderKind, enabled: Boolean) {
        viewModelScope.launch { repository.setReminderEnabled(kind, enabled) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository, container.streetRepository) }
        }
    }
}
