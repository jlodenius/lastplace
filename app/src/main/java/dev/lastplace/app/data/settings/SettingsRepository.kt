package dev.lastplace.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.lastplace.app.domain.MAX_REMINDER_MINUTES
import dev.lastplace.app.domain.ReminderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

data class ReminderSettings(
    val deadlineOffsetsMinutes: Set<Long> = setOf(24 * 60L, 12 * 60L),
    val moveOffsetsMinutes: Set<Long> = setOf(0L),
    val deadlineWarningsEnabled: Boolean = true,
    val moveRemindersEnabled: Boolean = true,
    val autoDetectEnabled: Boolean = true,
    val preferredReparkingTimes: Set<LocalTime> = emptySet(),
) {
    fun offsets(kind: ReminderKind): Set<Long> = when (kind) {
        ReminderKind.PLANNED_MOVE -> moveOffsetsMinutes
        ReminderKind.PARKING_DEADLINE -> deadlineOffsetsMinutes
    }

    fun enabled(kind: ReminderKind): Boolean = when (kind) {
        ReminderKind.PLANNED_MOVE -> moveRemindersEnabled
        ReminderKind.PARKING_DEADLINE -> deadlineWarningsEnabled
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private val legacyOffsetsKey = stringPreferencesKey("reminder_offsets_hours")
    private val deadlineOffsetsKey = stringSetPreferencesKey("deadline_offsets_minutes")
    private val moveOffsetsKey = stringSetPreferencesKey("move_offsets_minutes")
    private val deadlineEnabledKey = booleanPreferencesKey("deadline_warnings_enabled")
    private val moveEnabledKey = booleanPreferencesKey("move_reminders_enabled")
    private val autoDetectKey = booleanPreferencesKey("auto_detect_enabled")
    private val reparkingTimesKey = stringSetPreferencesKey("preferred_reparking_times")

    private fun offsetsKey(kind: ReminderKind) = when (kind) {
        ReminderKind.PLANNED_MOVE -> moveOffsetsKey
        ReminderKind.PARKING_DEADLINE -> deadlineOffsetsKey
    }

    private fun readSettings(prefs: Preferences): ReminderSettings = ReminderSettings(
        deadlineOffsetsMinutes = prefs[deadlineOffsetsKey]?.parseMinutes()
            ?: prefs[legacyOffsetsKey]?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
                ?.filter { it in 0..MAX_REMINDER_MINUTES / 60 }?.map { it * 60 }?.toSet()
            ?: setOf(24 * 60L, 12 * 60L),
        moveOffsetsMinutes = prefs[moveOffsetsKey]?.parseMinutes() ?: setOf(0L),
        deadlineWarningsEnabled = prefs[deadlineEnabledKey] ?: true,
        moveRemindersEnabled = prefs[moveEnabledKey] ?: true,
        autoDetectEnabled = prefs[autoDetectKey] ?: true,
        preferredReparkingTimes = prefs[reparkingTimesKey].orEmpty().map { LocalTime.parse(it) }.toSet(),
    )

    val settings: Flow<ReminderSettings> = store.data.map(::readSettings)

    suspend fun setReminderEnabled(kind: ReminderKind, enabled: Boolean) {
        store.edit { it[if (kind == ReminderKind.PLANNED_MOVE) moveEnabledKey else deadlineEnabledKey] = enabled }
    }

    suspend fun setReminderOffsets(kind: ReminderKind, minutes: Set<Long>) {
        require(minutes.all { it in 0..MAX_REMINDER_MINUTES })
        store.edit { it[offsetsKey(kind)] = minutes.map(Long::toString).toSet() }
    }

    suspend fun toggleReminderOffset(kind: ReminderKind, minutes: Long) {
        require(minutes in 0..MAX_REMINDER_MINUTES)
        store.edit {
            val current = readSettings(it).offsets(kind)
            val updated = if (minutes in current) current - minutes else current + minutes
            it[offsetsKey(kind)] = updated.map(Long::toString).toSet()
        }
    }

    suspend fun addReminderOffset(kind: ReminderKind, minutes: Long) {
        require(minutes in 0..MAX_REMINDER_MINUTES)
        store.edit {
            it[offsetsKey(kind)] = (readSettings(it).offsets(kind) + minutes).map(Long::toString).toSet()
        }
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
            it[reparkingTimesKey] = it[reparkingTimesKey].orEmpty().intersect(available.map(LocalTime::toString).toSet())
        }
    }

    private fun Set<String>.parseMinutes() = mapNotNull(String::toLongOrNull)
        .filter { it in 0..MAX_REMINDER_MINUTES }.toSet()
}
