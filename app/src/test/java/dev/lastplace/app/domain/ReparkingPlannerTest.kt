package dev.lastplace.app.domain

import dev.lastplace.app.domain.model.CleaningWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReparkingPlannerTest {
    private val planner = ReparkingPlanner()
    private val zone = ZoneId.of("Europe/Stockholm")
    private val afternoon = setOf(LocalTime.of(14, 0))
    private val both = afternoon + LocalTime.of(6, 0)
    private fun at(day: Int, hour: Int = 0, minute: Int = 0) = ZonedDateTime.of(2026, 5, day, hour, minute, 0, 0, zone)
    private fun window(day: Int, start: Int = 9, end: Int = 14) =
        CleaningWindow(DayOfWeek.of(day), LocalTime.of(start, 0), LocalTime.of(end, 0))
    private fun street(id: Long, vararg windows: CleaningWindow, favourite: Boolean = false) =
        ReparkingStreet(id, "Street $id", windows.toList(), favourite)

    @Test
    fun wednesdayMidnightDeadlineSuggestsTuesdayAfternoonCleaningEnd() {
        val current = street(1, window(3, 0, 6))
        val target = street(2, window(2))
        val result = planner.options(listOf(current, target), 1, at(27), afternoon, at(25, 8)).single()
        assertEquals(2L, result.street.id)
        assertEquals(at(26, 14), result.moveAt)
        assertEquals(at(26, 9).plusWeeks(1), result.nextCleaningStart)
        assertTrue(result.justCleaned)
    }

    @Test
    fun bothPreferredTimesAreUsedAndLatestOpportunityWins() {
        val afternoonStreet = street(2, window(2))
        val morningStreet = street(3, window(3, 0, 6))
        val streets = listOf(afternoonStreet, morningStreet)
        assertEquals(
            listOf(3L, 2L),
            planner.options(streets, 1, at(27, 9), both, at(25)).map { it.street.id },
        )
        assertEquals(
            listOf(2L),
            planner.options(streets, 1, at(27, 9), afternoon, at(25)).map { it.street.id },
        )
    }

    @Test
    fun neverInventsACleaningEndOnADifferentWeekday() {
        val target = street(2, window(1))
        val result = planner.options(listOf(target), 1, at(27), afternoon, at(25, 8)).single()
        assertEquals(at(25, 14), result.moveAt)
        assertTrue(result.justCleaned)
    }

    @Test
    fun missingPreferredCleaningEndFallsBackToLongestAvailableStayNow() {
        val result = planner.options(
            listOf(street(2, window(1)), street(3, window(5))),
            1, at(27), afternoon, at(26, 8),
        )
        assertEquals(listOf(2L, 3L), result.map { it.street.id })
        assertTrue(result.none { it.justCleaned })
        assertTrue(result.all { it.moveAt == at(26, 8) })
    }

    @Test
    fun missedOpportunityFallsBackRatherThanUsingAnUnpreferredTime() {
        val result = planner.options(
            listOf(street(2, window(2)), street(3, window(3, 0, 6))),
            1, at(27, 9), afternoon, at(26, 14, 1),
        )
        assertEquals(2L, result.first().street.id)
        assertTrue(result.none { it.justCleaned })
    }

    @Test
    fun opportunityRemainsVisibleDuringItsDisplayedMinute() {
        val result = planner.options(
            listOf(street(2, window(2))), 1, at(27), afternoon, at(26, 14).plusSeconds(45),
        ).single()
        assertTrue(result.justCleaned)
        assertEquals(at(26, 14), result.moveAt)
    }

    @Test
    fun noPreferredTimesMeansLongestStayFallback() {
        val result = planner.options(listOf(street(2, window(2))), 1, at(27), emptySet(), at(26, 8)).single()
        assertFalse(result.justCleaned)
    }

    @Test
    fun excludesCurrentStreetAndStreetsStillBeingCleanedAtCandidateTime() {
        val result = planner.options(
            listOf(street(1, window(2)), street(2, window(2), window(2, 13, 16)), street(3, window(2))),
            1, at(27), afternoon, at(26, 10),
        )
        assertEquals(listOf(3L), result.map { it.street.id })
    }

    @Test
    fun touchingWindowsMustAllEndBeforeParkingIsAllowed() {
        val result = planner.options(
            listOf(street(2, window(2), window(2, 14, 16))),
            1, at(27), afternoon + LocalTime.of(16, 0), at(26, 10),
        ).single()
        assertEquals(at(26, 16), result.moveAt)
    }

    @Test
    fun cleaningEndAtDeadlineIsNotAValidEasyOpportunity() {
        assertTrue(planner.options(listOf(street(2, window(2))), 1, at(26, 14), afternoon, at(26, 10)).isEmpty())
    }

    @Test
    fun overnightCleaningUsesTheFollowingDate() {
        val result = planner.options(
            listOf(street(2, window(2, 22, 6))), 1, at(27, 9), both, at(26, 10),
        ).single()
        assertEquals(at(27, 6), result.moveAt)
        assertEquals(at(26, 22).plusWeeks(1), result.nextCleaningStart)
    }

    @Test
    fun easyOptionsNeverMixInFallbackStreetsEvenWhenThoseWouldAllowLongerParking() {
        val result = planner.options(listOf(street(2, window(2)), street(3)), 1, at(27), afternoon, at(26, 8))
        assertEquals(listOf(2L), result.map { it.street.id })
        assertTrue(result.single().justCleaned)
    }

    @Test
    fun favouritesOnlyBreakTiesAfterMoveTimeAndParkingDuration() {
        val result = planner.options(
            listOf(
                street(2, window(2)),
                street(3, window(2), favourite = true),
                street(4, window(2), window(3), favourite = true),
            ),
            1, at(27), afternoon, at(26, 8),
        )
        assertEquals(listOf(3L, 2L, 4L), result.map { it.street.id })
        val later = planner.options(
            listOf(street(2, window(1), favourite = true), street(3, window(2))),
            1, at(27), afternoon, at(25, 8),
        )
        assertEquals(listOf(3L, 2L), later.map { it.street.id })
    }

    @Test
    fun fallbackFavouritesOnlyBreakDurationTiesAndNeverOverrideAvailability() {
        val result = planner.options(
            listOf(
                street(2, window(5)),
                street(3, window(5), favourite = true),
                street(4, window(3), favourite = true),
                street(5, window(2), favourite = true),
            ),
            1, at(27), emptySet(), at(26, 10),
        )
        assertEquals(listOf(3L, 2L, 4L), result.map { it.street.id })
    }

    @Test
    fun returnsOnlyThreeDistinctDestinationsWithTheirLatestOpportunity() {
        val result = planner.options(
            (2L..7L).map { street(it, window(1), window(2)) },
            1, at(27), afternoon, at(25, 8),
        )
        assertEquals(listOf(2L, 3L, 4L), result.map { it.street.id })
        assertTrue(result.all { it.moveAt == at(26, 14) })
    }

    @Test
    fun unrestrictedStreetWinsFallbackAndOverdueParkingStillGetsAnAlternative() {
        val result = planner.options(
            listOf(street(2), street(3, window(5), favourite = true)), 1, at(26), afternoon, at(26, 10),
        )
        assertEquals(2L, result.first().street.id)
        assertTrue(result.none { it.justCleaned })
        assertTrue(planner.options(listOf(street(2)), 1, null, afternoon, at(26, 10)).isEmpty())
    }

    @Test
    fun nextWeekAndDaylightSavingUseLocalCleaningTimes() {
        val now = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone)
        val result = planner.options(
            listOf(street(2, window(7, 0, 6))), 1, now.plusDays(2), both, now,
        ).single()
        assertEquals(LocalTime.of(6, 0), result.moveAt.toLocalTime())
        assertEquals(25, result.moveAt.dayOfMonth)
        assertEquals(3600, result.moveAt.offset.totalSeconds)
        val nextWeek = planner.options(listOf(street(2, window(1))), 1, at(26).plusWeeks(1), afternoon, at(27)).single()
        assertEquals(at(25, 14).plusWeeks(1), nextWeek.moveAt)
    }
}
