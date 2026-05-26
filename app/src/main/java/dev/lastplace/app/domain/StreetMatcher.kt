package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.GeoStreet
import dev.lastplace.app.domain.model.LatLng
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Matches a GPS fix to the nearest known street. When a street has polyline geometry
 * (from OSM), distance is measured to the street's line — far more accurate for long
 * streets than a point + radius. Falls back to point distance when geometry is absent.
 */
class StreetMatcher {

    /** A street to match against, carrying its id/name plus geometry. */
    data class Target(
        val id: Long,
        val name: String,
        val point: LatLng,
        val matchRadiusMeters: Int,
        /** Zero or more polylines (OSM ways). Empty = use [point]. */
        val geometry: List<List<LatLng>> = emptyList(),
    )

    data class Match(val target: Target, val distanceMeters: Double)

    /** Nearest target within its match radius, or null. */
    fun match(location: LatLng, targets: List<Target>): Match? =
        targets
            .map { Match(it, distanceTo(location, it)) }
            .filter { it.distanceMeters <= it.target.matchRadiusMeters }
            .minByOrNull { it.distanceMeters }

    private fun distanceTo(location: LatLng, target: Target): Double =
        if (target.geometry.isEmpty()) {
            haversineMeters(location, target.point)
        } else {
            target.geometry.minOf { way -> distanceToPolylineMeters(location, way) }
        }

    /** Legacy point-only matcher retained for simple [GeoStreet] callers/tests. */
    fun <T : GeoStreet> nearest(location: LatLng, streets: List<T>): Pair<T, Double>? =
        streets
            .map { it to haversineMeters(location, LatLng(it.lat, it.lng)) }
            .filter { it.second <= it.first.matchRadiusMeters }
            .minByOrNull { it.second }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val METERS_PER_DEG_LAT = 111_132.0

        fun haversineMeters(a: LatLng, b: LatLng): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
        }

        fun distanceToPolylineMeters(point: LatLng, line: List<LatLng>): Double {
            if (line.isEmpty()) return Double.MAX_VALUE
            if (line.size == 1) return haversineMeters(point, line[0])
            var best = Double.MAX_VALUE
            for (i in 0 until line.size - 1) {
                best = min(best, distanceToSegmentMeters(point, line[i], line[i + 1]))
            }
            return best
        }

        /** Point-to-segment distance using a local equirectangular projection (fine at street scale). */
        private fun distanceToSegmentMeters(p: LatLng, a: LatLng, b: LatLng): Double {
            val metersPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(p.lat))
            val px = (p.lng - a.lng) * metersPerDegLng
            val py = (p.lat - a.lat) * METERS_PER_DEG_LAT
            val bx = (b.lng - a.lng) * metersPerDegLng
            val by = (b.lat - a.lat) * METERS_PER_DEG_LAT
            val segLenSq = bx * bx + by * by
            val t = if (segLenSq == 0.0) 0.0 else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            return sqrt(dx * dx + dy * dy)
        }
    }
}
