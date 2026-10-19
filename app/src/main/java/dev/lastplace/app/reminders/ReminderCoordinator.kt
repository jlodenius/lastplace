package dev.lastplace.app.reminders

import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.data.db.ReminderDao
import dev.lastplace.app.data.db.ScheduledReminder
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.db.rulesSignature
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.reminderPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class ReminderCoordinator(
    private val dao: ReminderDao,
    private val parking: ParkingRepository,
    private val streets: StreetRepository,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
    private val engine: ScheduleEngine,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val mutex = Mutex()
    val plans = dao.observePlans()

    suspend fun getPlan(sessionId: Long): PlannedMove? = dao.getPlan(sessionId)

    suspend fun selectPlan(sessionId: Long, streetId: Long, moveAt: Instant, justCleaned: Boolean): Boolean = mutex.withLock {
        val active = parking.getActiveSession()?.takeIf { it.id == sessionId } ?: return@withLock false
        if (streetId == active.streetId) return@withLock false
        val saved = streets.observeStreetsWithRules().first()
        val target = saved.firstOrNull { it.street.id == streetId } ?: return@withLock false
        val now = clock.instant()
        val time = if (justCleaned) moveAt else now
        if (justCleaned && time.isBefore(now.truncatedTo(ChronoUnit.MINUTES))) return@withLock false
        val candidate = PlannedMove(
            sessionId, UUID.randomUUID().toString(), streetId, target.street.name,
            time.toEpochMilli(), justCleaned, target.rulesSignature(),
        )
        if (planProblem(candidate, active, saved, engine, clock.zone) != null) return@withLock false
        val previous = dao.getPlan(sessionId)
        if (previous?.invalidReason == null && previous?.streetId == streetId && previous.moveAt == candidate.moveAt) {
            return@withLock true
        }
        dao.savePlan(candidate)
        reconcile(allowImmediatePlanId = candidate.id)
        true
    }

    suspend fun cancelPlan(sessionId: Long, planId: String) = mutex.withLock {
        dao.deletePlan(sessionId, planId)
        reconcile()
    }

    suspend fun refresh() = mutex.withLock { reconcile() }

    suspend fun deliver(key: String) = mutex.withLock {
        val current = desiredReminders()
        val stored = dao.getReminders().firstOrNull { it.key == key } ?: return@withLock
        val desired = current.firstOrNull { it.key == key }
        if (desired == null) {
            scheduler.cancel(stored.spec)
            dao.deleteReminder(key)
            return@withLock
        }
        if (stored.delivered) return@withLock
        val now = clock.instant().toEpochMilli()
        if (desired.fireAt > now) {
            scheduler.schedule(desired)
            return@withLock
        }
        dao.saveReminder(ScheduledReminder(key, desired, delivered = true))
        // A phone starting late should not replay a long-missed move opportunity.
        if (desired.kind == ReminderKind.PLANNED_MOVE && now > desired.targetAt + MOVE_DELIVERY_GRACE_MILLIS) return@withLock
        scheduler.show(desired)
    }

    private suspend fun reconcile(allowImmediatePlanId: String? = null) {
        val desired = desiredReminders().associateBy { it.key }
        val existing = dao.getReminders().associateBy { it.key }
        existing.values.filter { it.key !in desired }.forEach {
            scheduler.cancel(it.spec)
            dao.deleteReminder(it.key)
        }
        val now = clock.instant().toEpochMilli()
        for ((key, spec) in desired) {
            val old = existing[key]
            val immediate = spec.planId == allowImmediatePlanId && allowImmediatePlanId != null && spec.offsetMinutes == 0L
            if (old == null && spec.fireAt <= now && !immediate) continue
            val reminder = ScheduledReminder(key, spec, old?.delivered ?: false)
            dao.saveReminder(reminder)
            if (!reminder.delivered) scheduler.schedule(spec)
        }
    }

    private suspend fun desiredReminders(): List<ReminderSpec> {
        val active = parking.getActiveSession()
        dao.deleteOtherPlans(active?.id ?: -1)
        if (active == null) return emptyList()
        scheduler.cancelLegacy(active.id)
        val saved = streets.observeStreetsWithRules().first()
        val preferences = settings.settings.first()
        val deadline = currentDeadline(active, saved, engine, clock.zone)
        if (deadline?.toEpochMilli() != active.deadline) parking.updateDeadline(active.id, deadline)
        val currentName = saved.firstOrNull { it.street.id == active.streetId }?.street?.name ?: "your current street"
        var plan = dao.getPlan(active.id)
        plan?.let {
            val problem = planProblem(it, active, saved, engine, clock.zone)
            if (problem != null && it.invalidReason == null) {
                plan = it.copy(invalidReason = problem)
                dao.savePlan(requireNotNull(plan))
            }
        }
        return buildList {
            if (preferences.deadlineWarningsEnabled && deadline != null) {
                addAll(reminderPlan(active.id, ReminderKind.PARKING_DEADLINE, deadline, preferences.deadlineOffsetsMinutes, currentName))
            }
            plan?.takeIf { it.invalidReason == null && preferences.moveRemindersEnabled }?.let {
                val name = saved.firstOrNull { street -> street.street.id == it.streetId }?.street?.name ?: it.streetName
                addAll(reminderPlan(active.id, ReminderKind.PLANNED_MOVE, Instant.ofEpochMilli(it.moveAt), preferences.moveOffsetsMinutes, name, it.id))
            }
        }
    }
}

private const val MOVE_DELIVERY_GRACE_MILLIS = 15 * 60_000L

fun currentDeadline(
    session: ParkingSession,
    streets: List<StreetWithRules>,
    engine: ScheduleEngine,
    zone: ZoneId,
): Instant? {
    val street = streets.firstOrNull { it.street.id == session.streetId }
        ?: return session.deadline?.let(Instant::ofEpochMilli)
    return engine.nextWindowStart(street.rules.map { it.toWindow() }, Instant.ofEpochMilli(session.parkedAt).atZone(zone))?.toInstant()
}

fun planProblem(
    plan: PlannedMove,
    session: ParkingSession,
    streets: List<StreetWithRules>,
    engine: ScheduleEngine,
    zone: ZoneId,
): String? {
    plan.invalidReason?.let { return it }
    if (plan.sessionId != session.id) return "This parking session has ended."
    if (streets.none { it.street.id == session.streetId }) return "Your current street was removed."
    val target = streets.firstOrNull { it.street.id == plan.streetId } ?: return "The planned street was removed."
    if (target.rulesSignature() != plan.rulesSignature) return "The destination's cleaning schedule changed. Choose a new plan."
    val deadline = currentDeadline(session, streets, engine, zone)
    val time = Instant.ofEpochMilli(plan.moveAt)
    if (plan.justCleaned && deadline != null && !time.isBefore(deadline)) {
        return "The planned move is no longer before your parking deadline."
    }
    val windows = target.rules.map { it.toWindow() }
    val at = time.atZone(zone)
    if (!engine.availability(windows, at).isAvailable) return "The destination is not available at the planned time."
    if (plan.justCleaned && engine.availability(windows, at.minusNanos(1)).isAvailable) {
        return "Cleaning no longer ends at the planned time. Choose a new plan."
    }
    return null
}
