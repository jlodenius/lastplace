package dev.lastplace.app.reminders

import dev.lastplace.app.domain.ReminderKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderCapabilitiesTest {
    @Test
    fun blockedMoveChannelDoesNotDescribeDeadlineNotificationsAsBlocked() {
        val capabilities = ReminderCapabilities(moveChannelAllowed = false)
        assertTrue(capabilities.notificationsAllowed(ReminderKind.PARKING_DEADLINE))
        assertFalse(capabilities.notificationsAllowed(ReminderKind.PLANNED_MOVE))
    }

    @Test
    fun appChannelAndPreciseAlarmCapabilitiesRemainIndependent() {
        val capabilities = ReminderCapabilities(appNotificationsAllowed = false, preciseAlarmsAllowed = false)
        assertTrue(capabilities.channelAllowed(ReminderKind.PARKING_DEADLINE))
        assertTrue(capabilities.channelAllowed(ReminderKind.PLANNED_MOVE))
        assertFalse(capabilities.notificationsAllowed(ReminderKind.PARKING_DEADLINE))
        assertFalse(capabilities.notificationsAllowed(ReminderKind.PLANNED_MOVE))
        assertFalse(capabilities.preciseAlarmsAllowed)
        val imprecise = ReminderCapabilities(preciseAlarmsAllowed = false)
        assertTrue(imprecise.notificationsAllowed(ReminderKind.PARKING_DEADLINE))
    }
}
