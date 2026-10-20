package dev.lastplace.app.ui.settings

import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun street(id: Long, end: Int) = StreetWithRules(
        Street(id = id, name = "Street $id", lat = 0.0, lng = 0.0),
        listOf(CleaningRule(streetId = id, dayOfWeek = 2, startMinuteOfDay = 0, endMinuteOfDay = end * 60)),
    )

    @Test
    fun deadlinePresetsIncludeShortWarningsWithoutChangingDefaults() {
        assertTrue(reminderPresets(ReminderKind.PARKING_DEADLINE).containsAll(setOf(10L, 60L)))
        assertEquals(setOf(1440L, 720L), ReminderSettings().deadlineOffsetsMinutes)
        assertEquals(setOf(0L), ReminderSettings().moveOffsetsMinutes)
    }

    @Test
    fun settingsHaveLoadingStateInsteadOfPretendingDefaultsAreStoredSelections() = runTest {
        val settings = mock(SettingsRepository::class.java)
        val streets = mock(StreetRepository::class.java)
        val loaded = ReminderSettings(deadlineOffsetsMinutes = emptySet(), deadlineWarningsEnabled = false)
        `when`(settings.settings).thenReturn(MutableStateFlow(loaded))
        `when`(streets.observeStreetsWithRules()).thenReturn(MutableStateFlow(emptyList()))
        val model = SettingsViewModel(settings, streets)
        assertNull(model.settings.value)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.settings.collect() }
        runCurrent()
        assertEquals(loaded, model.settings.value)
    }

    @Test
    fun preferredTimesStayLoadingUntilStreetsEmitAndThenDistinguishLoadedEmptyChoices() = runTest {
        val settings = mock(SettingsRepository::class.java)
        val streets = mock(StreetRepository::class.java)
        val loaded = ReminderSettings()
        val saved = MutableSharedFlow<List<StreetWithRules>>(replay = 1)
        `when`(settings.settings).thenReturn(MutableStateFlow(loaded))
        `when`(streets.observeStreetsWithRules()).thenReturn(saved)
        val model = SettingsViewModel(settings, streets)
        assertNull(model.reparkingTimes.value)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.settings.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.reparkingTimes.collect() }
        runCurrent()
        assertEquals(loaded, model.settings.value)
        assertNull(model.reparkingTimes.value)

        saved.emit(listOf(street(1, 6)))
        runCurrent()
        assertEquals(listOf(ReparkingTimeChoice(LocalTime.of(6, 0), false)), model.reparkingTimes.value)

        saved.emit(emptyList())
        runCurrent()
        assertEquals(emptyList<ReparkingTimeChoice>(), model.reparkingTimes.value)
    }

    @Test
    fun choicesTrackUniqueSortedSavedEndTimesWithNewTimesUnselected() = runTest {
        val morning = LocalTime.of(6, 0)
        val afternoon = LocalTime.of(14, 0)
        val settings = mock(SettingsRepository::class.java)
        val streets = mock(StreetRepository::class.java)
        val saved = MutableStateFlow(listOf(street(1, 14), street(2, 6), street(3, 14)))
        val preferences = MutableStateFlow(ReminderSettings(preferredReparkingTimes = setOf(afternoon)))
        `when`(streets.observeStreetsWithRules()).thenReturn(saved)
        `when`(settings.settings).thenReturn(preferences)
        val model = SettingsViewModel(settings, streets)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.reparkingTimes.collect() }
        runCurrent()
        assertEquals(listOf(ReparkingTimeChoice(morning, false), ReparkingTimeChoice(afternoon, true)), model.reparkingTimes.value)

        saved.value += street(4, 16)
        runCurrent()
        assertEquals(ReparkingTimeChoice(LocalTime.of(16, 0), false), requireNotNull(model.reparkingTimes.value).last())
        preferences.value = ReminderSettings(preferredReparkingTimes = setOf(morning, afternoon))
        runCurrent()
        assertEquals(2, requireNotNull(model.reparkingTimes.value).count { it.selected })

        saved.value = listOf(street(4, 16))
        runCurrent()
        assertEquals(listOf(ReparkingTimeChoice(LocalTime.of(16, 0), false)), model.reparkingTimes.value)
        saved.value = emptyList()
        runCurrent()
        assertEquals(emptyList<ReparkingTimeChoice>(), model.reparkingTimes.value)
    }
}
