package dev.lastplace.app.parking

import dev.lastplace.app.data.ParkSource
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.reminders.ReminderCoordinator
import dev.lastplace.app.reminders.ReminderScheduler
import dev.lastplace.app.reminders.planProblem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

data class StreetDeletionContext(
    val street: StreetWithRules,
    val activeSessionId: Long?,
    val parkedStreetId: Long?,
    val selectedPlanId: String?,
    val selectedDestinationId: Long?,
) {
    val isParkedStreet: Boolean get() = parkedStreetId == street.street.id
    val isPlannedDestination: Boolean get() = selectedDestinationId == street.street.id
}

class ParkingService(
    private val streetRepository: StreetRepository,
    private val parkingRepository: ParkingRepository,
    private val deadlineCalculator: DeadlineCalculator,
    private val reminderCoordinator: ReminderCoordinator,
    private val reminderScheduler: ReminderScheduler,
    private val scheduleEngine: ScheduleEngine = ScheduleEngine(),
) {
    private val mutex = Mutex()

    suspend fun park(
        streetId: Long,
        source: ParkSource = ParkSource.MANUAL,
        parkedAt: Instant = Instant.now(),
    ): Long = mutex.withLock {
        reminderCoordinator.mutateParking { parkInternal(streetId, source, parkedAt) }
    }

    suspend fun parkIfCurrent(
        streetId: Long,
        expectedSessionId: Long?,
        parkedAt: Instant = Instant.now(),
    ): Long = mutex.withLock {
        reminderCoordinator.mutateParking {
            if (parkingRepository.getActiveSession()?.id != expectedSessionId) -1L
            else parkInternal(streetId, ParkSource.MANUAL, parkedAt)
        }
    }

    suspend fun arriveAtPlan(sessionId: Long, planId: String, now: Instant = Instant.now()): Long = mutex.withLock {
        reminderCoordinator.mutateParking {
            val session = parkingRepository.getActiveSession()?.takeIf { it.id == sessionId }
                ?: return@mutateParking -1L
            val plan = reminderCoordinator.getPlan(sessionId)?.takeIf { it.id == planId }
                ?: return@mutateParking -1L
            val streets = streetRepository.observeStreetsWithRules().first()
            if (planProblem(plan, session, streets, scheduleEngine, ZoneId.systemDefault()) != null) return@mutateParking -1L
            parkInternal(plan.streetId, ParkSource.MANUAL, now)
        }
    }

    private suspend fun parkInternal(streetId: Long, source: ParkSource, parkedAt: Instant): Long {
        val street = streetRepository.getStreetWithRules(streetId) ?: return -1L
        val at = parkedAt.atZone(ZoneId.systemDefault())
        val deadline = deadlineCalculator.deadline(street.rules.map { it.toWindow() }, at)
        if (deadline != null && !deadline.isAfter(at)) return -1L
        parkingRepository.getActiveSession()?.let {
            if (it.streetId == streetId) return it.id
            parkingRepository.endSession(it.id, parkedAt)
            reminderScheduler.cancelLegacy(it.id)
        }
        return parkingRepository.startSession(streetId, parkedAt, deadline?.toInstant(), source)
    }

    suspend fun endParking(sessionId: Long): Boolean = mutex.withLock {
        reminderCoordinator.mutateParking {
            if (parkingRepository.getActiveSession()?.id != sessionId) return@mutateParking false
            parkingRepository.endSession(sessionId, Instant.now())
            reminderScheduler.cancelLegacy(sessionId)
            true
        }
    }

    suspend fun deletionContext(streetId: Long): StreetDeletionContext? {
        val street = streetRepository.getStreetWithRules(streetId) ?: return null
        val active = parkingRepository.getActiveSession()
        val plan = active?.let { reminderCoordinator.getPlan(it.id) }
        return StreetDeletionContext(street, active?.id, active?.streetId, plan?.id, plan?.streetId)
    }

    suspend fun deleteStreet(expected: StreetDeletionContext): Boolean = mutex.withLock {
        reminderCoordinator.mutateParking {
            val current = deletionContext(expected.street.street.id)
            if (current != expected) return@mutateParking false
            if (expected.isParkedStreet) {
                val sessionId = requireNotNull(expected.activeSessionId)
                parkingRepository.endSession(sessionId, Instant.now())
                reminderScheduler.cancelLegacy(sessionId)
            }
            streetRepository.deleteStreet(expected.street.street)
            true
        }
    }

    fun sendTestReminder() = reminderScheduler.sendTestReminder()

    suspend fun rescheduleActive() = reminderCoordinator.refresh()
}
