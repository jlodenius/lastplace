package dev.lastplace.app.ui.streets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class ParkingPresentationTest {
    private val now = ZonedDateTime.parse("2026-05-26T06:00:00+02:00[Europe/Stockholm]")

    @Test
    fun urgencyChangesAtTwoHoursAndTheExactDeadline() {
        assertEquals(ParkingUrgency.Normal, parkingUrgency(now, now.plusHours(2).plusSeconds(1)))
        assertEquals(ParkingUrgency.Soon, parkingUrgency(now, now.plusHours(2)))
        assertEquals(ParkingUrgency.Soon, parkingUrgency(now, now.plusSeconds(1)))
        assertEquals(ParkingUrgency.Overdue, parkingUrgency(now, now))
        assertEquals(ParkingUrgency.Overdue, parkingUrgency(now, now.minusSeconds(1)))
        assertEquals(ParkingUrgency.NoDeadline, parkingUrgency(now, null))
    }

    @Test
    fun countdownNeverRoundsAPositiveSubMinuteDeadlineDownToZero() {
        assertEquals("Less than a minute remaining", parkingCountdownText(now, now.plusSeconds(59)))
        assertEquals("Less than a minute remaining", parkingCountdownText(now, now.plusNanos(1)))
        assertEquals("1 minute remaining", parkingCountdownText(now, now.plusMinutes(1)))
        assertEquals("Cleaning deadline passed", parkingCountdownText(now, now))
        assertEquals("Cleaning deadline passed", parkingCountdownText(now, now.minusMinutes(1)))
    }

    @Test
    fun durationsUseReadableUnitsAndOmitZeroTrailingUnits() {
        assertEquals("2 hours 15 minutes remaining", parkingCountdownText(now, now.plusHours(2).plusMinutes(15)))
        assertEquals("2 hours remaining", parkingCountdownText(now, now.plusHours(2)))
        assertEquals("3 days 5 hours", freeForText(now, now.plusDays(3).plusHours(5)))
        assertEquals("1 day", freeForText(now, now.plusDays(1)))
        assertEquals("1 hour 1 minute", freeForText(now, now.plusHours(1).plusMinutes(1)))
        assertEquals("less than a minute", freeForText(now, now.plusSeconds(30)))
    }

    @Test
    fun countdownUsesElapsedTimeAcrossClockChanges() {
        val beforeClockChange = ZonedDateTime.parse("2026-10-25T01:30:00+02:00[Europe/Stockholm]")
        val afterClockChange = ZonedDateTime.parse("2026-10-25T03:30:00+01:00[Europe/Stockholm]")
        assertEquals("3 hours remaining", parkingCountdownText(beforeClockChange, afterClockChange))
        assertEquals(ParkingUrgency.Normal, parkingUrgency(beforeClockChange, afterClockChange))
    }
}
