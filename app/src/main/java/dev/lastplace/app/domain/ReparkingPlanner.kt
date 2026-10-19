package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class ReparkingStreet(
    val id: Long,
    val name: String,
    val windows: List<CleaningWindow>,
    val isFavourite: Boolean = false,
)

data class ReparkingOption(
    val street: ReparkingStreet,
    val moveAt: ZonedDateTime,
    val nextCleaningStart: ZonedDateTime?,
    val justCleaned: Boolean,
)

class ReparkingPlanner(private val scheduleEngine: ScheduleEngine = ScheduleEngine()) {

    fun options(
        streets: List<ReparkingStreet>,
        currentStreetId: Long,
        deadline: ZonedDateTime?,
        preferredTimes: Set<LocalTime>,
        now: ZonedDateTime,
    ): List<ReparkingOption> {
        if (deadline == null) return emptyList()
        val destinations = streets.filter { it.id != currentStreetId }
        val easy = if (deadline.isAfter(now)) destinations.flatMap { street ->
            cleaningEnds(street.windows, preferredTimes, now, deadline).mapNotNull { end ->
                val availability = scheduleEngine.availability(street.windows, end)
                if (availability.isAvailable) {
                    ReparkingOption(street, end, availability.nextCleaningStart, justCleaned = true)
                } else null
            }
        } else emptyList()

        val candidates = easy.ifEmpty {
            destinations.mapNotNull { street ->
                val availability = scheduleEngine.availability(street.windows, now)
                if (availability.isAvailable) {
                    ReparkingOption(street, now, availability.nextCleaningStart, justCleaned = false)
                } else null
            }
        }
        return candidates.sortedWith(
            compareByDescending<ReparkingOption> { it.moveAt.toInstant() }
                .thenByDescending { it.nextCleaningStart?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE }
                .thenByDescending { it.street.isFavourite }
                .thenBy { it.street.name }
                .thenBy { it.street.id },
        ).distinctBy { it.street.id }.take(3)
    }

    private fun cleaningEnds(
        windows: List<CleaningWindow>,
        preferredTimes: Set<LocalTime>,
        now: ZonedDateTime,
        deadline: ZonedDateTime,
    ): List<ZonedDateTime> = windows.filter { it.end in preferredTimes }.flatMap { window ->
        var date = now.toLocalDate().with(TemporalAdjusters.previousOrSame(window.dayOfWeek))
        buildList {
            while (!date.isAfter(deadline.toLocalDate())) {
                val endDate = if (window.end.isAfter(window.start)) date else date.plusDays(1)
                val end = ZonedDateTime.of(endDate, window.end, now.zone)
                // A 14:00 opportunity remains usable throughout the displayed 14:00 minute.
                if (!end.isBefore(now.truncatedTo(ChronoUnit.MINUTES)) && end.isBefore(deadline)) add(end)
                date = date.plusWeeks(1)
            }
        }
    }.distinct()
}
