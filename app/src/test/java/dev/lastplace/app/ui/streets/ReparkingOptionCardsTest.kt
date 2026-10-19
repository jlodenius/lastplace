package dev.lastplace.app.ui.streets

import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.domain.ReparkingOption
import dev.lastplace.app.domain.ReparkingStreet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ReparkingOptionCardsTest {
    private val time = ZonedDateTime.parse("2026-05-26T14:00:00+02:00[Europe/Stockholm]")
    private fun option(id: Long, justCleaned: Boolean = true) = ReparkingOption(
        ReparkingStreet(id, "Street $id", emptyList()), time, time.plusDays(6), justCleaned,
    )
    private fun plan(id: Long, justCleaned: Boolean = true) = PlannedMoveUi(
        plan = PlannedMove(7, "chosen", id, "Street $id", time.toInstant().toEpochMilli(), justCleaned, ""),
        streetName = "Street $id",
        timeText = "Tue 14:00",
        problem = null,
        timePassed = false,
        isAvailableNow = true,
        remindersText = null,
    )

    @Test
    fun noPlanKeepsTheOriginalRecommendationOrder() {
        val cards = buildReparkingOptionCards(listOf(option(3), option(1), option(2)), null)
        assertEquals(listOf(3L, 1L, 2L), cards.map { it.streetId })
        assertTrue(cards.first().recommended)
        assertTrue(cards.all { it.plannedMove == null && !it.isSelectedOption })
    }

    @Test
    fun selectedOptionAndPlanBecomeOneCardRatherThanFour() {
        val selected = plan(1)
        val candidate = option(1)
        val cards = buildReparkingOptionCards(listOf(candidate, option(2), option(3)), selected)
        assertEquals(3, cards.size)
        assertEquals(listOf(1L, 2L, 3L), cards.map { it.streetId })
        assertSame(selected, cards.first().plannedMove)
        assertSame(candidate, cards.first().option)
        assertTrue(cards.first().isSelectedOption)
    }

    @Test
    fun selectingALowerRankedOptionPinsItWithoutChangingAlternativeRanking() {
        val cards = buildReparkingOptionCards(listOf(option(1), option(2), option(3)), plan(3))
        assertEquals(listOf(3L, 1L, 2L), cards.map { it.streetId })
        assertFalse(cards.first().recommended)
        assertTrue(cards[1].recommended)
        assertTrue(cards.first().isSelectedOption)
    }

    @Test
    fun planOutsideCurrentSuggestionsIsRetainedWithOnlyTwoAlternatives() {
        val selected = plan(9)
        val cards = buildReparkingOptionCards(listOf(option(1), option(2), option(3)), selected)
        assertEquals(listOf(9L, 1L, 2L), cards.map { it.streetId })
        assertSame(selected, cards.first().plannedMove)
        assertNull(cards.first().option)
        assertFalse(cards.first().isSelectedOption)
    }

    @Test
    fun changedTimeOnTheSameStreetDoesNotDuplicateOrSilentlyReplaceThePlan() {
        val selected = plan(1)
        val newOpportunity = option(1).copy(moveAt = time.plusDays(1))
        val cards = buildReparkingOptionCards(listOf(newOpportunity, option(2)), selected)
        assertEquals(2, cards.size)
        assertSame(selected, cards.first().plannedMove)
        assertSame(newOpportunity, cards.first().option)
        assertFalse(cards.first().isSelectedOption)
    }

    @Test
    fun invalidAndMissedPlansKeepTheirProblemsAlongsideASelectableNewOpportunity() {
        val invalid = plan(1).copy(problem = "The schedule changed.")
        val invalidCard = buildReparkingOptionCards(listOf(option(1)), invalid).single()
        assertSame(invalid, invalidCard.plannedMove)
        assertFalse(invalidCard.isSelectedOption)
        val missed = plan(1).copy(timePassed = true)
        val missedCard = buildReparkingOptionCards(listOf(option(1, justCleaned = false)), missed).single()
        assertSame(missed, missedCard.plannedMove)
        assertFalse(missedCard.isSelectedOption)
    }

    @Test
    fun availableNowPlanIsSelectedUntilMissedWithoutMatchingTheChangingClockExactly() {
        val selected = plan(1, justCleaned = false)
        val current = option(1, justCleaned = false).copy(moveAt = time.plusSeconds(30))
        assertTrue(buildReparkingOptionCards(listOf(current), selected).single().isSelectedOption)
        assertFalse(buildReparkingOptionCards(listOf(current), selected.copy(timePassed = true)).single().isSelectedOption)
    }

    @Test
    fun removedStreetOrEmptyRecommendationsDoNotHideTheSelectedPlan() {
        val selected = plan(1).copy(problem = "The street was removed.")
        val card = buildReparkingOptionCards(emptyList(), selected).single()
        assertSame(selected, card.plannedMove)
        assertNull(card.option)
        assertTrue(buildReparkingOptionCards(emptyList(), null).isEmpty())
    }
}
