package dev.lastplace.app.ui.addstreet

import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.domain.model.LatLng
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.osm.OsmRequestException
import dev.lastplace.app.osm.OsmService
import dev.lastplace.app.osm.StreetSuggestion
import dev.lastplace.app.parking.ParkingService
import dev.lastplace.app.parking.StreetDeletionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class AddStreetViewModelTest {
    private val streets = mock(StreetRepository::class.java)
    private val osm = mock(OsmService::class.java)
    private val location = mock(LocationProvider::class.java)
    private val parking = mock(ParkingService::class.java)
    private val rule = RuleInput(2, 8 * 60, 14 * 60)
    private val existing = StreetWithRules(
        Street(
            id = 17,
            name = "Ynglingagatan vändplan",
            lat = 59.34,
            lng = 18.05,
            notes = "Keep this section independent",
            geometry = "59.3400,18.0500;59.3500,18.0600",
            isFavourite = true,
        ),
        listOf(CleaningRule(id = 29, streetId = 17, dayOfWeek = 2, startMinuteOfDay = 480, endMinuteOfDay = 840)),
    )

    @Before
    fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun model(id: Long = -1) = AddStreetViewModel(streets, osm, location, id, parking)

    @Test
    fun newRulesHaveNoAcceptedDayOrTimesAndRequireAcknowledgment() = runTest {
        val model = model()
        val initial = model.state.value.rules.single()
        assertNull(initial.dayOfWeek)
        assertNull(initial.startMinute)
        assertNull(initial.endMinute)
        assertNotNull(initial.missingFields)
        assertFalse(model.state.value.canSave)
        model.onQueryChange("Manual street")
        model.updateRule(0, rule.copy(endMinute = null))
        model.acknowledgeCleaningHours(true)
        assertFalse(model.state.value.canSave)
        model.updateRule(0, rule)
        assertFalse(model.state.value.cleaningHoursAcknowledged)
        assertFalse(model.state.value.canSave)
        model.acknowledgeCleaningHours(true)
        assertTrue(model.state.value.canSave)
        model.updateRule(0, rule.copy(startMinute = 9 * 60))
        assertFalse(model.state.value.cleaningHoursAcknowledged)
        assertFalse(model.state.value.canSave)
        verifyNoInteractions(location)
    }

    @Test
    fun nameOnlyEditPreservesRulesFavouriteNotesAndExactGeometryWithoutMapRequests() = runTest {
        `when`(streets.getStreetWithRules(17)).thenReturn(existing)
        val model = model(17)
        assertTrue(model.state.value.isLoading)
        assertFalse(model.state.value.canSave)
        runCurrent()
        assertEquals(existing.street.name, model.state.value.name)
        assertFalse(model.state.value.hasUnsavedChanges)
        assertFalse(model.state.value.needsAcknowledgment)
        model.onQueryChange("Ynglingagatan turning area")
        assertTrue(model.state.value.canSave)
        assertTrue(model.state.value.hasUnsavedChanges)
        model.save()
        model.save()
        runCurrent()
        val expectedStreet = existing.street.copy(name = "Ynglingagatan turning area")
        val expectedRules = existing.rules.map { it.copy(id = 0) }
        verify(streets, times(1)).updateStreet(expectedStreet, expectedRules)
        assertTrue(model.state.value.saved)
        assertFalse(model.state.value.hasUnsavedChanges)
        verifyNoInteractions(osm, location)
    }

    @Test
    fun pointOnlyCustomEntryDoesNotAutoRegenerateNameOrGeometryOnLoadAndSave() = runTest {
        val pointOnly = existing.copy(street = existing.street.copy(geometry = null))
        `when`(streets.getStreetWithRules(17)).thenReturn(pointOnly)
        val model = model(17)
        runCurrent()
        assertEquals("Ynglingagatan vändplan", model.state.value.name)
        assertTrue(model.state.value.geometry.isEmpty())
        model.save()
        runCurrent()
        verify(streets).updateStreet(pointOnly.street, pointOnly.rules.map { it.copy(id = 0) })
        verifyNoInteractions(osm, location)
    }

    @Test
    fun existingEntryWithoutRulesLoadsWithoutInventingARestriction() = runTest {
        val withoutRules = existing.copy(rules = emptyList())
        `when`(streets.getStreetWithRules(17)).thenReturn(withoutRules)
        val model = model(17)
        runCurrent()
        assertTrue(model.state.value.rules.isEmpty())
        assertFalse(model.state.value.needsAcknowledgment)
        assertTrue(model.state.value.canSave)
        model.save()
        runCurrent()
        verify(streets).updateStreet(withoutRules.street, emptyList())
        verifyNoInteractions(osm, location)
    }

    @Test
    fun changedScheduleRequiresAcknowledgmentButReturningToOriginalDoesNot() = runTest {
        `when`(streets.getStreetWithRules(17)).thenReturn(existing)
        val model = model(17)
        runCurrent()
        model.updateRule(0, rule.copy(endMinute = 15 * 60))
        assertTrue(model.state.value.needsAcknowledgment)
        assertFalse(model.state.value.canSave)
        model.acknowledgeCleaningHours(true)
        assertTrue(model.state.value.canSave)
        model.updateRule(0, rule.copy(endMinute = 16 * 60))
        assertFalse(model.state.value.cleaningHoursAcknowledged)
        model.updateRule(0, rule)
        assertFalse(model.state.value.needsAcknowledgment)
        assertFalse(model.state.value.hasUnsavedChanges)
        assertTrue(model.state.value.canSave)
    }

    @Test
    fun searchFailureIsDistinctFromNoMatchesAndRetryCanSucceedWithoutLosingForm() = runTest {
        val suggestion = StreetSuggestion("Ynglingagatan", "Ynglingagatan — Stockholm", LatLng(59.3, 18.0))
        `when`(osm.searchStreets("Ynglingagatan", null))
            .thenAnswer { throw OsmRequestException("Offline") }
            .thenReturn(listOf(suggestion))
        `when`(osm.searchStreets("Other street", null)).thenReturn(emptyList())
        val model = model()
        model.updateRule(0, rule)
        model.acknowledgeCleaningHours(true)
        model.onQueryChange("Ynglingagatan")
        assertTrue(model.state.value.search is StreetSearchState.Searching)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(model.state.value.search is StreetSearchState.Failed)
        assertEquals(listOf(rule), model.state.value.rules)
        assertTrue(model.state.value.cleaningHoursAcknowledged)
        model.retrySearch()
        runCurrent()
        assertEquals(listOf(suggestion), (model.state.value.search as StreetSearchState.Results).streets)
        model.onQueryChange("Other street")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(model.state.value.search is StreetSearchState.NoMatches)
        model.onQueryChange("ab")
        assertEquals(StreetSearchState.ShortQuery, model.state.value.search)
        model.onQueryChange("")
        assertEquals(StreetSearchState.Idle, model.state.value.search)
    }

    @Test
    fun staleResponseCannotReplaceNewQueryEvenWhenOldRequestReturnsAfterCancellation() = runTest {
        val model = model()
        val oldResult = StreetSuggestion("Old result", "Old result", LatLng(1.0, 1.0))
        `when`(osm.searchStreets("Older query", null)).thenAnswer {
            model.onQueryChange("Newer query")
            listOf(oldResult)
        }
        `when`(osm.searchStreets("Newer query", null)).thenReturn(emptyList())
        model.onQueryChange("Older query")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("Newer query", model.state.value.name)
        assertEquals(StreetSearchState.Searching("Newer query"), model.state.value.search)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(StreetSearchState.NoMatches("Newer query"), model.state.value.search)
    }

    @Test
    fun staleSuggestionTapCannotReplaceAChangedCustomNameOrStartGeometryCapture() = runTest {
        val suggestion = StreetSuggestion("Ynglingagatan", "Ynglingagatan", LatLng(59.3, 18.0))
        `when`(osm.searchStreets("Ynglingagatan", null)).thenReturn(listOf(suggestion))
        val model = model()
        model.onQueryChange("Ynglingagatan")
        advanceTimeBy(300)
        runCurrent()
        model.onQueryChange("Ynglingagatan vändplan")
        model.onSuggestionPicked(suggestion)
        assertEquals("Ynglingagatan vändplan", model.state.value.name)
        assertFalse(model.state.value.geometryLoading)
        assertFalse(model.state.value.hasLocation)
        verify(osm, never()).fetchGeometry(suggestion.name, suggestion.point)
    }

    @Test
    fun locationDenialAndNoFixRemainVisibleWithManualAlternativeAndFormIntact() = runTest {
        `when`(location.current()).thenReturn(null)
        val model = model()
        model.onQueryChange("Manual section")
        model.updateRule(0, rule)
        model.locationPermissionDenied()
        assertTrue(model.state.value.locationError!!.contains("Search by street name"))
        model.useCurrentLocation()
        assertTrue(model.state.value.isLocating)
        runCurrent()
        assertFalse(model.state.value.isLocating)
        assertTrue(model.state.value.locationError!!.contains("No location fix"))
        assertEquals("Manual section", model.state.value.name)
        assertEquals(listOf(rule), model.state.value.rules)
        verifyNoInteractions(osm)
    }

    @Test
    fun deletionIsPreparedThenCancelledOrConfirmedWithExactStaleGuardContext() = runTest {
        val context = StreetDeletionContext(existing, 8, 17, "plan", 9)
        `when`(streets.getStreetWithRules(17)).thenReturn(existing)
        `when`(parking.deletionContext(17)).thenReturn(context)
        `when`(parking.deleteStreet(context)).thenReturn(false)
        val model = model(17)
        runCurrent()
        model.requestDelete()
        runCurrent()
        assertEquals(context, model.state.value.deletionContext)
        model.cancelDelete()
        assertNull(model.state.value.deletionContext)
        verify(parking, never()).deleteStreet(context)
        model.requestDelete()
        runCurrent()
        model.confirmDelete()
        model.confirmDelete()
        runCurrent()
        verify(parking, times(1)).deleteStreet(context)
        assertFalse(model.state.value.saved)
        assertNull(model.state.value.deletionContext)
        assertTrue(model.state.value.error!!.contains("Nothing was deleted"))
        assertEquals(existing.street.name, model.state.value.name)
    }

    @Test
    fun failedSaveKeepsEditsAndAcknowledgmentForRetry() = runTest {
        val newStreet = Street(name = "Manual street", lat = 0.0, lng = 0.0, matchRadiusMeters = 60)
        val rules = listOf(CleaningRule(streetId = 0, dayOfWeek = 2, startMinuteOfDay = 480, endMinuteOfDay = 840))
        `when`(streets.addStreet(newStreet, rules)).thenThrow(IllegalStateException("Disk full")).thenReturn(Unit)
        val model = model()
        model.onQueryChange("Manual street")
        model.updateRule(0, rule)
        model.acknowledgeCleaningHours(true)
        model.save()
        assertTrue(model.state.value.mutationInProgress)
        runCurrent()
        assertFalse(model.state.value.saved)
        assertTrue(model.state.value.error!!.contains("Your edits are still here"))
        assertEquals(listOf(rule), model.state.value.rules)
        assertTrue(model.state.value.cleaningHoursAcknowledged)
        assertTrue(model.state.value.canSave)
        model.save()
        runCurrent()
        assertTrue(model.state.value.saved)
    }
}
