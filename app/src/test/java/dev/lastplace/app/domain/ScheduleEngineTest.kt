package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun whenParkedInsideActiveWindowReturnsNextWeek() {
        val next = engine.nextWindowStart(listOf(tue0814), at(2026, 5, 26, 9, 0))
        assertEquals(at(2026, 6, 2, 8, 0), next)
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
    }
}
