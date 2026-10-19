package dev.lastplace.app.parking

import dev.lastplace.app.data.ParkSource
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.reminders.ReminderCoordinator
import dev.lastplace.app.reminders.ReminderScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

class ParkingService(
    private val streetRepository: StreetRepository,
    private val parkingRepository: ParkingRepository,
    private val deadlineCalculator: DeadlineCalculator,
    private val reminderCoordinator: ReminderCoordinator,
    private val reminderScheduler: ReminderScheduler,
) {
    private val mutex = Mutex()

    suspend fun park(
        streetId: Long,
        source: ParkSource = ParkSource.MANUAL,
        parkedAt: Instant = Instant.now(),
    ): Long = mutex.withLock { parkInternal(streetId, source, parkedAt) }

    suspend fun arriveAtPlan(sessionId: Long, planId: String, now: Instant = Instant.now()): Long = mutex.withLock {
        if (parkingRepository.getActiveSession()?.id != sessionId) return@withLock -1L
        val plan = reminderCoordinator.getPlan(sessionId)?.takeIf { it.id == planId } ?: return@withLock -1L
        parkInternal(plan.streetId, ParkSource.MANUAL, now)
    }

    private suspend fun parkInternal(streetId: Long, source: ParkSource, parkedAt: Instant): Long {
        val street = streetRepository.getStreetWithRules(streetId) ?: return -1L
        val at = parkedAt.atZone(ZoneId.systemDefault())
        val deadline = deadlineCalculator.deadline(street.rules.map { it.toWindow() }, at)
        if (deadline != null && !deadline.isAfter(at)) return -1L
        parkingRepository.getActiveSession()?.let {
            parkingRepository.endSession(it.id, parkedAt)
            reminderScheduler.cancelLegacy(it.id)
        }
        val id = parkingRepository.startSession(streetId, parkedAt, deadline?.toInstant(), source)
        reminderCoordinator.refresh()
        return id
    }

    suspend fun endParking(sessionId: Long) = mutex.withLock {
        if (parkingRepository.getActiveSession()?.id != sessionId) return@withLock
        parkingRepository.endSession(sessionId, Instant.now())
        reminderScheduler.cancelLegacy(sessionId)
        reminderCoordinator.refresh()
    }

    fun sendTestReminder() = reminderScheduler.sendTestReminder()

    suspend fun rescheduleActive() = reminderCoordinator.refresh()
}
