package dev.lastplace.app.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A single weekly street-cleaning window, e.g. Tuesday 08:00–14:00.
 * MVP uses simple weekly rules; a street may have several windows.
 */
data class CleaningWindow(
    val dayOfWeek: DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
)

/** A geographic point. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Anything the [dev.lastplace.app.domain.StreetMatcher] can match against.
 * Implemented by the Room `Street` entity, keeping the domain layer free of
 * any Android/Room dependency.
 */
interface GeoStreet {
    val lat: Double
    val lng: Double
    val matchRadiusMeters: Int
}
