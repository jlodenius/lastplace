package dev.lastplace.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DeadlineCalculatorTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val calc = DeadlineCalculator()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi), zone)

    @Test
    fun keepsFutureOffsetsSortedAscending() {
        val deadline = at(2026, 5, 26, 8, 0)
        val now = at(2026, 5, 25, 2, 0)
        val times = calc.reminderTimes(deadline, offsetsHours = listOf(12, 24), now = now)
        // 24h before -> 05-25 08:00, 12h before -> 05-25 20:00, both after now.
        assertEquals(listOf(at(2026, 5, 25, 8, 0), at(2026, 5, 25, 20, 0)), times)
    }

    @Test
    fun dropsOffsetsAlreadyInThePast() {
        val deadline = at(2026, 5, 26, 8, 0)
        val now = at(2026, 5, 25, 22, 0) // past both the 24h and 12h marks
        val times = calc.reminderTimes(deadline, offsetsHours = listOf(24, 12), now = now)
        assertTrue(times.isEmpty())
    }

    @Test
    fun keepsOnlyTheStillFutureOffset() {
        val deadline = at(2026, 5, 26, 8, 0)
        val now = at(2026, 5, 25, 12, 0) // past the 24h mark, before the 12h mark
        val times = calc.reminderTimes(deadline, offsetsHours = listOf(24, 12), now = now)
        assertEquals(listOf(at(2026, 5, 25, 20, 0)), times)
    }
}
