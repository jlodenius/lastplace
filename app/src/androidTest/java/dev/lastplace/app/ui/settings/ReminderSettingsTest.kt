package dev.lastplace.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lastplace.app.ui.theme.ParkingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun preferredTimesComeImmediatelyAfterDeliveryAndBeforeBothReminderGroups() {
        compose.setContent { ParkingTheme { SettingsScreen(onBack = {}) } }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Preferred re-parking times").fetchSemanticsNodes().isNotEmpty()
        }
        val headings = listOf(
            "Reminder delivery", "Preferred re-parking times",
            "Parking deadline warnings", "Planned move reminders",
        )
        val positions = headings.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue(positions.zipWithNext().all { (before, after) -> before < after })
    }

    @Test
    fun preferredTimesSectionLinkStillScrollsToItsMovedPosition() {
        compose.setContent {
            ParkingTheme { SettingsScreen(onBack = {}, initialSection = SettingsSection.REPARKING_TIMES) }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Preferred re-parking times").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Preferred re-parking times").assertIsDisplayed()
    }

    @Test
    fun labeledSwitchIsOneToggleTargetAndTheLabelChangesItsState() {
        val enabled = mutableStateOf(true)
        var changes = 0
        compose.setContent {
            ParkingTheme {
                ReminderToggleRow("Parking deadline warnings", enabled.value, onCheckedChange = {
                    enabled.value = it
                    changes++
                })
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithText("Parking deadline warnings").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test
    fun labeledSwitchRemainsUsableAtLargeFontSize() {
        val enabled = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ParkingTheme {
                    ReminderToggleRow("Planned move reminders", enabled.value, onCheckedChange = { enabled.value = it })
                }
            }
        }
        compose.onNodeWithText("Planned move reminders").assertIsOff().performClick().assertIsOn()
    }
}
