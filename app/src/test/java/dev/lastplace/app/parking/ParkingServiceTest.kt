package dev.lastplace.app.parking

import dev.lastplace.app.data.ParkSource
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.db.rulesSignature
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.reminders.ReminderCoordinator
import dev.lastplace.app.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.ZoneId
import java.time.ZonedDateTime

class ParkingServiceTest {
    private val streets = mock(StreetRepository::class.java)
    private val parking = mock(ParkingRepository::class.java)
    private val coordinator = mock(ReminderCoordinator::class.java) { invocation ->
        if (invocation.method.name == "mutateParking") {
            runBlocking { invocation.getArgument<suspend () -> Any?>(0).invoke() }
        } else Answers.RETURNS_DEFAULTS.answer(invocation)
    }
    private val reminders = mock(ReminderScheduler::class.java)
    private val service = ParkingService(streets, parking, DeadlineCalculator(), coordinator, reminders)
    private val street = StreetWithRules(
        Street(id = 1, name = "Test street", lat = 59.3, lng = 18.0),
        listOf(CleaningRule(streetId = 1, dayOfWeek = 2, startMinuteOfDay = 9 * 60, endMinuteOfDay = 14 * 60)),
    )
    private fun at(day: Int, hour: Int) =
        ZonedDateTime.of(2026, 5, day, hour, 0, 0, 0, ZoneId.systemDefault()).toInstant()

    private fun assertNoSessionMutation() {
        assertTrue(mockingDetails(parking).invocations.none { it.method.name in listOf("endSession", "startSession") })
    }

    @Test
    fun rejectsParkingAtStartAndDuringCleaningWithoutChangingExistingParkingOrReminders() = runTest {
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        assertEquals(-1L, service.park(1, parkedAt = at(26, 9)))
        assertEquals(-1L, service.park(1, parkedAt = at(26, 10)))
        verifyNoInteractions(parking, reminders)
    }

    @Test
    fun rejectsOvernightCleaningOnTheFollowingDay() = runTest {
        val overnight = street.copy(rules = listOf(street.rules.single().copy(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)))
        `when`(streets.getStreetWithRules(1)).thenReturn(overnight)
        assertEquals(-1L, service.park(1, parkedAt = at(27, 5)))
        verifyNoInteractions(parking, reminders)
    }

    @Test
    fun missingStreetDoesNotClearExistingParking() = runTest {
        assertEquals(-1L, service.park(1, parkedAt = at(26, 10)))
        verifyNoInteractions(parking, reminders)
    }

    @Test
    fun allowsParkingAtExactCleaningEnd() = runTest {
        val parkedAt = at(26, 14)
        val deadline = ZonedDateTime.of(2026, 6, 2, 9, 0, 0, 0, ZoneId.systemDefault()).toInstant()
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        `when`(parking.startSession(1, parkedAt, deadline, ParkSource.MANUAL)).thenReturn(42L)
        assertEquals(42L, service.park(1, parkedAt = parkedAt))
        verify(parking).startSession(1, parkedAt, deadline, ParkSource.MANUAL)
    }

    @Test
    fun staleMovedNotificationCannotEndNewParkingOrCancelItsReminders() = runTest {
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(8, 2, at(26, 14).toEpochMilli(), null, source = "MANUAL"))
        assertEquals(false, service.endParking(3))
        assertNoSessionMutation()
        verifyNoInteractions(reminders)
    }

    @Test
    fun arrivalRequiresTheSameSessionAndPlanAndChecksActualAvailability() = runTest {
        val session = ParkingSession(3, 2, at(25, 8).toEpochMilli(), at(27, 0).toEpochMilli(), source = "MANUAL")
        val plan = PlannedMove(3, "chosen", 1, "Test", at(26, 14).toEpochMilli(), true, street.rulesSignature())
        `when`(streets.observeStreetsWithRules()).thenReturn(flowOf(listOf(street, street.copy(street = street.street.copy(id = 2), rules = emptyList()))))
        `when`(parking.getActiveSession()).thenReturn(session)
        `when`(coordinator.getPlan(3)).thenReturn(plan)
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        assertEquals(-1L, service.arriveAtPlan(4, "chosen", at(26, 14)))
        assertEquals(-1L, service.arriveAtPlan(3, "stale", at(26, 14)))
        assertEquals(-1L, service.arriveAtPlan(3, "chosen", at(26, 10)))
        verifyNoInteractions(reminders)
        val nextDeadline = at(26, 9).plusSeconds(7 * 24 * 3600)
        `when`(parking.startSession(1, at(26, 14), nextDeadline, ParkSource.MANUAL)).thenReturn(42L)
        assertEquals(42L, service.arriveAtPlan(3, "chosen", at(26, 14)))
        verify(parking).endSession(3, at(26, 14))
    }

    @Test
    fun staleRegistrationCannotReplaceNewerSession() = runTest {
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(8, 2, at(26, 14).toEpochMilli(), null, source = "AUTO"))
        assertEquals(-1L, service.parkIfCurrent(1, null, at(26, 14)))
        assertEquals(-1L, service.parkIfCurrent(1, 3, at(26, 14)))
        assertNoSessionMutation()
        verifyNoInteractions(streets, reminders)
    }

    @Test
    fun currentStreetDoesNotRestartSessionAndHistoricalSourceRemainsOpaque() = runTest {
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(8, 1, at(26, 14).toEpochMilli(), null, source = "AUTO"))
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        assertEquals(8L, service.parkIfCurrent(1, 8, at(26, 15)))
        assertNoSessionMutation()
    }

    @Test
    fun confirmedChangeRechecksAvailability() = runTest {
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(8, 2, at(26, 8).toEpochMilli(), null, source = "MANUAL"))
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        assertEquals(-1L, service.parkIfCurrent(1, 8, at(26, 10)))
        assertNoSessionMutation()
    }

    @Test
    fun deletionContextDoesNotMutateAndStaleConfirmationIsRejected() = runTest {
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        val confirmation = requireNotNull(service.deletionContext(1))
        assertTrue(mockingDetails(streets).invocations.none { it.method.name == "deleteStreet" })
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(9, 1, at(26, 14).toEpochMilli(), null, source = "MANUAL"))
        assertEquals(false, service.deleteStreet(confirmation))
        assertTrue(mockingDetails(streets).invocations.none { it.method.name == "deleteStreet" })
        assertNoSessionMutation()
    }

    @Test
    fun deletingParkedStreetEndsSession() = runTest {
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(9, 1, at(26, 14).toEpochMilli(), null, source = "MANUAL"))
        val confirmation = requireNotNull(service.deletionContext(1))
        assertTrue(confirmation.isParkedStreet)
        assertTrue(service.deleteStreet(confirmation))
        assertTrue(mockingDetails(parking).invocations.any { it.method.name == "endSession" && it.arguments[0] == 9L })
        verify(reminders).cancelLegacy(9)
        verify(streets).deleteStreet(street.street)
    }

    @Test
    fun deletingOnlyDestinationPreservesActiveSession() = runTest {
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        `when`(parking.getActiveSession()).thenReturn(ParkingSession(9, 2, at(26, 14).toEpochMilli(), null, source = "MANUAL"))
        `when`(coordinator.getPlan(9)).thenReturn(PlannedMove(9, "selected", 1, street.street.name, at(26, 14).toEpochMilli(), true, ""))
        val confirmation = requireNotNull(service.deletionContext(1))
        assertTrue(confirmation.isPlannedDestination)
        assertTrue(service.deleteStreet(confirmation))
        assertNoSessionMutation()
        verifyNoInteractions(reminders)
    }
}
