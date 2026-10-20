package dev.lastplace.app.parking

import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.data.db.ReminderDao
import dev.lastplace.app.data.db.ScheduledReminder
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.reminders.ReminderCoordinator
import dev.lastplace.app.reminders.ReminderScheduler
import dev.lastplace.app.reminders.ReminderSchedulingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ParkingMutationIntegrationTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: Int, hour: Int = 0, minute: Int = 0): Instant =
        ZonedDateTime.of(2026, 5, day, hour, minute, 0, 0, zone).toInstant()

    private val clock = Clock.fixed(at(24, 10), zone)
    private val engine = ScheduleEngine()
    private val current = StreetWithRules(
        Street(id = 1, name = "Ynglingagatan", lat = 59.34, lng = 18.05),
        listOf(CleaningRule(streetId = 1, dayOfWeek = 3, startMinuteOfDay = 0, endMinuteOfDay = 360)),
    )
    private val destination = StreetWithRules(
        Street(id = 2, name = "Ynglingagatan vändplan", lat = 59.34, lng = 18.05),
        listOf(CleaningRule(streetId = 2, dayOfWeek = 2, startMinuteOfDay = 540, endMinuteOfDay = 840)),
    )
    private val saved = MutableStateFlow(listOf(current, destination))
    private val initialSession = ParkingSession(
        id = 7, streetId = 1, parkedAt = at(24, 8).toEpochMilli(),
        deadline = at(27).toEpochMilli(), source = "MANUAL",
    )
    private val active = MutableStateFlow<ParkingSession?>(initialSession)
    private val preferences = MutableStateFlow(ReminderSettings(
        deadlineOffsetsMinutes = setOf(2880, 1440),
        moveOffsetsMinutes = setOf(60, 10, 0),
    ))
    private val alarms = linkedMapOf<String, ReminderSpec>()
    private val shown = mutableListOf<ReminderSpec>()
    private val endedSessions = mutableListOf<Long>()
    private var nextSessionId = 8L
    private val dao = MutationMemoryReminders()
    private val streets = mock(StreetRepository::class.java) { invocation ->
        when (invocation.method.name) {
            "observeStreetsWithRules" -> saved
            "getStreetWithRules" -> saved.value.firstOrNull { it.street.id == invocation.getArgument<Long>(0) }
            "deleteStreet" -> {
                val street = invocation.getArgument<Street>(0)
                saved.value = saved.value.filterNot { it.street.id == street.id }
                Unit
            }
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val parking = mock(ParkingRepository::class.java) { invocation ->
        when (invocation.method.name) {
            "observeActiveSession" -> active
            "getActiveSession" -> active.value
            "updateDeadline" -> {
                val id = invocation.getArgument<Long>(0)
                val deadline = invocation.getArgument<Instant?>(1)?.toEpochMilli()
                active.value = active.value?.let { if (it.id == id) it.copy(deadline = deadline) else it }
                Unit
            }
            "endSession" -> {
                val id = invocation.getArgument<Long>(0)
                endedSessions += id
                if (active.value?.id == id) active.value = null
                Unit
            }
            "startSession" -> {
                val id = nextSessionId++
                active.value = ParkingSession(
                    id = id,
                    streetId = invocation.getArgument(0),
                    parkedAt = invocation.getArgument<Instant>(1).toEpochMilli(),
                    deadline = invocation.getArgument<Instant?>(2)?.toEpochMilli(),
                    source = invocation.getArgument<dev.lastplace.app.data.ParkSource>(3).name,
                )
                id
            }
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val settings = mock(SettingsRepository::class.java) { invocation ->
        if (invocation.method.name == "getSettings") preferences else Answers.RETURNS_DEFAULTS.answer(invocation)
    }
    private val scheduler = mock(ReminderScheduler::class.java) { invocation ->
        when (invocation.method.name) {
            "schedule" -> {
                val spec = invocation.getArgument<ReminderSpec>(0)
                alarms[spec.key] = spec
                null
            }
            "cancel" -> {
                alarms.remove(invocation.getArgument<ReminderSpec>(0).key)
                null
            }
            "show" -> {
                shown += invocation.getArgument<ReminderSpec>(0)
                null
            }
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    }
    private val coordinator = ReminderCoordinator(dao, parking, streets, settings, scheduler, engine, clock)
    private val service = ParkingService(streets, parking, DeadlineCalculator(engine), coordinator, scheduler)

    private suspend fun selectMove(): PlannedMove {
        assertTrue(coordinator.selectPlan(7, 2, at(26, 14), justCleaned = true))
        assertEquals(2, alarms.values.count { it.kind == ReminderKind.PARKING_DEADLINE })
        assertEquals(3, alarms.values.count { it.kind == ReminderKind.PLANNED_MOVE })
        return requireNotNull(coordinator.getPlan(7))
    }

    @Test
    fun deletingParkedStreetClearsSessionPlanAndBothAlarmGroupsBeforeReturning() = runTest {
        selectMove()
        val staleKeys = alarms.keys.toList()
        val confirmation = requireNotNull(service.deletionContext(1))
        assertTrue(confirmation.isParkedStreet)
        assertTrue(service.deleteStreet(confirmation))

        assertNull(parking.getActiveSession())
        assertEquals(listOf(7L), endedSessions)
        assertNull(coordinator.getPlan(7))
        assertNull(streets.getStreetWithRules(1))
        assertEquals(destination, streets.getStreetWithRules(2))
        assertTrue(dao.getReminders().isEmpty())
        assertTrue(alarms.isEmpty())
        val readiness = coordinator.readiness.first()
        assertFalse(readiness.updating)
        assertNull(readiness.sessionId)
        assertTrue(readiness.pending.isEmpty())

        staleKeys.forEach { coordinator.deliver(it) }
        assertTrue(shown.isEmpty())
        assertTrue(alarms.isEmpty())
    }

    @Test
    fun deletingOnlyDestinationKeepsParkingAndDeadlineAlarmsButInvalidatesSelectedMove() = runTest {
        val selected = selectMove()
        val warnings = alarms.filterValues { it.kind == ReminderKind.PARKING_DEADLINE }
        val oldMoveKeys = alarms.values.filter { it.kind == ReminderKind.PLANNED_MOVE }.map { it.key }
        val confirmation = requireNotNull(service.deletionContext(2))
        assertFalse(confirmation.isParkedStreet)
        assertTrue(confirmation.isPlannedDestination)
        assertTrue(service.deleteStreet(confirmation))

        assertEquals(initialSession, parking.getActiveSession())
        assertTrue(endedSessions.isEmpty())
        assertNull(streets.getStreetWithRules(2))
        assertEquals(current, streets.getStreetWithRules(1))
        val invalid = requireNotNull(coordinator.getPlan(7))
        assertEquals(selected.id, invalid.id)
        assertEquals(selected.moveAt, invalid.moveAt)
        assertNotNull(invalid.invalidReason)
        assertTrue(invalid.invalidReason!!.contains("removed"))
        assertEquals(warnings, alarms)
        assertEquals(warnings.keys, dao.getReminders().map { it.key }.toSet())
        val readiness = coordinator.readiness.first()
        assertFalse(readiness.updating)
        assertEquals(ReminderSchedulingState.SCHEDULED, readiness.status(ReminderKind.PARKING_DEADLINE, clock.instant()).state)
        assertEquals(ReminderSchedulingState.CANCELLED, readiness.status(ReminderKind.PLANNED_MOVE, clock.instant()).state)

        oldMoveKeys.forEach { coordinator.deliver(it) }
        assertTrue(shown.isEmpty())
        assertEquals(warnings, alarms)
        assertEquals(initialSession, parking.getActiveSession())
    }

    @Test
    fun arrivalRejectsChangedDestinationHoursBeforeRefreshEvenWhenStreetIsAvailableNow() = runTest {
        val selected = selectMove()
        val warnings = alarms.filterValues { it.kind == ReminderKind.PARKING_DEADLINE }
        val changed = destination.copy(rules = destination.rules.map { it.copy(endMinuteOfDay = 900) })
        saved.value = listOf(current, changed)
        assertTrue(engine.availability(changed.rules.map { it.toWindow() }, clock.instant().atZone(zone)).isAvailable)
        assertNull(coordinator.getPlan(7)?.invalidReason)

        assertEquals(-1L, service.arriveAtPlan(7, selected.id, clock.instant()))

        assertEquals(initialSession, parking.getActiveSession())
        assertTrue(endedSessions.isEmpty())
        val invalid = requireNotNull(coordinator.getPlan(7))
        assertEquals(selected.id, invalid.id)
        assertTrue(requireNotNull(invalid.invalidReason).contains("schedule changed"))
        assertEquals(warnings, alarms)
        assertEquals(warnings.keys, dao.getReminders().map { it.key }.toSet())
        assertTrue(shown.isEmpty())
    }
}

private class MutationMemoryReminders : ReminderDao {
    private val plans = MutableStateFlow<List<PlannedMove>>(emptyList())
    private val reminders = linkedMapOf<String, ScheduledReminder>()

    override fun observePlans() = plans
    override suspend fun getPlan(sessionId: Long) = plans.value.firstOrNull { it.sessionId == sessionId }
    override suspend fun savePlan(plan: PlannedMove) {
        plans.value = plans.value.filterNot { it.sessionId == plan.sessionId } + plan
    }
    override suspend fun deletePlan(sessionId: Long, planId: String) {
        plans.value = plans.value.filterNot { it.sessionId == sessionId && it.id == planId }
    }
    override suspend fun deleteOtherPlans(sessionId: Long) {
        plans.value = plans.value.filter { it.sessionId == sessionId }
    }
    override suspend fun getReminders() = reminders.values.toList()
    override suspend fun saveReminder(reminder: ScheduledReminder) {
        reminders[reminder.key] = reminder
    }
    override suspend fun deleteReminder(key: String) {
        reminders.remove(key)
    }
}
