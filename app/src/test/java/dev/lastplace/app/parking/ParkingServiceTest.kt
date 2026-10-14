package dev.lastplace.app.parking

import dev.lastplace.app.data.ParkSource
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.ZoneId
import java.time.ZonedDateTime

class ParkingServiceTest {

    private val streets = mock(StreetRepository::class.java)
    private val parking = mock(ParkingRepository::class.java)
    private val settings = mock(SettingsRepository::class.java)
    private val reminders = mock(ReminderScheduler::class.java)
    private val service = ParkingService(streets, parking, settings, DeadlineCalculator(), reminders)
    private val street = StreetWithRules(
        Street(id = 1, name = "Test street", lat = 59.3, lng = 18.0),
        listOf(CleaningRule(streetId = 1, dayOfWeek = 2, startMinuteOfDay = 9 * 60, endMinuteOfDay = 14 * 60)),
    )

    private fun at(day: Int, hour: Int) =
        ZonedDateTime.of(2026, 5, day, hour, 0, 0, 0, ZoneId.systemDefault()).toInstant()

    @Test
    fun rejectsParkingAtStartAndDuringCleaningWithoutChangingExistingParkingOrReminders() = runTest {
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        assertEquals(-1L, service.park(1, parkedAt = at(26, 9)))
        assertEquals(-1L, service.park(1, parkedAt = at(26, 10)))
        verifyNoInteractions(parking, settings, reminders)
    }

    @Test
    fun rejectsOvernightCleaningOnTheFollowingDay() = runTest {
        val overnight = street.copy(rules = listOf(street.rules.single().copy(
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
        )))
        `when`(streets.getStreetWithRules(1)).thenReturn(overnight)
        assertEquals(-1L, service.park(1, parkedAt = at(27, 5)))
        verifyNoInteractions(parking, settings, reminders)
    }

    @Test
    fun missingStreetDoesNotClearExistingParking() = runTest {
        assertEquals(-1L, service.park(1, parkedAt = at(26, 10)))
        verifyNoInteractions(parking, settings, reminders)
    }

    @Test
    fun allowsParkingAtExactCleaningEnd() = runTest {
        val parkedAt = at(26, 14)
        val deadline = ZonedDateTime.of(2026, 6, 2, 9, 0, 0, 0, ZoneId.systemDefault()).toInstant()
        `when`(streets.getStreetWithRules(1)).thenReturn(street)
        `when`(settings.settings).thenReturn(flowOf(ReminderSettings()))
        `when`(parking.startSession(1, parkedAt, deadline, ParkSource.MANUAL)).thenReturn(42L)
        assertEquals(42L, service.park(1, parkedAt = parkedAt))
    }
}
