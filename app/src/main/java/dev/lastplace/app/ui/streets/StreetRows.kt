package dev.lastplace.app.ui.streets

import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.domain.ParkingAvailability
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.model.CleaningWindow
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class StreetRow(
    val id: Long,
    val name: String,
    val freeFor: String?,
    val nextCleaningText: String?,
    val unavailableUntilText: String?,
    val daytimeCleaningText: String?,
) {
    val isAvailable: Boolean get() = unavailableUntilText == null
    val hasDaytimeCleaning: Boolean get() = daytimeCleaningText != null
}

internal val parkingTimeFormatter: DateTimeFormatter
    get() = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())

internal fun buildStreetRows(
    streets: List<StreetWithRules>,
    now: ZonedDateTime,
    scheduleEngine: ScheduleEngine,
    daytimeOnly: Boolean,
): List<StreetRow> = streets.map { street ->
    val windows = street.rules.map { it.toWindow() }
    ScheduledStreet(street, windows, scheduleEngine.availability(windows, now))
}.filter { (_, windows, _) ->
    !daytimeOnly || windows.any { it.isDaytime }
}.sortedWith(
    compareBy<ScheduledStreet> { !it.availability.isAvailable }
        .thenBy { it.availability.availableAt }
        .thenByDescending { it.availability.nextCleaningStart?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE },
).map { (street, windows, availability) ->
    val daytimeWindows = windows.filter { it.isDaytime }.sortedWith(compareBy({ it.dayOfWeek }, { it.start }))
    val daytimeText = daytimeWindows.takeIf { it.isNotEmpty() }?.joinToString(" · ") {
        "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.start}–${it.end}"
    }
    StreetRow(
        id = street.street.id,
        name = street.street.name,
        freeFor = availability.nextCleaningStart?.takeIf { availability.isAvailable }?.let { freeForText(now, it) },
        nextCleaningText = availability.nextCleaningStart?.takeIf { availability.isAvailable }
            ?.let { "cleaning ${it.format(parkingTimeFormatter)}" },
        unavailableUntilText = availability.availableAt?.format(parkingTimeFormatter),
        daytimeCleaningText = daytimeText,
    )
}

private data class ScheduledStreet(
    val street: StreetWithRules,
    val windows: List<CleaningWindow>,
    val availability: ParkingAvailability,
)

internal fun freeForText(now: ZonedDateTime, until: ZonedDateTime): String {
    val d = Duration.between(now, until)
    if (d.isNegative || d.isZero) return "0m"
    val days = d.toDays()
    val hours = d.toHours() % 24
    val minutes = d.toMinutes() % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
