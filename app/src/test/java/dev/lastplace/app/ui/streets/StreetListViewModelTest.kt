package dev.lastplace.app.ui.streets

import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.data.db.rulesSignature
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.parking.ParkingService
import dev.lastplace.app.reminders.ReminderCoordinator
import dev.lastplace.app.reminders.ReminderReadiness
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
import org.mockito.Mockito.mockingDetails
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
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
    private val reminders = mock(ReminderCoordinator::class.java)
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
        `when`(reminders.plans).thenReturn(flowOf(emptyList()))
        `when`(reminders.readiness).thenReturn(flowOf(ReminderReadiness()))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel() = StreetListViewModel(
        streets, parking, service, ScheduleEngine(), StreetMatcher(), location, settings, reminders, clock,
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
    fun selectedPlanStaysVisibleWhenMissedAndReportsScheduleChangesWithoutMovingCar() = runTest {
        val session = ParkingSession(3, 2, at(8).toEpochMilli(), at(0).plusSeconds(86400).toEpochMilli(), source = "MANUAL")
        val active = MutableStateFlow<ParkingSession?>(session)
        val saved = MutableStateFlow(listOf(dayStreet, nightStreet))
        val plan = PlannedMove(3, "selected", 1, dayStreet.street.name, at(14).toEpochMilli(), true, dayStreet.rulesSignature())
        `when`(parking.observeActiveSession()).thenReturn(active)
        `when`(streets.observeStreetsWithRules()).thenReturn(saved)
        `when`(reminders.plans).thenReturn(flowOf(listOf(plan)))
        val preferences = ReminderSettings(moveRemindersEnabled = false, deadlineWarningsEnabled = false)
        `when`(settings.settings).thenReturn(flowOf(preferences))
        `when`(reminders.readiness).thenReturn(flowOf(ReminderReadiness(
            sessionId = session.id, planId = plan.id, updating = false,
            settings = preferences, deadline = session.deadline,
        )))
        val model = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertEquals("selected", model.uiState.value.plannedMove?.plan?.id)
        assertEquals("Planned move reminders off", model.uiState.value.plannedMove?.remindersText)
        assertFalse(model.uiState.value.deadlineWarningsEnabled)
        assertEquals("Night street", model.uiState.value.active?.streetName)

        `when`(clock.instant()).thenReturn(at(14, 2))
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(model.uiState.value.plannedMove!!.timePassed)
        assertEquals("selected", model.uiState.value.plannedMove?.plan?.id)
        saved.value = listOf(nightStreet, dayStreet.copy(rules = dayStreet.rules.map { it.copy(endMinuteOfDay = 16 * 60) }))
        runCurrent()
        assertTrue(model.uiState.value.plannedMove!!.problem!!.contains("schedule changed"))
        active.value = null
        runCurrent()
        assertEquals(null, model.uiState.value.plannedMove)
    }

    @Test
    fun plannedMoveShowsActualAtTimeReminderAndNeverGuessesFromOffsets() = runTest {
        val session = ParkingSession(3, 2, at(8).toEpochMilli(), at(0).plusSeconds(86400).toEpochMilli(), source = "MANUAL")
        val plan = PlannedMove(3, "selected", 1, dayStreet.street.name, at(14).toEpochMilli(), true, dayStreet.rulesSignature())
        val preferences = MutableStateFlow(ReminderSettings(moveOffsetsMinutes = setOf(60, 10, 0)))
        `when`(parking.observeActiveSession()).thenReturn(flowOf(session))
        `when`(reminders.plans).thenReturn(flowOf(listOf(plan)))
        `when`(settings.settings).thenReturn(preferences)
        val reconciled = MutableStateFlow(ReminderReadiness(
            sessionId = session.id, planId = plan.id, updating = false,
            settings = preferences.value, deadline = session.deadline,
            pending = listOf(ReminderSpec(3, ReminderKind.PLANNED_MOVE, plan.moveAt, plan.moveAt, 0, plan.streetName, plan.id)),
        ))
        `when`(reminders.readiness).thenReturn(reconciled)
        val model = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertTrue(model.uiState.value.plannedMove!!.remindersText!!.startsWith("Planned move reminder scheduled:"))
        assertEquals(setOf(60L, 10L, 0L), preferences.value.moveOffsetsMinutes)

        `when`(clock.instant()).thenReturn(at(14, 2))
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(model.uiState.value.plannedMove!!.remindersText!!.contains("due or delayed"))
        reconciled.value = reconciled.value.copy(pending = emptyList())
        runCurrent()
        assertEquals("No upcoming plan reminders", model.uiState.value.plannedMove?.remindersText)

        preferences.value = preferences.value.copy(moveRemindersEnabled = false)
        runCurrent()
        assertEquals("Updating reminder status…", model.uiState.value.plannedMove?.remindersText)
        reconciled.value = reconciled.value.copy(settings = preferences.value)
        runCurrent()
        assertEquals("Planned move reminders off", model.uiState.value.plannedMove?.remindersText)
    }

    @Test
    fun startsLoadingAndRefreshesUrgencyImmediatelyOnResume() = runTest {
        val session = ParkingSession(3, 1, at(6).toEpochMilli(), at(9).toEpochMilli(), source = "MANUAL")
        `when`(parking.observeActiveSession()).thenReturn(flowOf(session))
        `when`(clock.instant()).thenReturn(at(6, 59))
        val model = viewModel()
        assertTrue(model.uiState.value.isLoading)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertFalse(model.uiState.value.isLoading)
        assertEquals(ParkingUrgency.Normal, model.uiState.value.active?.urgency)
        `when`(clock.instant()).thenReturn(at(7))
        model.refresh()
        runCurrent()
        assertEquals(ParkingUrgency.Soon, model.uiState.value.active?.urgency)
        `when`(clock.instant()).thenReturn(at(8, 59, 45))
        model.refresh()
        runCurrent()
        assertEquals("Less than a minute remaining", model.uiState.value.active?.remainingText)
        `when`(clock.instant()).thenReturn(at(9))
        model.refresh()
        runCurrent()
        assertEquals(ParkingUrgency.Overdue, model.uiState.value.active?.urgency)
    }

    @Test
    fun liveCountdownEntersSubMinuteStateWithoutAnUnrelatedEvent() = runTest {
        val session = ParkingSession(3, 1, at(8).toEpochMilli(), at(9).toEpochMilli(), source = "MANUAL")
        `when`(parking.observeActiveSession()).thenReturn(flowOf(session))
        `when`(clock.instant()).thenReturn(at(8, 59))
        val model = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertEquals("1 minute remaining", model.uiState.value.active?.remainingText)
        `when`(clock.instant()).thenReturn(at(8, 59, 1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Less than a minute remaining", model.uiState.value.active?.remainingText)
        `when`(clock.instant()).thenReturn(at(9))
        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(ParkingUrgency.Overdue, model.uiState.value.active?.urgency)
    }

    @Test
    fun repeatedRegistrationTapsAreIgnoredWhileMutationIsRunning() = runTest {
        var pending: Continuation<Long>? = null
        `when`(service.parkIfCurrent(1, null, at(8, 59, 59))).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<Long>
            pending = continuation
            COROUTINE_SUSPENDED
        }
        `when`(streets.getStreetWithRules(1)).thenReturn(dayStreet)
        val model = viewModel()
        val first = model.park(1)
        model.park(1).join()
        assertTrue(model.mutationRunning.value)
        assertEquals(1, mockingDetails(service).invocations.count { it.method.name == "parkIfCurrent" })
        requireNotNull(pending).resume(42)
        first.join()
        assertFalse(model.mutationRunning.value)
        assertTrue(model.notificationPrompt.value)
        assertEquals("Parked on Day street", model.message.value)
    }

    @Test
    fun rejectedGpsParkingShowsUnavailabilityInsteadOfSuccess() = runTest {
        `when`(location.hasPermission()).thenReturn(true)
        `when`(location.current()).thenReturn(LatLng(59.3, 18.0))
        `when`(clock.instant()).thenReturn(at(10))
        `when`(service.parkIfCurrent(1, null, at(10))).thenReturn(-1L)
        val model = viewModel()
        model.parkWhereIAm().join()
        assertTrue(model.actionError.value!!.contains("unavailable"))
        assertEquals(null, model.message.value)
        assertFalse(model.parkLoading.value)
    }
}
