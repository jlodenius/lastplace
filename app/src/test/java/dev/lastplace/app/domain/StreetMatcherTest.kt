package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetMatcherTest {

    private val matcher = StreetMatcher()

    private fun target(
        id: Long,
        lat: Double,
        lng: Double,
        radius: Int = 40,
        geometry: List<List<LatLng>> = emptyList(),
    ) = StreetMatcher.Target(id, "S$id", LatLng(lat, lng), radius, geometry)

    @Test
    fun matchesNearestPointWithinRadius() {
        // ~0.001 deg lat ≈ 111 m, outside b's 100 m radius from the query point.
        val a = target(1, 59.0, 18.0, radius = 100)
        val b = target(2, 59.001, 18.0, radius = 100)
        val match = matcher.match(LatLng(59.0, 18.0), listOf(a, b))
        assertEquals(1L, match?.target?.id)
    }

    @Test
    fun returnsNullWhenOutsideAllRadii() {
        val s = target(1, 59.0, 18.0, radius = 40)
        assertNull(matcher.match(LatLng(60.0, 18.0), listOf(s)))
    }

    @Test
    fun matchesAgainstPolylineNotJustEndpoints() {
        // A street running east-west; the query point sits beside its middle,
        // far from either endpoint but close to the line itself.
        val line = listOf(LatLng(59.0, 18.000), LatLng(59.0, 18.010))
        val street = target(1, 59.0, 18.0, radius = 40, geometry = listOf(line))
        // ~0.0002 deg lat ≈ 22 m north of the line's midpoint.
        val match = matcher.match(LatLng(59.0002, 18.005), listOf(street))
        assertEquals(1L, match?.target?.id)
        assertTrue("distance should be ~22 m", match!!.distanceMeters in 15.0..30.0)
    }

    @Test
    fun polylineDistanceUsesClosestWay() {
        val near = listOf(LatLng(59.0, 18.0), LatLng(59.0, 18.01))
        val far = listOf(LatLng(60.0, 19.0), LatLng(60.0, 19.01))
        val d = StreetMatcher.distanceToPolylineMeters(LatLng(59.00005, 18.005), near)
        assertTrue("expected a few meters, got $d", d < 10.0)
        val dFar = StreetMatcher.distanceToPolylineMeters(LatLng(59.00005, 18.005), far)
        assertTrue(dFar > 100_000.0)
    }
}
