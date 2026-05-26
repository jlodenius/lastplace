package dev.lastplace.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.lastplace.app.AppContainer
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ReminderSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReminderSettings())

    /** Adds or removes an offset, keeping the list sorted descending. */
    fun toggleOffset(hours: Int) {
        val current = settings.value.offsetsHours
        val updated = if (hours in current) current - hours else current + hours
        viewModelScope.launch { repository.setOffsets(updated.sortedDescending()) }
    }

    fun setAutoDetect(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoDetect(enabled) }
    }

    companion object {
        val OFFSET_OPTIONS = listOf(48, 24, 12, 6, 2)

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository) }
        }
    }
}
