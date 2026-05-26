package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Computes when a street is next off-limits for parking.
 *
 * Pure Kotlin, no Android dependencies — fully unit-testable on the JVM. This is
 * the logic a missed reminder (and therefore a fine) would come from, so it is the
 * most heavily tested class in the project.
 */
class ScheduleEngine {

    /**
     * The earliest cleaning-window START strictly after [from], across all [windows],
     * or `null` if [windows] is empty.
     */
    fun nextWindowStart(windows: List<CleaningWindow>, from: ZonedDateTime): ZonedDateTime? =
        windows.map { nextStartFor(it, from) }.minOrNull()

    private fun nextStartFor(window: CleaningWindow, from: ZonedDateTime): ZonedDateTime {
        val thisWeekDate = from.toLocalDate().with(TemporalAdjusters.nextOrSame(window.dayOfWeek))
        val candidate = ZonedDateTime.of(thisWeekDate, window.start, from.zone)
        // If the matching day is today but the window's start time has already
        // passed (or we're inside the window), the next occurrence is next week.
        return if (candidate.isAfter(from)) {
            candidate
        } else {
            val nextWeekDate = from.toLocalDate().with(TemporalAdjusters.next(window.dayOfWeek))
            ZonedDateTime.of(nextWeekDate, window.start, from.zone)
        }
    }
}
