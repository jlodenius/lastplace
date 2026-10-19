package dev.lastplace.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/** Reminder preferences. */
data class ReminderSettings(
    val offsetsHours: List<Int> = listOf(24, 12),
    val autoDetectEnabled: Boolean = true,
    val preferredReparkingTimes: Set<LocalTime> = emptySet(),
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    private val offsetsKey = stringPreferencesKey("reminder_offsets_hours")
    private val autoDetectKey = booleanPreferencesKey("auto_detect_enabled")
    private val reparkingTimesKey = stringSetPreferencesKey("preferred_reparking_times")

    val settings: Flow<ReminderSettings> = store.data.map { prefs ->
        ReminderSettings(
            offsetsHours = prefs[offsetsKey]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.map { it.trim().toInt() }
                ?: listOf(24, 12),
            autoDetectEnabled = prefs[autoDetectKey] ?: true,
            preferredReparkingTimes = prefs[reparkingTimesKey].orEmpty().map { LocalTime.parse(it) }.toSet(),
        )
    }

    suspend fun setOffsets(hours: List<Int>) {
        store.edit { it[offsetsKey] = hours.joinToString(",") }
    }

    suspend fun setAutoDetect(enabled: Boolean) {
        store.edit { it[autoDetectKey] = enabled }
    }

    suspend fun toggleReparkingTime(time: LocalTime) {
        store.edit {
            val current = it[reparkingTimesKey].orEmpty()
            val value = time.toString()
            it[reparkingTimesKey] = if (value in current) current - value else current + value
        }
    }

    suspend fun retainReparkingTimes(available: Set<LocalTime>) {
        store.edit {
            val availableValues = available.map { time -> time.toString() }.toSet()
            it[reparkingTimesKey] = it[reparkingTimesKey].orEmpty().intersect(availableValues)
        }
    }
}
