package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

data class ParkingAvailability(
    val nextCleaningStart: ZonedDateTime?,
    val availableAt: ZonedDateTime?,
) {
    val isAvailable: Boolean get() = availableAt == null
}

class ScheduleEngine {

    /** Returns the current cleaning start if already off-limits, otherwise the next start. */
    fun nextWindowStart(windows: List<CleaningWindow>, from: ZonedDateTime): ZonedDateTime? =
        availability(windows, from).nextCleaningStart

    fun availability(windows: List<CleaningWindow>, from: ZonedDateTime): ParkingAvailability {
        val occurrences = windows.flatMap { window ->
            val previousDate = from.toLocalDate().with(TemporalAdjusters.previousOrSame(window.dayOfWeek))
            listOf(previousDate, previousDate.plusWeeks(1)).map { date ->
                val endDate = if (window.end.isAfter(window.start)) date else date.plusDays(1)
                Occurrence(
                    start = ZonedDateTime.of(date, window.start, from.zone),
                    end = ZonedDateTime.of(endDate, window.end, from.zone),
                )
            }
        }.filter { it.end.isAfter(from) }.sortedBy { it.start }

        var availableAt: ZonedDateTime? = null
        for (occurrence in occurrences) {
            if (occurrence.start.isAfter(availableAt ?: from)) break
            if (availableAt == null || occurrence.end.isAfter(availableAt)) {
                availableAt = occurrence.end
            }
        }
        return ParkingAvailability(occurrences.firstOrNull()?.start, availableAt)
    }

    private data class Occurrence(val start: ZonedDateTime, val end: ZonedDateTime)
}
