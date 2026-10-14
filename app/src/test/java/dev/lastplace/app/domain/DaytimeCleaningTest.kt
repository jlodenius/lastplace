package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class DaytimeCleaningTest {

    private fun window(start: String, end: String) =
        CleaningWindow(DayOfWeek.TUESDAY, LocalTime.parse(start), LocalTime.parse(end))

    @Test
    fun daytimeIncludesNineToFourteenAndTheFullDaytimeRange() {
        assertTrue(window("09:00", "14:00").isDaytime)
        assertTrue(window("08:00", "18:00").isDaytime)
    }

    @Test
    fun windowsExtendingOutsideDaytimeDoNotQualify() {
        assertFalse(window("07:59", "14:00").isDaytime)
        assertFalse(window("09:00", "18:01").isDaytime)
        assertFalse(window("00:00", "06:00").isDaytime)
        assertFalse(window("19:00", "22:00").isDaytime)
    }

    @Test
    fun overnightAndFullDayWindowsDoNotQualify() {
        assertFalse(window("22:00", "06:00").isDaytime)
        assertFalse(window("14:00", "09:00").isDaytime)
        assertFalse(window("09:00", "09:00").isDaytime)
    }
}
