package dev.lastplace.app.ui.streets

import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.domain.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StreetRowsTest {

    private val engine = ScheduleEngine()
    private val zone = ZoneId.of("Europe/Stockholm")
    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 5, 26, hour, minute, 0, 0, zone)

    private fun rule(day: Int = 2, start: Int = 9 * 60, end: Int = 14 * 60) =
        CleaningRule(streetId = 1, dayOfWeek = day, startMinuteOfDay = start, endMinuteOfDay = end)

    private fun street(id: Long, vararg rules: CleaningRule) = StreetWithRules(
        Street(id = id, name = "Street $id", lat = 59.3, lng = 18.0),
        rules.map { it.copy(streetId = id) },
    )

    @Test
    fun activeCleaningNeverShowsFreeTimeOrParkAnytime() {
        val row = buildStreetRows(listOf(street(1, rule())), at(10), engine, false).single()
        assertFalse(row.isAvailable)
        assertNull(row.freeFor)
        assertNull(row.nextCleaningText)
        assertTrue(row.unavailableUntilText!!.contains("14:00"))
    }

    @Test
    fun listChangesAvailabilityAtExactCleaningBoundaries() {
        val streets = listOf(street(1, rule()))
        assertTrue(buildStreetRows(streets, at(8, 59), engine, false).single().isAvailable)
        assertFalse(buildStreetRows(streets, at(9), engine, false).single().isAvailable)
        assertFalse(buildStreetRows(streets, at(13, 59), engine, false).single().isAvailable)
        val after = buildStreetRows(streets, at(14), engine, false).single()
        assertTrue(after.isAvailable)
        assertNull(after.unavailableUntilText)
        assertEquals("6 days 19 hours", after.freeFor)
        assertNotNull(after.nextCleaningText)
    }

    @Test
    fun availableStreetsRankBeforeCleaningStreetsWithLongestFreeTimeFirst() {
        val streets = listOf(
            street(1, rule()),
            street(2, rule(day = 3)),
            street(3),
            street(4, rule(day = 5)),
        )
        val rows = buildStreetRows(streets, at(10), engine, false)
        assertEquals(listOf(3L, 4L, 2L, 1L), rows.map { it.id })
        assertTrue(rows.first().isAvailable)
        assertNull(rows.first().freeFor)
    }

    @Test
    fun favouritesBreakExactTiesWithoutOverridingAvailabilityOrParkingDuration() {
        fun favourite(street: StreetWithRules) = street.copy(street = street.street.copy(isFavourite = true))
        val streets = listOf(
            street(1, rule(day = 5)),
            favourite(street(2, rule(day = 5))),
            favourite(street(3, rule(day = 3))),
            favourite(street(4, rule())),
            street(5),
            favourite(street(6)),
        )
        val rows = buildStreetRows(streets, at(10), engine, false)
        assertEquals(listOf(6L, 5L, 2L, 1L, 3L, 4L), rows.map { it.id })
        assertTrue(rows.first().isFavourite)
        assertFalse(rows[1].isFavourite)
    }

    @Test
    fun favouritesDoNotMoveLaterOpeningStreetsAheadOfEarlierOnes() {
        val earlier = street(1, rule())
        val tied = street(2, rule()).let { it.copy(street = it.street.copy(isFavourite = true)) }
        val later = street(3, rule(end = 16 * 60)).let { it.copy(street = it.street.copy(isFavourite = true)) }
        val rows = buildStreetRows(listOf(earlier, later, tied), at(10), engine, false)
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.id })
    }

    @Test
    fun daytimeFilterIncludesAnyDaytimeWindowRegardlessOfWeekdayOrCurrentAvailability() {
        val streets = listOf(
            street(1, rule()),
            street(2, rule(start = 0, end = 6 * 60)),
            street(3),
            street(4, rule(day = 5)),
            street(5, rule(day = 4), rule(day = 3, start = 22 * 60, end = 6 * 60)),
        )
        val rows = buildStreetRows(streets, at(10), engine, true)
        assertEquals(listOf(4L, 5L, 1L), rows.map { it.id })
        assertTrue(rows.all { it.hasDaytimeCleaning })
        assertFalse(rows.last().isAvailable)
        assertEquals(5, buildStreetRows(streets, at(10), engine, false).size)
    }

    @Test
    fun daytimeBadgeShowsActualTimesAndDoesNotIncludeNighttimeWindows() {
        val row = buildStreetRows(
            listOf(street(1, rule(), rule(day = 3, start = 22 * 60, end = 6 * 60))),
            at(10), engine, false,
        ).single()
        assertTrue(row.hasDaytimeCleaning)
        assertTrue(row.daytimeCleaningText!!.contains("09:00–14:00"))
        assertFalse(row.daytimeCleaningText.contains("22:00"))
    }

    @Test
    fun customStreetSectionsRemainIndependentWithTheirOwnHours() {
        val main = street(1, rule(day = 5)).let { it.copy(street = it.street.copy(name = "Ynglingagatan")) }
        val turning = street(2, rule(day = 3)).let { it.copy(street = it.street.copy(name = "Ynglingagatan vändplan")) }
        val rows = buildStreetRows(listOf(main, turning), at(10), engine, false)
        assertEquals(listOf("Ynglingagatan", "Ynglingagatan vändplan"), rows.map { it.name })
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertTrue(rows[0].nextCleaningText!!.contains("29"))
        assertTrue(rows[1].nextCleaningText!!.contains("27"))
    }

    @Test
    fun sameWeekdayAndSharedGeometryDoNotMergeNamedStreetSections() {
        val main = street(1, rule(day = 5, start = 8 * 60)).let {
            it.copy(street = it.street.copy(name = "Ynglingagatan", geometry = "[[[59.3,18.0],[59.4,18.0]]]"))
        }
        val turning = main.copy(
            street = main.street.copy(id = 2, name = "Ynglingagatan vändplan"),
            rules = listOf(rule(day = 5, start = 10 * 60)),
        )
        val rows = buildStreetRows(listOf(main, turning), at(10), engine, false)
        assertEquals(2, rows.size)
        assertEquals("Ynglingagatan", rows.single { it.id == 1L }.name)
        assertEquals("Ynglingagatan vändplan", rows.single { it.id == 2L }.name)
        assertTrue(rows.single { it.id == 1L }.nextCleaningText!!.contains("08:00"))
        assertTrue(rows.single { it.id == 2L }.nextCleaningText!!.contains("10:00"))
    }

    @Test
    fun nighttimeAndUnrestrictedStreetsHaveNoDaytimeBadge() {
        val streets = listOf(street(1), street(2, rule(start = 22 * 60, end = 6 * 60)))
        assertTrue(buildStreetRows(streets, at(10), engine, false).none { it.hasDaytimeCleaning })
        assertTrue(buildStreetRows(streets, at(10), engine, true).isEmpty())
    }
}
