package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleEngineTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val engine = ScheduleEngine()

    // 2026-05-25 is a Monday; 2026-05-26 a Tuesday; 2026-06-02 the next Tuesday.
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi), zone)

    private val tue0814 = CleaningWindow(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(14, 0))

    @Test
    fun returnsNextWindowLaterThisWeek() {
        val next = engine.nextWindowStart(listOf(tue0814), at(2026, 5, 25, 10, 0))
        assertEquals(at(2026, 5, 26, 8, 0), next)
    }

    @Test
    fun insideActiveWindowReturnsCurrentStartInsteadOfFreeTimeUntilNextWeek() {
        val next = engine.nextWindowStart(listOf(tue0814), at(2026, 5, 26, 9, 0))
        assertEquals(at(2026, 5, 26, 8, 0), next)
    }

    @Test
    fun afterTodaysWindowClosesReturnsNextWeek() {
        val next = engine.nextWindowStart(listOf(tue0814), at(2026, 5, 26, 15, 0))
        assertEquals(at(2026, 6, 2, 8, 0), next)
    }

    @Test
    fun beforeTodaysWindowReturnsToday() {
        val next = engine.nextWindowStart(listOf(tue0814), at(2026, 5, 26, 6, 0))
        assertEquals(at(2026, 5, 26, 8, 0), next)
    }

    @Test
    fun picksEarliestAcrossMultipleWindows() {
        val thu = CleaningWindow(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(10, 0))
        val next = engine.nextWindowStart(listOf(thu, tue0814), at(2026, 5, 25, 10, 0))
        assertEquals(at(2026, 5, 26, 8, 0), next)
    }

    @Test
    fun emptyWindowsReturnsNull() {
        assertNull(engine.nextWindowStart(emptyList(), at(2026, 5, 25, 10, 0)))
        assertTrue(engine.availability(emptyList(), at(2026, 5, 25, 10, 0)).isAvailable)
    }

    @Test
    fun unavailableFromExactStartUntilJustBeforeEnd() {
        for (now in listOf(at(2026, 5, 26, 8, 0), at(2026, 5, 26, 13, 59))) {
            val availability = engine.availability(listOf(tue0814), now)
            assertFalse(availability.isAvailable)
            assertEquals(at(2026, 5, 26, 14, 0), availability.availableAt)
            assertEquals(at(2026, 5, 26, 8, 0), availability.nextCleaningStart)
        }
    }

    @Test
    fun availableAtExactEndWithNextWeeksDeadline() {
        val availability = engine.availability(listOf(tue0814), at(2026, 5, 26, 14, 0))
        assertTrue(availability.isAvailable)
        assertNull(availability.availableAt)
        assertEquals(at(2026, 6, 2, 8, 0), availability.nextCleaningStart)
    }

    @Test
    fun availableBeforeCleaningStarts() {
        val availability = engine.availability(listOf(tue0814), at(2026, 5, 26, 7, 59))
        assertTrue(availability.isAvailable)
        assertEquals(at(2026, 5, 26, 8, 0), availability.nextCleaningStart)
    }

    @Test
    fun overnightCleaningBlocksParkingOnBothDays() {
        val overnight = CleaningWindow(DayOfWeek.TUESDAY, LocalTime.of(22, 0), LocalTime.of(6, 0))
        for (now in listOf(at(2026, 5, 26, 23, 0), at(2026, 5, 27, 5, 59))) {
            val availability = engine.availability(listOf(overnight), now)
            assertFalse(availability.isAvailable)
            assertEquals(at(2026, 5, 27, 6, 0), availability.availableAt)
            assertEquals(at(2026, 5, 26, 22, 0), availability.nextCleaningStart)
        }
        assertTrue(engine.availability(listOf(overnight), at(2026, 5, 27, 6, 0)).isAvailable)
    }

    @Test
    fun overnightCleaningCrossesWeekBoundary() {
        val sunday = CleaningWindow(DayOfWeek.SUNDAY, LocalTime.of(22, 0), LocalTime.of(6, 0))
        val availability = engine.availability(listOf(sunday), at(2026, 5, 25, 1, 0))
        assertFalse(availability.isAvailable)
        assertEquals(at(2026, 5, 25, 6, 0), availability.availableAt)
    }

    @Test
    fun overlappingAndAdjacentWindowsStayUnavailableUntilAllCleaningEnds() {
        val overlap = CleaningWindow(DayOfWeek.TUESDAY, LocalTime.of(13, 0), LocalTime.of(15, 0))
        val adjacent = CleaningWindow(DayOfWeek.TUESDAY, LocalTime.of(15, 0), LocalTime.of(16, 0))
        val later = CleaningWindow(DayOfWeek.TUESDAY, LocalTime.of(17, 0), LocalTime.of(18, 0))
        val windows = listOf(later, adjacent, overlap, tue0814)
        val availability = engine.availability(windows, at(2026, 5, 26, 9, 0))
        assertFalse(availability.isAvailable)
        assertEquals(at(2026, 5, 26, 16, 0), availability.availableAt)
        assertTrue(engine.availability(windows, at(2026, 5, 26, 16, 0)).isAvailable)
    }

    @Test
    fun equalStartAndEndIsTreatedAsFullDayRatherThanAvailable() {
        val allDay = CleaningWindow(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(8, 0))
        val availability = engine.availability(listOf(allDay), at(2026, 5, 27, 7, 0))
        assertFalse(availability.isAvailable)
        assertEquals(at(2026, 5, 27, 8, 0), availability.availableAt)
    }

    @Test
    fun overnightCleaningUsesLocalEndTimeAcrossDaylightSavingChange() {
        val saturday = CleaningWindow(DayOfWeek.SATURDAY, LocalTime.of(22, 0), LocalTime.of(6, 0))
        val availability = engine.availability(listOf(saturday), at(2026, 3, 29, 3, 0))
        assertFalse(availability.isAvailable)
        assertEquals(at(2026, 3, 29, 6, 0), availability.availableAt)
    }
}
