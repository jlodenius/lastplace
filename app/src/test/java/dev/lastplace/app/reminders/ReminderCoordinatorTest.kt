package dev.lastplace.app.reminders

import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.data.db.ReminderDao
import dev.lastplace.app.data.db.ScheduledReminder
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ScheduleEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderCoordinatorTest {
    private val testZone = ZoneId.of("Europe/Stockholm")
    private fun at(day: Int, hour: Int = 0, minute: Int = 0) = ZonedDateTime.of(2026, 5, day, hour, minute, 0, 0, testZone).toInstant()
    private val clock = object : Clock() {
        var now = at(24, 10)
        override fun instant() = now
        override fun getZone(): ZoneId = testZone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private val dao = MemoryReminders()
    private val parking = mock(ParkingRepository::class.java)
    private val streets = mock(StreetRepository::class.java)
    private val settings = mock(SettingsRepository::class.java)
    private val scheduler = mock(ReminderScheduler::class.java)
    private val current = StreetWithRules(
        Street(1, "Oak", 0.0, 0.0),
        listOf(CleaningRule(streetId = 1, dayOfWeek = 3, startMinuteOfDay = 0, endMinuteOfDay = 360)),
    )
    private val target = StreetWithRules(
        Street(2, "Birch", 0.0, 0.0),
        listOf(CleaningRule(streetId = 2, dayOfWeek = 2, startMinuteOfDay = 540, endMinuteOfDay = 840)),
    )
    private val saved = MutableStateFlow(listOf(current, target))
    private val active = MutableStateFlow<ParkingSession?>(ParkingSession(7, 1, at(24, 8).toEpochMilli(), at(27).toEpochMilli(), source = "MANUAL"))
    private val preferences = MutableStateFlow(ReminderSettings(
        deadlineOffsetsMinutes = setOf(2880, 1440), moveOffsetsMinutes = setOf(60, 10, 0),
    ))
    private lateinit var coordinator: ReminderCoordinator
    private fun newCoordinator() = ReminderCoordinator(dao, parking, streets, settings, scheduler, ScheduleEngine(), clock)
    private fun moveEvents() = dao.events.values.filter { it.spec.kind == ReminderKind.PLANNED_MOVE }
    private fun deadlineEvents() = dao.events.values.filter { it.spec.kind == ReminderKind.PARKING_DEADLINE }
    private fun assertNoNotifications() = assertTrue(mockingDetails(scheduler).invocations.none { it.method.name == "show" })

    @Before
    fun setUp() = runTest {
        `when`(parking.getActiveSession()).thenAnswer { active.value }
        `when`(parking.observeActiveSession()).thenReturn(active)
        `when`(streets.observeStreetsWithRules()).thenReturn(saved)
        `when`(settings.settings).thenReturn(preferences)
        coordinator = newCoordinator()
    }

    @Test
    fun selectedPlanSchedulesIndependentMultiOffsetRemindersWithoutMovingCar() = runTest {
        assertTrue(coordinator.selectPlan(7, 2, at(26, 14), true))
        assertEquals(2L, dao.getPlan(7)?.streetId)
        assertEquals(setOf(at(26, 13), at(26, 13, 50), at(26, 14)), moveEvents().map { Instant.ofEpochMilli(it.spec.fireAt) }.toSet())
        assertEquals(setOf(at(25), at(26)), deadlineEvents().map { Instant.ofEpochMilli(it.spec.fireAt) }.toSet())
        assertEquals(5, dao.events.keys.size)
        assertTrue(mockingDetails(parking).invocations.none { it.method.name == "endSession" })
        assertEquals(1L, active.value?.streetId)
        val selectedId = dao.getPlan(7)?.id
        coordinator.selectPlan(7, 2, at(26, 14), true)
        assertEquals(selectedId, dao.getPlan(7)?.id)
    }

    @Test
    fun switchingEitherCategoryOffKeepsThePlanAndTheOtherCategory() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val warnings = deadlineEvents().map { it.key }.toSet()
        preferences.value = preferences.value.copy(moveRemindersEnabled = false)
        coordinator.refresh()
        assertTrue(moveEvents().isEmpty())
        assertEquals(warnings, deadlineEvents().map { it.key }.toSet())
        assertNotNull(dao.getPlan(7))
        preferences.value = preferences.value.copy(moveRemindersEnabled = true, deadlineWarningsEnabled = false)
        coordinator.refresh()
        assertEquals(3, moveEvents().size)
        assertTrue(deadlineEvents().isEmpty())
        preferences.value = preferences.value.copy(moveRemindersEnabled = false)
        coordinator.refresh()
        assertTrue(dao.events.isEmpty())
        assertNotNull(dao.getPlan(7))
    }

    @Test
    fun replacementAndCancellationDoNotCancelDeadlineWarningsOrAcceptStaleActions() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val old = requireNotNull(dao.getPlan(7))
        val oldKey = moveEvents().first().key
        val warnings = deadlineEvents().map { it.key }.toSet()
        saved.value += target.copy(street = target.street.copy(id = 3, name = "Elm"))
        assertTrue(coordinator.selectPlan(7, 3, at(26, 14), true))
        val replacement = requireNotNull(dao.getPlan(7))
        assertNotEquals(old.id, replacement.id)
        assertFalse(coordinator.cancelPlan(7, old.id))
        assertEquals(replacement, dao.getPlan(7))
        coordinator.deliver(oldKey)
        assertNoNotifications()
        assertTrue(coordinator.cancelPlan(7, replacement.id))
        assertNull(dao.getPlan(7))
        assertTrue(moveEvents().isEmpty())
        assertEquals(warnings, deadlineEvents().map { it.key }.toSet())
    }

    @Test
    fun endingParkingClearsPlansAndAllRemindersAndRejectsQueuedAlarms() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val oldKeys = dao.events.keys.toList()
        active.value = null
        coordinator.refresh()
        assertTrue(dao.events.isEmpty())
        assertTrue(dao.plans.value.isEmpty())
        oldKeys.forEach { coordinator.deliver(it) }
        assertNoNotifications()
    }

    @Test
    fun preferencesFavouritesAndNamesCannotSilentlyChangeSelectedDestinationOrTime() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val plan = dao.getPlan(7)
        preferences.value = preferences.value.copy(preferredReparkingTimes = emptySet())
        saved.value = listOf(current, target.copy(street = target.street.copy(name = "Birch renamed", isFavourite = true)))
        coordinator.refresh()
        assertEquals(plan, dao.getPlan(7))
        assertTrue(moveEvents().all { it.spec.streetName == "Birch renamed" })
    }

    @Test
    fun changedOrDeletedDestinationInvalidatesPlanAndCancelsItsNotifications() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val oldKey = moveEvents().first().key
        saved.value = listOf(current, target.copy(rules = target.rules.map { it.copy(endMinuteOfDay = 900) }))
        coordinator.refresh()
        assertNotNull(dao.getPlan(7)?.invalidReason)
        assertTrue(moveEvents().isEmpty())
        assertEquals(2, deadlineEvents().size)
        coordinator.deliver(oldKey)
        assertNoNotifications()
        saved.value = listOf(current, target)
        coordinator.refresh()
        assertTrue(moveEvents().isEmpty())
        assertTrue(coordinator.selectPlan(7, 2, at(26, 14), true))
        saved.value = listOf(current)
        coordinator.refresh()
        assertNotNull(dao.getPlan(7)?.invalidReason)
        assertTrue(moveEvents().isEmpty())
    }

    @Test
    fun changedCurrentScheduleUpdatesDeadlineWarningsAndInvalidatesLatePlan() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        saved.value = listOf(current.copy(rules = listOf(current.rules.single().copy(dayOfWeek = 2, startMinuteOfDay = 720, endMinuteOfDay = 780))), target)
        coordinator.refresh()
        verify(parking).updateDeadline(7, at(26, 12))
        assertNotNull(dao.getPlan(7)?.invalidReason)
        assertTrue(moveEvents().isEmpty())
        assertTrue(deadlineEvents().all { it.spec.targetAt == at(26, 12).toEpochMilli() })
    }

    @Test
    fun deliveredRemindersStayDeliveredAcrossRestartAndDuplicateBroadcasts() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val event = moveEvents().first { it.spec.offsetMinutes == 60L }
        clock.now = at(26, 13)
        coordinator.deliver(event.key)
        coordinator.deliver(event.key)
        coordinator = newCoordinator()
        coordinator.refresh()
        coordinator.deliver(event.key)
        verify(scheduler, times(1)).show(event.spec)
        assertTrue(dao.events.getValue(event.key).delivered)
    }

    @Test
    fun rebootRestoresPendingAlarmsIncludingAnUndeliveredDueReminder() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val event = moveEvents().first { it.spec.offsetMinutes == 10L }
        clock.now = at(26, 13, 51)
        clearInvocations(scheduler)
        coordinator = newCoordinator()
        coordinator.refresh()
        verify(scheduler).schedule(event.spec)
        coordinator.deliver(event.key)
        verify(scheduler).show(event.spec)
    }

    @Test
    fun receiverRechecksPreferencesAndSchedulesEvenWithoutPriorRefresh() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val event = moveEvents().first()
        clock.now = Instant.ofEpochMilli(event.spec.fireAt)
        preferences.value = preferences.value.copy(moveRemindersEnabled = false)
        coordinator.deliver(event.key)
        assertNoNotifications()
        assertFalse(dao.events.containsKey(event.key))
    }

    @Test
    fun selectingLateSkipsPastOffsetsAndChangingOffsetsReschedulesImmediately() = runTest {
        clock.now = at(26, 13, 30)
        coordinator.selectPlan(7, 2, at(26, 14), true)
        assertEquals(setOf(10L, 0L), moveEvents().map { it.spec.offsetMinutes }.toSet())
        preferences.value = preferences.value.copy(moveOffsetsMinutes = setOf(60, 20, 5))
        coordinator.refresh()
        assertEquals(setOf(20L, 5L), moveEvents().map { it.spec.offsetMinutes }.toSet())
        assertEquals(2, dao.events.size)
    }

    @Test
    fun availableNowPlanCanNotifyNowButDoesNotInventFutureTimes() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), false)
        val plan = requireNotNull(dao.getPlan(7))
        assertEquals(clock.now.toEpochMilli(), plan.moveAt)
        assertEquals(setOf(0L), moveEvents().map { it.spec.offsetMinutes }.toSet())
        val event = moveEvents().single()
        coordinator.deliver(event.key)
        verify(scheduler).show(event.spec)
    }

    @Test
    fun rejectsPastInvalidAndWrongSessionSelections() = runTest {
        assertFalse(coordinator.selectPlan(8, 2, at(26, 14), true))
        assertFalse(coordinator.selectPlan(7, 1, at(26, 14), true))
        assertFalse(coordinator.selectPlan(7, 2, at(26, 13), true))
        assertFalse(coordinator.selectPlan(7, 2, at(27, 14), true))
        clock.now = at(26, 14, 1)
        assertFalse(coordinator.selectPlan(7, 2, at(26, 14), true))
        assertTrue(dao.plans.value.isEmpty())
    }

    @Test
    fun overdueParkingCanStillSelectAnAvailableNowFallback() = runTest {
        clock.now = at(27, 1)
        assertTrue(coordinator.selectPlan(7, 2, clock.now, false))
        assertEquals(clock.now.toEpochMilli(), dao.getPlan(7)?.moveAt)
        assertEquals(1, moveEvents().size)
    }

    @Test
    fun earlyAlarmsAreRescheduledAndRemovedOffsetsAreRejectedAtDelivery() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val event = moveEvents().first { it.spec.offsetMinutes == 10L }
        clearInvocations(scheduler)
        coordinator.deliver(event.key)
        assertNoNotifications()
        verify(scheduler).schedule(event.spec)
        preferences.value = preferences.value.copy(moveOffsetsMinutes = setOf(0))
        clock.now = at(26, 13, 50)
        coordinator.deliver(event.key)
        assertNoNotifications()
        assertFalse(dao.events.containsKey(event.key))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun parkingMutationsSharePlanLockAndReconcileBeforeReportingCompletion() = runTest {
        coordinator.refresh()
        val finishMutation = CompletableDeferred<Unit>()
        val mutation = async {
            coordinator.mutateParking {
                finishMutation.await()
                active.value = active.value?.copy(id = 8)
                "changed"
            }
        }
        runCurrent()
        assertTrue(coordinator.readiness.first().updating)
        val oldSessionPlan = async { coordinator.selectPlan(7, 2, at(26, 14), true) }
        runCurrent()
        assertFalse(oldSessionPlan.isCompleted)
        finishMutation.complete(Unit)
        assertEquals("changed", mutation.await())
        assertFalse(oldSessionPlan.await())
        val readiness = coordinator.readiness.first()
        assertFalse(readiness.updating)
        assertEquals(8L, readiness.sessionId)
        assertTrue(readiness.pending.all { it.sessionId == 8L })
    }

    @Test
    fun failedSchedulingCannotPublishReadyFromAStoredButUnsafelyReconciledRecord() = runTest {
        coordinator.refresh()
        val warning = deadlineEvents().first()
        doThrow(IllegalStateException("alarm service unavailable")).`when`(scheduler).schedule(warning.spec)
        assertTrue(runCatching { coordinator.deliver(warning.key) }.isFailure)
        assertTrue(coordinator.readiness.first().updating)
        assertTrue(runCatching { coordinator.refresh() }.isFailure)
        assertTrue(coordinator.readiness.first().updating)
    }

    @Test
    fun readinessUsesStoredPendingWarningsAndDefaultGapIsNotCalledScheduled() = runTest {
        assertTrue(coordinator.readiness.first().updating)
        clock.now = at(26, 22)
        preferences.value = ReminderSettings()
        coordinator.refresh()
        val readiness = coordinator.readiness.first()
        assertFalse(readiness.updating)
        assertTrue(readiness.pending.isEmpty())
        assertEquals(ReminderSchedulingState.NO_UPCOMING, readiness.status(ReminderKind.PARKING_DEADLINE, clock.now).state)
        assertTrue(dao.events.isEmpty())
        preferences.value = preferences.value.copy(deadlineOffsetsMinutes = setOf(60))
        assertTrue(coordinator.readiness.first().updating)
        coordinator.refresh()
        val scheduled = coordinator.readiness.first()
        assertEquals(deadlineEvents().map { it.spec }, scheduled.pending)
        assertEquals(ReminderSchedulingState.SCHEDULED, scheduled.status(ReminderKind.PARKING_DEADLINE, clock.now).state)
    }

    @Test
    fun readinessExcludesDeliveredStaleCancelledAndSkippedRecords() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val delivered = deadlineEvents().first()
        clock.now = Instant.ofEpochMilli(delivered.spec.fireAt)
        coordinator.deliver(delivered.key)
        assertFalse(coordinator.readiness.first().pending.any { it.key == delivered.key })
        val stale = delivered.spec.copy(sessionId = 99)
        dao.saveReminder(ScheduledReminder(stale.key, stale))
        coordinator.refresh()
        assertFalse(coordinator.readiness.first().pending.any { it.sessionId == 99L })
        val plan = requireNotNull(dao.getPlan(7))
        coordinator.cancelPlan(7, plan.id)
        val cancelled = coordinator.readiness.first()
        assertFalse(cancelled.pending.any { it.kind == ReminderKind.PLANNED_MOVE })
        assertEquals(ReminderSchedulingState.NO_TARGET, cancelled.status(ReminderKind.PLANNED_MOVE, clock.now).state)
        assertEquals(1, cancelled.pending.size)
    }

    @Test
    fun readinessBecomesUpdatingForChangedSessionSettingsPlanAndStreetInputs() = runTest {
        coordinator.refresh()
        assertFalse(coordinator.readiness.first().updating)
        preferences.value = preferences.value.copy(deadlineOffsetsMinutes = emptySet())
        assertTrue(coordinator.readiness.first().updating)
        coordinator.refresh()
        assertEquals(ReminderSchedulingState.NO_TIMES, coordinator.readiness.first().status(ReminderKind.PARKING_DEADLINE, clock.now).state)
        preferences.value = preferences.value.copy(deadlineWarningsEnabled = false)
        coordinator.refresh()
        assertEquals(ReminderSchedulingState.OFF, coordinator.readiness.first().status(ReminderKind.PARKING_DEADLINE, clock.now).state)
        saved.value = listOf(current.copy(street = current.street.copy(name = "Oak section")), target)
        assertTrue(coordinator.readiness.first().updating)
        coordinator.refresh()
        assertFalse(coordinator.readiness.first().updating)
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val selected = requireNotNull(dao.getPlan(7))
        dao.savePlan(selected.copy(id = "replacement"))
        assertTrue(coordinator.readiness.first().updating)
        coordinator.refresh()
        active.value = active.value?.copy(id = 8)
        val newerSession = coordinator.readiness.first()
        assertTrue(newerSession.updating)
        assertEquals(8L, newerSession.sessionId)
        assertTrue(newerSession.pending.isEmpty())
        coordinator.refresh()
        assertFalse(coordinator.readiness.first().updating)
    }

    @Test
    fun pendingWarningsAndAtTimeMoveRemindersBecomeDueNotFutureWithoutNewAlarms() = runTest {
        preferences.value = preferences.value.copy(moveOffsetsMinutes = setOf(0))
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val readiness = coordinator.readiness.first()
        val move = moveEvents().single()
        assertEquals(ReminderSchedulingState.SCHEDULED, readiness.status(ReminderKind.PLANNED_MOVE, at(26, 13, 59)).state)
        assertEquals(move.spec, readiness.status(ReminderKind.PLANNED_MOVE, at(26, 14)).reminder)
        assertEquals(ReminderSchedulingState.DUE, readiness.status(ReminderKind.PLANNED_MOVE, at(26, 14)).state)
        val warning = deadlineEvents().first()
        assertEquals(ReminderSchedulingState.DUE, readiness.status(ReminderKind.PARKING_DEADLINE, Instant.ofEpochMilli(warning.spec.fireAt)).state)
        clock.now = at(26, 14)
        coordinator.deliver(move.key)
        assertEquals(ReminderSchedulingState.NO_UPCOMING, coordinator.readiness.first().status(ReminderKind.PLANNED_MOVE, clock.now).state)
    }

    @Test
    fun invalidPlanReadinessDoesNotHideIndependentDeadlineRecords() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        saved.value = listOf(current)
        assertTrue(coordinator.readiness.first().updating)
        coordinator.refresh()
        val readiness = coordinator.readiness.first()
        assertFalse(readiness.updating)
        assertEquals(ReminderSchedulingState.CANCELLED, readiness.status(ReminderKind.PLANNED_MOVE, clock.now).state)
        assertEquals(ReminderSchedulingState.SCHEDULED, readiness.status(ReminderKind.PARKING_DEADLINE, clock.now).state)
        assertTrue(readiness.pending.all { it.kind == ReminderKind.PARKING_DEADLINE })
    }

    @Test
    fun veryLatePlanReminderIsNotDeliveredAndDoesNotClearParking() = runTest {
        coordinator.selectPlan(7, 2, at(26, 14), true)
        val event = moveEvents().first { it.spec.offsetMinutes == 0L }
        clock.now = at(26, 16)
        val readiness = coordinator.readiness.first()
        assertEquals(ReminderSchedulingState.DUE, readiness.status(ReminderKind.PLANNED_MOVE, at(26, 14, 15)).state)
        assertEquals(ReminderSchedulingState.NO_UPCOMING, readiness.status(ReminderKind.PLANNED_MOVE, at(26, 14, 16)).state)
        coordinator.deliver(event.key)
        assertNoNotifications()
        assertNotNull(active.value)
        assertNotNull(dao.getPlan(7))
        assertTrue(dao.events.getValue(event.key).delivered)
    }
}

private class MemoryReminders : ReminderDao {
    val plans = MutableStateFlow<List<PlannedMove>>(emptyList())
    val events = linkedMapOf<String, ScheduledReminder>()
    override fun observePlans() = plans
    override suspend fun getPlan(sessionId: Long) = plans.value.firstOrNull { it.sessionId == sessionId }
    override suspend fun savePlan(plan: PlannedMove) { plans.value = plans.value.filterNot { it.sessionId == plan.sessionId } + plan }
    override suspend fun deletePlan(sessionId: Long, planId: String) {
        plans.value = plans.value.filterNot { it.sessionId == sessionId && it.id == planId }
    }
    override suspend fun deleteOtherPlans(sessionId: Long) { plans.value = plans.value.filter { it.sessionId == sessionId } }
    override suspend fun getReminders() = events.values.toList()
    override suspend fun saveReminder(reminder: ScheduledReminder) { events[reminder.key] = reminder }
    override suspend fun deleteReminder(key: String) { events.remove(key) }
}
