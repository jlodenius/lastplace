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
import dev.lastplace.app.data.settings.ReminderSettings
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReminderSpec
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.reminderPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val reconciled = MutableStateFlow<ReconciledReminders?>(null)
    val readiness: Flow<ReminderReadiness> = combine(
        combine(
            parking.observeActiveSession(), streets.observeStreetsWithRules(), settings.settings, plans,
        ) { session, saved, preferences, plans ->
            reminderInputs(session, saved, preferences, plans.firstOrNull { it.sessionId == session?.id })
        },
        reconciled,
    ) { inputs, snapshot ->
        val current = snapshot?.takeIf { it.inputs == inputs }
        ReminderReadiness(
            sessionId = inputs.session?.id,
            planId = inputs.plan?.id,
            updating = current == null,
            pending = current?.pending.orEmpty(),
            settings = inputs.settings,
            deadline = inputs.session?.deadline,
            planInvalidReason = inputs.plan?.invalidReason,
        )
    }.distinctUntilChanged()

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

    suspend fun cancelPlan(sessionId: Long, planId: String): Boolean = mutex.withLock {
        if (parking.getActiveSession()?.id != sessionId || dao.getPlan(sessionId)?.id != planId) return@withLock false
        dao.deletePlan(sessionId, planId)
        reconcile()
        true
    }

    suspend fun refresh() = mutex.withLock { reconcile() }

    suspend fun <T> mutateParking(mutation: suspend () -> T): T = mutex.withLock {
        reconciled.value = null
        val result = mutation()
        reconcile()
        result
    }

    suspend fun deliver(key: String) = mutex.withLock {
        reconciled.value = null
        val prepared = desiredReminders()
        deliverPrepared(key, prepared.specs)
        publishReadiness(prepared)
    }

    private suspend fun deliverPrepared(key: String, current: List<ReminderSpec>) {
        val stored = dao.getReminders().firstOrNull { it.key == key } ?: return
        val desired = current.firstOrNull { it.key == key }
        if (desired == null) {
            scheduler.cancel(stored.spec)
            dao.deleteReminder(key)
            return
        }
        if (stored.delivered) return
        val now = clock.instant().toEpochMilli()
        if (desired.fireAt > now) {
            scheduler.schedule(desired)
            return
        }
        dao.saveReminder(ScheduledReminder(key, desired, delivered = true))
        // A phone starting late should not replay a long-missed move opportunity.
        if (desired.kind == ReminderKind.PLANNED_MOVE && now > desired.targetAt + MOVE_DELIVERY_GRACE_MILLIS) return
        scheduler.show(desired)
    }

    private suspend fun reconcile(allowImmediatePlanId: String? = null) {
        reconciled.value = null
        val prepared = desiredReminders()
        val desired = prepared.specs.associateBy { it.key }
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
        publishReadiness(prepared)
    }

    private suspend fun publishReadiness(prepared: PreparedReminders) {
        val desired = prepared.specs.associateBy { it.key }
        reconciled.value = ReconciledReminders(
            prepared.inputs,
            dao.getReminders().filter { !it.delivered && desired[it.key] == it.spec }.map { it.spec },
        )
    }

    private suspend fun desiredReminders(): PreparedReminders {
        val active = parking.getActiveSession()
        dao.deleteOtherPlans(active?.id ?: -1)
        val saved = streets.observeStreetsWithRules().first()
        val preferences = settings.settings.first()
        if (active == null) return PreparedReminders(reminderInputs(null, saved, preferences, null), emptyList())
        scheduler.cancelLegacy(active.id)
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
        val specs = buildList {
            if (preferences.deadlineWarningsEnabled && deadline != null) {
                addAll(reminderPlan(active.id, ReminderKind.PARKING_DEADLINE, deadline, preferences.deadlineOffsetsMinutes, currentName))
            }
            plan?.takeIf { it.invalidReason == null && preferences.moveRemindersEnabled }?.let {
                val name = saved.firstOrNull { street -> street.street.id == it.streetId }?.street?.name ?: it.streetName
                addAll(reminderPlan(active.id, ReminderKind.PLANNED_MOVE, Instant.ofEpochMilli(it.moveAt), preferences.moveOffsetsMinutes, name, it.id))
            }
        }
        return PreparedReminders(reminderInputs(active, saved, preferences, plan), specs)
    }

    private fun reminderInputs(
        active: ParkingSession?,
        saved: List<StreetWithRules>,
        preferences: ReminderSettings,
        plan: PlannedMove?,
    ) = ReminderInputs(
        active?.copy(deadline = currentDeadline(active, saved, engine, clock.zone)?.toEpochMilli()),
        saved.map { ReminderStreet(it.street.id, it.street.name, it.rulesSignature()) }.sortedBy { it.id },
        preferences,
        plan,
        clock.zone,
    )
}

private data class ReminderStreet(val id: Long, val name: String, val rules: String)
private data class ReminderInputs(
    val session: ParkingSession?,
    val streets: List<ReminderStreet>,
    val settings: ReminderSettings,
    val plan: PlannedMove?,
    val zone: ZoneId,
)
private data class PreparedReminders(val inputs: ReminderInputs, val specs: List<ReminderSpec>)
private data class ReconciledReminders(val inputs: ReminderInputs, val pending: List<ReminderSpec>)

internal const val MOVE_DELIVERY_GRACE_MILLIS = 15 * 60_000L

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
