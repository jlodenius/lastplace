package dev.lastplace.app.ui.streets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lastplace.app.data.db.PlannedMove
import dev.lastplace.app.domain.ReminderKind
import dev.lastplace.app.domain.ReparkingOption
import dev.lastplace.app.domain.ReparkingStreet
import dev.lastplace.app.reminders.ReminderCapabilities
import dev.lastplace.app.ui.theme.ParkingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ParkingScreensTest {
    @get:Rule val compose = createComposeRule()

    private val mainName = "Ynglingagatan"
    private val sectionName = "Ynglingagatan vändplan"
    private fun row(id: Long, name: String) = StreetRow(id, name, "3 days 5 hours", "Next cleaning: Fri 29 May, 08:00", null, null)
    private fun active() = ActiveParking(
        sessionId = 7,
        streetName = mainName,
        deadlineText = "Tue 26 May, 08:00",
        remainingText = "2 hours remaining",
        hasDeadline = true,
        streetId = 1,
        urgency = ParkingUrgency.Soon,
    )
    private fun scrollHomeTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("parking-content").performScrollToNode(matcher)
        return compose.onNode(matcher)
    }

    private fun parkedState() = HomeUiState(
        streets = listOf(row(1, mainName), row(2, sectionName)),
        active = active(),
        hasSavedStreets = true,
        isLoading = false,
    )

    private fun suggestedOption(justCleaned: Boolean = true): ReparkingOption {
        val time = ZonedDateTime.parse("2026-05-26T14:00:00+02:00[Europe/Stockholm]")
        return ReparkingOption(ReparkingStreet(2, sectionName, emptyList()), time, time.plusDays(6), justCleaned)
    }

    @Test
    fun loadingDoesNotPretendToBeEmptyOrUnparked() {
        compose.setContent { ParkingTheme { StreetListContent(HomeUiState(isLoading = true)) } }
        compose.onNodeWithText("Loading parking…").assertIsDisplayed()
        compose.onNodeWithText("No saved streets").assertDoesNotExist()
        compose.onNodeWithText("Not currently parked").assertDoesNotExist()
        compose.onNodeWithText("Park where I am").assertDoesNotExist()
    }

    @Test
    fun firstUseHasOneClearAddActionAndNoGpsAction() {
        var additions = 0
        compose.setContent {
            ParkingTheme { StreetListContent(HomeUiState(isLoading = false), onAddStreet = { additions++ }) }
        }
        compose.onNodeWithText("Add your first street").performClick()
        compose.onNodeWithText("Enter cleaning hours from street signs, not the map.").assertExists()
        compose.onAllNodesWithContentDescription("Add street").assertCountEquals(0)
        compose.onNodeWithText("Park where I am").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, additions) }
    }

    @Test
    fun gpsFailureRemainsVisibleAlongsideManualRegistration() {
        var retries = 0
        val error = "No reliable GPS match. Choose a saved street below."
        compose.setContent {
            ParkingTheme {
                StreetListContent(
                    state = parkedState().copy(active = null),
                    gpsError = error,
                    onParkWhereIAm = { retries++ },
                )
            }
        }
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("Or choose a saved street below.").assertIsDisplayed()
        compose.onNodeWithText("Park where I am").performClick()
        compose.onNodeWithText(error).assertExists()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun currentStreetRegistrationIsDisabledAndEditAndFavouriteAreIndependent() {
        var edits = 0
        var favourites = 0
        var registrations = 0
        compose.setContent {
            ParkingTheme {
                SavedStreetRow(
                    row(1, sectionName), isCurrent = true, isBusy = false,
                    onEdit = { edits++ }, onToggleFavourite = { favourites++ }, onPark = { registrations++ },
                )
            }
        }
        compose.onNodeWithText("Parked here").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Edit $sectionName").performClick()
        compose.onNodeWithContentDescription("Favourite $sectionName").performClick()
        compose.runOnIdle {
            assertEquals(1, edits)
            assertEquals(1, favourites)
            assertEquals(0, registrations)
        }
    }

    @Test
    fun endingParkingRequiresConfirmationAndCancelChangesNothing() {
        var ended: Long? = null
        compose.setContent { ParkingTheme { StreetListContent(parkedState(), onEndParking = { ended = it }) } }
        compose.onNodeWithText("End parking").performClick()
        compose.onNodeWithText("End parking on $mainName?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(ended) }
        compose.onNodeWithText("End parking").performClick()
        compose.onNode(hasText("End parking") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(7L, ended) }
    }

    @Test
    fun changingStreetCapturesTheConfirmedSessionAndCancelDoesNotRegister() {
        var registered: Pair<Long, Long?>? = null
        val state = mutableStateOf(parkedState())
        compose.setContent {
            ParkingTheme { StreetListContent(state.value, onPark = { street, session -> registered = street to session }) }
        }
        scrollHomeTo(hasContentDescription("I've parked on $sectionName")).performClick()
        compose.onNodeWithText("Change parked location from $mainName to $sectionName?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(registered) }
        scrollHomeTo(hasContentDescription("I've parked on $sectionName")).performClick()
        compose.runOnIdle { state.value = state.value.copy(active = active().copy(sessionId = 99)) }
        compose.onNodeWithText("I've parked on $sectionName").performClick()
        compose.runOnIdle { assertEquals(2L to 7L, registered) }
    }

    @Test
    fun firstRegistrationIsOneTapWithoutAConfirmation() {
        var registered: Pair<Long, Long?>? = null
        compose.setContent {
            ParkingTheme {
                StreetListContent(parkedState().copy(active = null), onPark = { street, session -> registered = street to session })
            }
        }
        scrollHomeTo(hasContentDescription("I've parked on $sectionName")).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2L to null, registered) }
    }

    @Test
    fun filteredEmptyStateOffersShowAllInsteadOfAnotherFirstUseFlow() {
        var filter: Boolean? = null
        compose.setContent {
            ParkingTheme {
                StreetListContent(
                    parkedState().copy(active = null, streets = emptyList(), daytimeOnly = true),
                    onShowDaytimeOnly = { filter = it },
                )
            }
        }
        scrollHomeTo(hasText("Show all streets")).performClick()
        compose.onNodeWithText("Add your first street").assertDoesNotExist()
        compose.runOnIdle { assertEquals(false, filter) }
    }

    @Test
    fun urgencyDeadlineAndEndActionRemainReachableAtLargeFontOnANarrowLayout() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ParkingTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ActiveParkingCard(
                            active = active().copy(streetName = sectionName),
                            isBusy = false, onEndParking = {}, onOpenReminderSettings = {},
                            reminderContent = { Text("No upcoming deadline warnings") },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Move soon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 hours remaining").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Move before Tue 26 May, 08:00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("End parking").performScrollTo().assertIsDisplayed()
    }

    private fun selectedPlan() = PlannedMoveUi(
        plan = PlannedMove(7, "selected", 2, sectionName, 1L, true, ""),
        streetName = sectionName,
        timeText = "Tue 26 May, 14:00",
        problem = null,
        timePassed = false,
        isAvailableNow = true,
        remindersText = "Notification scheduled: Tue 26 May, 14:00",
    )

    @Test
    fun selectedMoveHasItsOwnCardAndKeepsExplicitArrivalChangeAndCancelActions() {
        val plan = selectedPlan()
        var arrived: PlannedMoveUi? = null
        var cancelled: PlannedMoveUi? = null
        var changes = 0
        compose.setContent {
            ParkingTheme {
                StreetListContent(
                    parkedState().copy(plannedMove = plan),
                    onArrived = { arrived = it },
                    onCancelPlan = { cancelled = it },
                    onOpenReparkingOptions = { changes++ },
                )
            }
        }
        val inCard = hasAnyAncestor(hasTestTag("planned-move-card"))
        compose.onNodeWithText("2 hours remaining").assertIsDisplayed()
        scrollHomeTo(hasText("Planned move") and inCard).assertIsDisplayed()
        scrollHomeTo(hasText(sectionName) and inCard).assertIsDisplayed()
        scrollHomeTo(hasText(plan.timeText) and inCard).assertIsDisplayed()
        scrollHomeTo(hasText("I've parked here") and inCard).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        scrollHomeTo(hasText("Change plan") and inCard).performClick()
        scrollHomeTo(hasText("Cancel plan") and inCard).performClick()
        compose.runOnIdle {
            assertEquals(plan, arrived)
            assertEquals(plan, cancelled)
            assertEquals(1, changes)
        }
    }

    @Test
    fun plannedMoveCardStaysReadableAtLargeFontAndCannotRegisterAnUnavailableDestination() {
        val plan = selectedPlan().copy(isAvailableNow = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ParkingTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        PlannedMoveCard(plan, onCancel = {}, onArrived = {}, onChangePlan = {})
                    }
                }
            }
        }
        compose.onNodeWithText(sectionName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(plan.timeText).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("I've parked here").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Change plan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel plan").performScrollTo().assertIsDisplayed()
        val arrival = compose.onNodeWithText("I've parked here").fetchSemanticsNode()
        val change = compose.onNodeWithText("Change plan").fetchSemanticsNode()
        val cancel = compose.onNodeWithText("Cancel plan").fetchSemanticsNode()
        assertEquals(arrival.size.width, change.size.width)
        assertEquals(change.size.width, cancel.size.width)
        assertEquals(arrival.positionInRoot.x, change.positionInRoot.x, 1f)
        assertEquals(change.positionInRoot.x, cancel.positionInRoot.x, 1f)
        assertTrue(change.positionInRoot.y < cancel.positionInRoot.y)
    }

    @Test
    fun plannedMoveActionsUseAFullWidthPrimaryAndEqualAlignedSecondaryButtons() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                ParkingTheme {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        PlannedMoveCard(selectedPlan(), onCancel = {}, onArrived = {}, onChangePlan = {})
                    }
                }
            }
        }
        val arrival = compose.onNodeWithText("I've parked here").fetchSemanticsNode()
        val change = compose.onNodeWithText("Change plan").fetchSemanticsNode()
        val cancel = compose.onNodeWithText("Cancel plan").fetchSemanticsNode()
        assertEquals(change.size.width.toFloat(), cancel.size.width.toFloat(), 1f)
        assertEquals(change.size.height, cancel.size.height)
        assertEquals(change.positionInRoot.y, cancel.positionInRoot.y, 1f)
        assertEquals(arrival.positionInRoot.x, change.positionInRoot.x, 1f)
        assertEquals(arrival.positionInRoot.x + arrival.size.width, cancel.positionInRoot.x + cancel.size.width, 1f)
        assertTrue(arrival.positionInRoot.y + arrival.size.height <= change.positionInRoot.y)
    }

    @Test
    fun suggestedMoveHasItsOwnCardAndPlanningDoesNotRegisterParking() {
        val option = suggestedOption()
        var selected: ReparkingOption? = null
        var optionsOpened = 0
        var registrations = 0
        compose.setContent {
            ParkingTheme {
                StreetListContent(
                    parkedState().copy(reparkingOptions = listOf(option), hasPreferredTimes = true),
                    onSelectPlan = { selected = it },
                    onOpenReparkingOptions = { optionsOpened++ },
                    onPark = { _, _ -> registrations++ },
                )
            }
        }
        val inCard = hasAnyAncestor(hasTestTag("suggested-move-card"))
        scrollHomeTo(hasText("Suggested move") and inCard).assertIsDisplayed()
        scrollHomeTo(hasText(sectionName) and inCard).assertIsDisplayed()
        scrollHomeTo(hasText("Plan this move") and inCard).performClick()
        scrollHomeTo(hasText("View options") and inCard).performClick()
        compose.runOnIdle {
            assertEquals(option, selected)
            assertEquals(1, optionsOpened)
            assertEquals(0, registrations)
        }
    }

    @Test
    fun suggestedMoveWithoutDestinationDoesNotOfferAnInvalidPlanAction() {
        compose.setContent {
            ParkingTheme {
                SuggestedMoveCard(null, hasPreferredTimes = true, onPlan = {}, onOpenOptions = {}, onChooseTimes = {})
            }
        }
        compose.onNodeWithText("No available destination").assertExists()
        compose.onNodeWithText("Plan this move").assertDoesNotExist()
    }

    @Test
    fun busyMoveCardsDisableTheirMutationActions() {
        compose.setContent {
            ParkingTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SuggestedMoveCard(suggestedOption(), true, onPlan = {}, onOpenOptions = {}, onChooseTimes = {}, isBusy = true)
                    PlannedMoveCard(selectedPlan(), onCancel = {}, onArrived = {}, onChangePlan = {}, isBusy = true)
                }
            }
        }
        compose.onNodeWithText("Plan this move").assertIsNotEnabled()
        compose.onNodeWithText("I've parked here").assertIsNotEnabled()
        compose.onNodeWithText("Change plan").assertIsNotEnabled()
        compose.onNodeWithText("Cancel plan").assertIsNotEnabled()
    }

    @Test
    fun optionsStartWithDestinationsAfterTheDeadlineWithoutTheExplanatoryPreamble() {
        val state = mutableStateOf(parkedState().copy(reparkingOptions = listOf(suggestedOption(false)), hasPreferredTimes = false))
        compose.setContent { ParkingTheme { ReparkingOptionsContent(state.value) } }
        compose.onNodeWithText("Move before Tue 26 May, 08:00").assertIsDisplayed()
        val deadline = compose.onNodeWithText("Move before Tue 26 May, 08:00").fetchSemanticsNode()
        val card = compose.onNodeWithTag("reparking-option-2").fetchSemanticsNode()
        assertEquals(with(compose.density) { 16.dp.toPx() }, card.positionInRoot.y - deadline.positionInRoot.y - deadline.size.height, 1f)
        compose.onNodeWithText("Based on saved cleaning hours, not live parking spaces. Planning never registers parking.").assertDoesNotExist()
        compose.onNodeWithText("No preferred re-parking times selected").assertDoesNotExist()
        compose.onNodeWithText("Choose times").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(reparkingOptions = listOf(suggestedOption()), hasPreferredTimes = true, plannedMove = selectedPlan())
        }
        compose.onNodeWithText("Planned move").assertExists()
        compose.onNodeWithText("Up to three just-cleaned destinations at your preferred times, latest opportunity first. They may not be available now.").assertDoesNotExist()
        compose.onNodeWithText("Your selected plan stays fixed. Choosing another option replaces it, not your current parking.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(reparkingOptions = listOf(suggestedOption(false)), plannedMove = null) }
        compose.onNodeWithText("No suitable opportunity at your preferred times is available before this deadline. Alternatives available now are ordered by longest stay.").assertDoesNotExist()
    }

    @Test
    fun emptyOptionsAndFailedActionsStillHaveFeedback() {
        compose.setContent {
            ParkingTheme { ReparkingOptionsContent(parkedState(), actionError = "The plan changed. Please choose again.") }
        }
        compose.onNodeWithText("No re-parking options available.").assertIsDisplayed()
        compose.onNodeWithText("The plan changed. Please choose again.").assertIsDisplayed()
    }

    @Test
    fun invalidSelectedPlanDoesNotReplaceTheCurrentDeadline() {
        val plan = PlannedMoveUi(
            plan = PlannedMove(7, "selected", 2, sectionName, 1L, true, ""),
            streetName = sectionName, timeText = "Tue 26 May, 14:00", problem = "The saved cleaning hours changed.",
            timePassed = true, isAvailableNow = false, remindersText = "Plan reminders cancelled",
        )
        compose.setContent {
            ParkingTheme { StreetListContent(parkedState().copy(plannedMove = plan)) }
        }
        compose.onNodeWithText("2 hours remaining").assertIsDisplayed()
        compose.onNodeWithText("Move before Tue 26 May, 08:00").assertExists()
        scrollHomeTo(hasText("The saved cleaning hours changed.")).assertIsDisplayed()
        scrollHomeTo(hasText("Plan reminders cancelled")).assertIsDisplayed()
    }

    @Test
    fun overdueParkingUsesExplicitMoveNowStatus() {
        compose.setContent {
            ParkingTheme {
                ActiveParkingCard(
                    active().copy(urgency = ParkingUrgency.Overdue, remainingText = "Cleaning deadline passed"),
                    isBusy = false, onEndParking = {}, onOpenReminderSettings = {}, reminderContent = {},
                )
            }
        }
        compose.onNodeWithText("Move now — cleaning deadline passed").assertExists()
        compose.onNodeWithText("Move before Tue 26 May, 08:00").assertExists()
    }

    @Test
    fun noDeadlineDoesNotInventACountdownOrClaimLegalParking() {
        compose.setContent {
            ParkingTheme {
                ActiveParkingCard(
                    active().copy(hasDeadline = false, urgency = ParkingUrgency.NoDeadline, remainingText = ""),
                    isBusy = false, onEndParking = {}, onOpenReminderSettings = {}, reminderContent = {},
                )
            }
        }
        compose.onNodeWithText("No cleaning deadline in saved hours").assertExists()
        compose.onNodeWithText("2 hours remaining").assertDoesNotExist()
        compose.onNodeWithText("Park anytime").assertDoesNotExist()
    }

    @Test
    fun blockedMoveChannelDoesNotMislabelDeadlineDelivery() {
        compose.setContent {
            ParkingTheme {
                ReminderDeliveryBlockers(ReminderCapabilities(moveChannelAllowed = false), ReminderKind.PARKING_DEADLINE)
            }
        }
        compose.onNodeWithText("Deadline notifications blocked.").assertDoesNotExist()
        compose.onNodeWithText("Planned move notifications blocked.").assertDoesNotExist()
    }

    @Test
    fun schedulingStatusAndDeliveryBlockersStayVisibleTogether() {
        compose.setContent {
            ParkingTheme {
                ActiveParkingCard(
                    active(), isBusy = false, onEndParking = {}, onOpenReminderSettings = {},
                    reminderContent = {
                        Text("No upcoming deadline warnings")
                        ReminderDeliveryBlockers(
                            ReminderCapabilities(appNotificationsAllowed = false, preciseAlarmsAllowed = false),
                            ReminderKind.PARKING_DEADLINE,
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("No upcoming deadline warnings").assertExists()
        compose.onNodeWithText("Notifications blocked for LastPlace.").assertExists()
        compose.onNodeWithText("Precise reminders unavailable. Android may delay reminders.").assertExists()
    }

    @Test
    fun emptyPreferredTimesAreNotPresentedAsAMissedOpportunity() {
        var chooseTimes = 0
        compose.setContent {
            ParkingTheme {
                SuggestedMoveCard(
                    option = suggestedOption(false),
                    hasPreferredTimes = false,
                    onPlan = {},
                    onOpenOptions = {},
                    onChooseTimes = { chooseTimes++ },
                )
            }
        }
        compose.onNodeWithText("No preferred re-parking times selected").assertExists()
        compose.onNodeWithText("Choose times").performClick()
        compose.onNodeWithText("No preferred-time move available.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, chooseTimes) }
    }
}
