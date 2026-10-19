package dev.lastplace.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReminderPlanTest {
    private val time = Instant.parse("2026-05-26T12:00:00Z")

    @Test
    fun customMinutesHoursDaysAndAtTimeRemainDistinctAndExact() {
        val offsets = setOf(0L, 10, 59, 60, 1440, 2880)
        val events = reminderPlan(7, ReminderKind.PLANNED_MOVE, time, offsets, "Birch", "plan")
        assertEquals(6, events.map { it.key }.toSet().size)
        events.forEach {
            assertEquals(time.minusSeconds(it.offsetMinutes * 60).toEpochMilli(), it.fireAt)
        }
    }

    @Test
    fun categoriesPlansAndSessionsHaveIndependentIdentities() {
        val move = reminderPlan(7, ReminderKind.PLANNED_MOVE, time, setOf(10), "Birch", "a").single()
        val warning = reminderPlan(7, ReminderKind.PARKING_DEADLINE, time, setOf(10), "Oak").single()
        val replacement = move.copy(planId = "b")
        val session = move.copy(sessionId = 8)
        assertEquals(4, setOf(move.key, warning.key, replacement.key, session.key).size)
        assertEquals(move.key, move.copy(streetName = "Renamed").key)
    }

    @Test
    fun offsetsAreValidatedAndLabelsSupportDaysHoursMinutesAndZero() {
        assertTrue(reminderPlan(1, ReminderKind.PLANNED_MOVE, time, setOf(-1, Long.MAX_VALUE), "Street").isEmpty())
        assertEquals("At the time", reminderOffsetLabel(0))
        assertEquals("10 minutes before", reminderOffsetLabel(10))
        assertEquals("1 hour before", reminderOffsetLabel(60))
        assertEquals("2 days before", reminderOffsetLabel(2880))
    }
}
