package dev.lastplace.app.ui.addstreet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RuleInputTest {
    @Test
    fun unsetFieldsAreNotAcceptedAsPickerSeedValues() {
        val rule = RuleInput()
        assertFalse(rule.isComplete)
        assertNull(rule.summary())
        assertEquals("Choose weekday, start time, end time.", rule.missingFields)
        assertFalse(rule.copy(dayOfWeek = 2, startMinute = 480).isComplete)
    }

    @Test
    fun summaryMakesOvernightAndEqualTimeMeaningsExplicitIncludingSundayRollover() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            assertEquals("Tuesday 08:00–14:00", RuleInput(2, 480, 840).summary())
            assertEquals("Sunday 22:00 to Monday 02:00 (ends next day)", RuleInput(7, 1320, 120).summary())
            assertEquals("Tuesday 08:00 to Wednesday 08:00 (24-hour restriction)", RuleInput(2, 480, 480).summary())
            assertTrue(RuleInput(2, 0, 0).isComplete)
        } finally {
            Locale.setDefault(original)
        }
    }
}
