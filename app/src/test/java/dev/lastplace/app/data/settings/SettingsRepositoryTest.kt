package dev.lastplace.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
    fun selectionsSurviveReopeningTheStoreWithoutChangingReminderSettings() = runTest {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val repository = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
        repository.setOffsets(listOf(6, 2))
        repository.setAutoDetect(false)
        repository.toggleReparkingTime(afternoon)
        scope.cancel()
        job.join()
        val reopened = SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertEquals(
            ReminderSettings(offsetsHours = listOf(6, 2), autoDetectEnabled = false, preferredReparkingTimes = setOf(afternoon)),
            reopened.settings.first(),
        )
    }
}
