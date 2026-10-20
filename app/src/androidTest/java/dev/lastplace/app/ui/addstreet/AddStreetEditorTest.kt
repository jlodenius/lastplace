package dev.lastplace.app.ui.addstreet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.parking.StreetDeletionContext
import dev.lastplace.app.ui.theme.ParkingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class AddStreetEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun incompleteNewHoursExplainMissingInputAndRequireAcknowledgmentBeforeSave() {
        var state by mutableStateOf(AddStreetUiState(name = "Ynglingagatan vändplan"))
        var saves = 0
        compose.setContent {
            ParkingTheme {
                AddStreetEditor(state, onBack = {}, onAcknowledge = { state = state.copy(cleaningHoursAcknowledged = it) }, onSave = { saves++ })
            }
        }
        compose.onNodeWithText("Choose weekday, start time, end time.").performScrollTo().assertIsDisplayed()
        val save = compose.onNode(hasText("Add street") and hasClickAction())
        save.performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(rules = listOf(RuleInput(2, 480, 840))) }
        save.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I've checked these cleaning hours").performScrollTo().performClick()
        save.performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun narrowLargeFontLayoutKeepsEveryWeekdayAndBothTimeActionsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ParkingTheme {
                    Box(Modifier.width(320.dp)) {
                        AddStreetEditor(AddStreetUiState(name = "Ynglingagatan vändplan"), onBack = {})
                    }
                }
            }
        }
        (1..7).forEach {
            val day = DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.getDefault())
            compose.onNodeWithText(day).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Choose start time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose end time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use current location").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun nameOnlyEditDoesNotAskForScheduleAcknowledgment() {
        val rules = listOf(RuleInput(2, 480, 840))
        compose.setContent {
            ParkingTheme {
                AddStreetEditor(AddStreetUiState(isEditing = true, name = "Ynglingagatan vändplan", rules = rules, originalRules = rules), onBack = {})
            }
        }
        compose.onNodeWithText("I've checked these cleaning hours").assertDoesNotExist()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled()
    }

    @Test
    fun dirtyBackCanKeepEditingOrExplicitlyDiscard() {
        var backs = 0
        compose.setContent {
            ParkingTheme {
                AddStreetEditor(AddStreetUiState(name = "Unsaved section", hasUnsavedChanges = true), onBack = { backs++ })
            }
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnIdle { assertEquals(0, backs) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun searchFailureOffersRetryWithoutClaimingNoMatchesOrLosingManualName() {
        var retries = 0
        compose.setContent {
            ParkingTheme {
                AddStreetEditor(
                    AddStreetUiState(name = "Ynglingagatan vändplan", search = StreetSearchState.Failed("Ynglingagatan vändplan", "Offline")),
                    onBack = {}, onRetrySearch = { retries++ },
                )
            }
        }
        compose.onNodeWithText("Street search failed: Offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry search").performScrollTo().performClick()
        compose.onNodeWithText("Ynglingagatan vändplan").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun deletingCurrentStreetNamesSavedEntryAndExplainsSessionAndReminderConsequences() {
        val saved = StreetWithRules(Street(id = 17, name = "Ynglingagatan vändplan", lat = 59.3, lng = 18.0), emptyList())
        val context = StreetDeletionContext(saved, 8, 17, "plan", 9)
        compose.setContent {
            ParkingTheme {
                AddStreetEditor(AddStreetUiState(isEditing = true, name = "Unsaved new name", deletionContext = context), onBack = {})
            }
        }
        compose.onNodeWithText("Delete Ynglingagatan vändplan?").assertIsDisplayed()
        compose.onNodeWithText("Parking on this street will end. Its plan and both reminder groups will be cleared.").assertIsDisplayed()
        compose.onNodeWithText("Keep street").assertIsEnabled()
    }
}
