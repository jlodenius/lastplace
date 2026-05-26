package dev.lastplace.app.parking

import dev.lastplace.app.data.ParkSource
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Orchestrates a parking session end-to-end: ends any prior session, computes the
 * deadline from the street's cleaning windows, persists the session, and schedules
 * the move-your-car reminders.
 */
class ParkingService(
    private val streetRepository: StreetRepository,
    private val parkingRepository: ParkingRepository,
    private val settingsRepository: SettingsRepository,
    private val deadlineCalculator: DeadlineCalculator,
    private val reminderScheduler: ReminderScheduler,
) {

    /** Registers the car as parked on [streetId]. Returns the new session id, or -1 if the street is gone. */
    suspend fun park(
        streetId: Long,
        source: ParkSource = ParkSource.MANUAL,
        parkedAt: Instant = Instant.now(),
    ): Long {
        // Only one active session at a time.
        parkingRepository.getActiveSession()?.let { endParking(it.id) }

        val streetWithRules = streetRepository.getStreetWithRules(streetId) ?: return -1L
        val zonedParkedAt = parkedAt.atZone(ZoneId.systemDefault())
        val windows = streetWithRules.rules.map { it.toWindow() }
        val deadline = deadlineCalculator.deadline(windows, zonedParkedAt)

        val sessionId = parkingRepository.startSession(
            streetId = streetId,
            parkedAt = parkedAt,
            deadline = deadline?.toInstant(),
            source = source,
        )

        if (deadline != null) {
            val offsets = settingsRepository.settings.first().offsetsHours
            val times = deadlineCalculator.reminderTimes(deadline, offsets, zonedParkedAt)
            reminderScheduler.schedule(
                sessionId = sessionId,
                streetName = streetWithRules.street.name,
                deadline = deadline.toInstant(),
                reminderTimes = times.map { it.toInstant() },
            )
        }
        return sessionId
    }

    suspend fun endParking(sessionId: Long) {
        parkingRepository.endSession(sessionId, Instant.now())
        reminderScheduler.cancelAll(sessionId)
    }

    /** Fires a reminder through the real pipeline ~10 s from now, to verify notifications work. */
    fun sendTestReminder() {
        val now = Instant.now()
        reminderScheduler.schedule(
            sessionId = TEST_SESSION_ID,
            streetName = "Test street",
            deadline = now.plusSeconds(2 * 3600),
            reminderTimes = listOf(now.plusSeconds(10)),
        )
    }

    /** Re-schedules reminders for the active session (e.g. after a reboot). */
    suspend fun rescheduleActive() {
        val active = parkingRepository.getActiveSession() ?: return
        val deadlineMillis = active.deadline ?: return
        val street = streetRepository.getStreetWithRules(active.streetId) ?: return

        val deadline = Instant.ofEpochMilli(deadlineMillis)
        val offsets = settingsRepository.settings.first().offsetsHours
        val times = deadlineCalculator.reminderTimes(
            deadline.atZone(ZoneId.systemDefault()),
            offsets,
            ZonedDateTime.now(),
        )
        reminderScheduler.schedule(active.id, street.street.name, deadline, times.map { it.toInstant() })
    }

    companion object {
        private const val TEST_SESSION_ID = 999_999L
    }
}
