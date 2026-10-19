package dev.lastplace.app.ui.streets

import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.parking.ParkingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class StreetListViewModelTest {

    private val streets = mock(StreetRepository::class.java)
    private val parking = mock(ParkingRepository::class.java)
    private val service = mock(ParkingService::class.java)
    private val location = mock(LocationProvider::class.java)
    private val clock = mock(Clock::class.java)
    private val settings = mock(SettingsRepository::class.java)
    private val zone = ZoneId.of("Europe/Stockholm")
    private val dayStreet = StreetWithRules(
        Street(id = 1, name = "Day street", lat = 59.3, lng = 18.0),
        listOf(CleaningRule(streetId = 1, dayOfWeek = 2, startMinuteOfDay = 9 * 60, endMinuteOfDay = 14 * 60)),
    )
    private val nightStreet = StreetWithRules(
        Street(id = 2, name = "Night street", lat = 59.4, lng = 18.0),
        listOf(CleaningRule(streetId = 2, dayOfWeek = 3, startMinuteOfDay = 0, endMinuteOfDay = 6 * 60)),
    )

    private fun at(hour: Int, minute: Int = 0, second: Int = 0) =
        ZonedDateTime.of(2026, 5, 26, hour, minute, second, 0, zone).toInstant()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        `when`(clock.zone).thenReturn(zone)
        `when`(clock.instant()).thenReturn(at(8, 59, 59))
        `when`(streets.observeStreetsWithRules()).thenReturn(flowOf(listOf(dayStreet, nightStreet)))
        `when`(parking.observeActiveSession()).thenReturn(flowOf(null))
        `when`(settings.settings).thenReturn(flowOf(ReminderSettings()))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel() = StreetListViewModel(
        streets, parking, service, ScheduleEngine(), StreetMatcher(), location, settings, clock,
    )

    @Test
    fun availabilityRefreshesAtMinuteBoundariesWithoutDatabaseChanges() = runTest {
        val model = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertTrue(model.uiState.value.streets.first { it.id == 1L }.isAvailable)

        `when`(clock.instant()).thenReturn(at(9))
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(model.uiState.value.streets.first { it.id == 1L }.isAvailable)

        `when`(clock.instant()).thenReturn(at(14))
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(model.uiState.value.streets.first { it.id == 1L }.isAvailable)
    }

    @Test
    fun filterKeepsCurrentParkingVisibleEvenWhenItsStreetHasNighttimeCleaning() = runTest {
        val active = ParkingSession(id = 3, streetId = 2, parkedAt = at(8).toEpochMilli(), deadline = null, source = "MANUAL")
        `when`(parking.observeActiveSession()).thenReturn(flowOf(active))
        val model = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertEquals(2, model.uiState.value.streets.size)

        model.showDaytimeOnly(true)
        runCurrent()
        assertTrue(model.uiState.value.daytimeOnly)
        assertEquals(listOf(1L), model.uiState.value.streets.map { it.id })
        assertEquals("Night street", model.uiState.value.active?.streetName)

        model.showDaytimeOnly(false)
        runCurrent()
        assertEquals(2, model.uiState.value.streets.size)
    }

    @Test
    fun recommendationsReactToParkingPreferencesSchedulesFavouritesAndTime() = runTest {
        val savedStreets = MutableStateFlow(listOf(dayStreet, nightStreet))
        val preferences = MutableStateFlow(ReminderSettings())
        val session = ParkingSession(
            id = 3, streetId = 2, parkedAt = at(8).toEpochMilli(),
            deadline = at(0).plusSeconds(24 * 3600).toEpochMilli(), source = "MANUAL",
        )
        val active = MutableStateFlow<ParkingSession?>(null)
        `when`(streets.observeStreetsWithRules()).thenReturn(savedStreets)
        `when`(settings.settings).thenReturn(preferences)
        `when`(parking.observeActiveSession()).thenReturn(active)
        val model = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertTrue(model.uiState.value.reparkingOptions.isEmpty())

        active.value = session
        runCurrent()
        assertFalse(model.uiState.value.reparkingOptions.single().justCleaned)
        preferences.value = ReminderSettings(preferredReparkingTimes = setOf(LocalTime.of(14, 0)))
        runCurrent()
        assertTrue(model.uiState.value.reparkingOptions.single().justCleaned)

        savedStreets.value += dayStreet.copy(street = dayStreet.street.copy(id = 4, name = "Favourite", isFavourite = true))
        runCurrent()
        assertEquals(listOf(4L, 1L), model.uiState.value.reparkingOptions.map { it.street.id })
        assertEquals(listOf(2L, 4L, 1L), model.uiState.value.streets.map { it.id })

        model.showDaytimeOnly(true)
        runCurrent()
        assertEquals(2, model.uiState.value.reparkingOptions.size)
        savedStreets.value = listOf(nightStreet, dayStreet.copy(rules = dayStreet.rules.map { it.copy(dayOfWeek = 5) }))
        runCurrent()
        assertFalse(model.uiState.value.reparkingOptions.single().justCleaned)

        savedStreets.value = listOf(dayStreet, nightStreet)
        `when`(clock.instant()).thenReturn(at(14, 1))
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(model.uiState.value.reparkingOptions.single().justCleaned)
        active.value = null
        runCurrent()
        assertTrue(model.uiState.value.reparkingOptions.isEmpty())
    }

    @Test
    fun rejectedGpsParkingShowsUnavailabilityInsteadOfSuccess() = runTest {
        `when`(location.hasPermission()).thenReturn(true)
        `when`(location.current()).thenReturn(LatLng(59.3, 18.0))
        `when`(clock.instant()).thenReturn(at(10))
        `when`(service.park(1, parkedAt = at(10))).thenReturn(-1L)
        val model = viewModel()
        model.parkWhereIAm().join()
        assertEquals("Day street isn't available to park on right now", model.message.value)
        assertFalse(model.parkLoading.value)
    }
}
