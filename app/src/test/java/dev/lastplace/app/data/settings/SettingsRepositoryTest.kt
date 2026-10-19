package dev.lastplace.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.lastplace.app.domain.ReminderKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalTime

class SettingsRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val morning = LocalTime.of(6, 0)
    private val afternoon = LocalTime.of(14, 0)

    @Test
    fun preferencesStartEmptyAndSupportMultipleIndependentSelections() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertTrue(repository.settings.first().preferredReparkingTimes.isEmpty())
        repository.toggleReparkingTime(morning)
        repository.toggleReparkingTime(afternoon)
        assertEquals(setOf(morning, afternoon), repository.settings.first().preferredReparkingTimes)
        repository.toggleReparkingTime(morning)
        assertEquals(setOf(afternoon), repository.settings.first().preferredReparkingTimes)
        repository.toggleReparkingTime(afternoon)
        assertTrue(repository.settings.first().preferredReparkingTimes.isEmpty())
    }

    @Test
    fun rapidTogglesAreAtomicAndDoNotLoseSelections() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        val first = launch { repository.toggleReparkingTime(morning) }
        val second = launch { repository.toggleReparkingTime(afternoon) }
        first.join()
        second.join()
        assertEquals(setOf(morning, afternoon), repository.settings.first().preferredReparkingTimes)
    }

    @Test
    fun removedTimesAreForgottenAndNewOrReintroducedTimesStartUnselected() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        repository.toggleReparkingTime(morning)
        repository.toggleReparkingTime(afternoon)
        repository.retainReparkingTimes(setOf(afternoon))
        assertEquals(setOf(afternoon), repository.settings.first().preferredReparkingTimes)
        repository.retainReparkingTimes(setOf(morning, afternoon, LocalTime.of(16, 0)))
        assertEquals(setOf(afternoon), repository.settings.first().preferredReparkingTimes)
        repository.retainReparkingTimes(emptySet())
        assertTrue(repository.settings.first().preferredReparkingTimes.isEmpty())
    }

    @Test
    fun legacyHourSettingsMigrateWithoutChangingThemOrTheMoveDefaults() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { it[stringPreferencesKey("reminder_offsets_hours")] = "48,24" }
        val repository = SettingsRepository(store)
        assertEquals(setOf(2880L, 1440L), repository.settings.first().deadlineOffsetsMinutes)
        assertEquals(setOf(0L), repository.settings.first().moveOffsetsMinutes)
        repository.addReminderOffset(ReminderKind.PARKING_DEADLINE, 10)
        assertEquals(setOf(2880L, 1440L, 10L), repository.settings.first().deadlineOffsetsMinutes)
        assertEquals(setOf(0L), repository.settings.first().moveOffsetsMinutes)
    }

    @Test
    fun emptyLegacySelectionsStayEmpty() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { it[stringPreferencesKey("reminder_offsets_hours")] = "" }
        assertTrue(SettingsRepository(store).settings.first().deadlineOffsetsMinutes.isEmpty())
    }

    @Test
    fun notificationCategoriesAllowMultipleCustomOffsetsAndIndependentDisabling() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        repository.addReminderOffset(ReminderKind.PLANNED_MOVE, 60)
        repository.addReminderOffset(ReminderKind.PLANNED_MOVE, 10)
        repository.addReminderOffset(ReminderKind.PLANNED_MOVE, 10)
        repository.addReminderOffset(ReminderKind.PARKING_DEADLINE, 2880)
        assertEquals(setOf(0L, 10L, 60L), repository.settings.first().moveOffsetsMinutes)
        assertEquals(setOf(720L, 1440L, 2880L), repository.settings.first().deadlineOffsetsMinutes)
        repository.setReminderEnabled(ReminderKind.PLANNED_MOVE, false)
        assertTrue(repository.settings.first().deadlineWarningsEnabled)
        repository.setReminderEnabled(ReminderKind.PARKING_DEADLINE, false)
        assertEquals(setOf(0L, 10L, 60L), repository.settings.first().moveOffsetsMinutes)
        repository.toggleReminderOffset(ReminderKind.PLANNED_MOVE, 10)
        assertEquals(setOf(0L, 60L), repository.settings.first().moveOffsetsMinutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCustomOffsetsAreRejected() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        repository.addReminderOffset(ReminderKind.PLANNED_MOVE, -1)
    }

    @Test
    fun selectionsSurviveReopeningTheStoreWithoutChangingReminderSettings() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        repository.setReminderOffsets(ReminderKind.PARKING_DEADLINE, setOf(360, 120))
        repository.setAutoDetect(false)
        repository.addReminderOffset(ReminderKind.PLANNED_MOVE, 10)
        repository.addReminderOffset(ReminderKind.PLANNED_MOVE, 60)
        repository.setReminderEnabled(ReminderKind.PARKING_DEADLINE, false)
        repository.toggleReparkingTime(afternoon)
        scope.cancel()
        job.join()
        val reopened = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertEquals(
            ReminderSettings(
                deadlineOffsetsMinutes = setOf(360, 120), moveOffsetsMinutes = setOf(0, 10, 60),
                deadlineWarningsEnabled = false, autoDetectEnabled = false, preferredReparkingTimes = setOf(afternoon),
            ),
            reopened.settings.first(),
        )
    }
}
