package dev.lastplace.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Reminder preferences. */
data class ReminderSettings(
    val offsetsHours: List<Int> = listOf(24, 12),
    val autoDetectEnabled: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val offsetsKey = stringPreferencesKey("reminder_offsets_hours")
    private val autoDetectKey = booleanPreferencesKey("auto_detect_enabled")

    val settings: Flow<ReminderSettings> = context.dataStore.data.map { prefs ->
        ReminderSettings(
            offsetsHours = prefs[offsetsKey]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.map { it.trim().toInt() }
                ?: listOf(24, 12),
            autoDetectEnabled = prefs[autoDetectKey] ?: true,
        )
    }

    suspend fun setOffsets(hours: List<Int>) {
        context.dataStore.edit { it[offsetsKey] = hours.joinToString(",") }
    }

    suspend fun setAutoDetect(enabled: Boolean) {
        context.dataStore.edit { it[autoDetectKey] = enabled }
    }
}
