package dev.lastplace.app.data.db

import dev.lastplace.app.domain.model.LatLng

/**
 * Encodes a street's geometry — one or more polylines (OSM ways) — as a compact string.
 * Format: ways separated by '|', points within a way separated by ';', "lat,lng" per point.
 * Keeping ways separate avoids a spurious connecting segment between disjoint OSM ways.
 */
object GeometryCodec {

    fun encode(ways: List<List<LatLng>>): String? {
        val nonEmpty = ways.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        return nonEmpty.joinToString("|") { way ->
            way.joinToString(";") { "${it.lat},${it.lng}" }
        }
    }

    fun decode(encoded: String?): List<List<LatLng>> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split("|").mapNotNull { wayStr ->
            val points = wayStr.split(";").mapNotNull { pointStr ->
                val parts = pointStr.split(",")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null) LatLng(lat, lng) else null
            }
            points.ifEmpty { null }
        }
    }
}
