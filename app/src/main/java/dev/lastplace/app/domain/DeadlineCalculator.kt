package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import java.time.ZonedDateTime

/**
 * Turns a parking event + a street's cleaning windows into a deadline and the set of
 * reminder times to schedule.
 */
class DeadlineCalculator(
    private val engine: ScheduleEngine = ScheduleEngine(),
) {

    /** The moment the car must be moved by: the next cleaning window after [parkedAt]. */
    fun deadline(windows: List<CleaningWindow>, parkedAt: ZonedDateTime): ZonedDateTime? =
        engine.nextWindowStart(windows, parkedAt)

    /**
     * Reminder times for a [deadline], one per offset in [offsetsHours] (e.g. 24h & 12h
     * before). Past times relative to [now] are dropped; the result is sorted ascending.
     */
    fun reminderTimes(
        deadline: ZonedDateTime,
        offsetsHours: List<Int>,
        now: ZonedDateTime,
    ): List<ZonedDateTime> =
        offsetsHours
            .map { deadline.minusHours(it.toLong()) }
            .filter { it.isAfter(now) }
            .sorted()
}
