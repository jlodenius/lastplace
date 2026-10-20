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
    val isFavourite: Boolean = false,
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
        .thenByDescending { it.availability.nextCleaningStart?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE }
        .thenByDescending { it.street.street.isFavourite },
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
            ?.let { "Next cleaning: ${it.format(parkingTimeFormatter)}" },
        unavailableUntilText = availability.availableAt?.format(parkingTimeFormatter),
        daytimeCleaningText = daytimeText,
        isFavourite = street.street.isFavourite,
    )
}

private data class ScheduledStreet(
    val street: StreetWithRules,
    val windows: List<CleaningWindow>,
    val availability: ParkingAvailability,
)

enum class ParkingUrgency { Normal, Soon, Overdue, NoDeadline }

private val moveSoonThreshold = Duration.ofHours(2)

internal fun parkingUrgency(now: ZonedDateTime, deadline: ZonedDateTime?): ParkingUrgency {
    if (deadline == null) return ParkingUrgency.NoDeadline
    val remaining = Duration.between(now, deadline)
    return when {
        remaining.isNegative || remaining.isZero -> ParkingUrgency.Overdue
        remaining <= moveSoonThreshold -> ParkingUrgency.Soon
        else -> ParkingUrgency.Normal
    }
}

internal fun parkingCountdownText(now: ZonedDateTime, deadline: ZonedDateTime): String {
    val remaining = Duration.between(now, deadline)
    return when {
        remaining.isNegative || remaining.isZero -> "Cleaning deadline passed"
        remaining < Duration.ofMinutes(1) -> "Less than a minute remaining"
        else -> "${freeForText(now, deadline)} remaining"
    }
}

internal fun freeForText(now: ZonedDateTime, until: ZonedDateTime): String {
    val duration = Duration.between(now, until)
    if (duration.isNegative || duration.isZero) return "0 minutes"
    if (duration < Duration.ofMinutes(1)) return "less than a minute"
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    fun amount(value: Long, unit: String) = "$value $unit${if (value == 1L) "" else "s"}"
    return when {
        days > 0 -> listOfNotNull(amount(days, "day"), hours.takeIf { it > 0 }?.let { amount(it, "hour") }).joinToString(" ")
        hours > 0 -> listOfNotNull(amount(hours, "hour"), minutes.takeIf { it > 0 }?.let { amount(it, "minute") }).joinToString(" ")
        else -> amount(minutes, "minute")
    }
}
