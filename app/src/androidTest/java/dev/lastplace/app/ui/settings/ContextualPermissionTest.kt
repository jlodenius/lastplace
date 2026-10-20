package dev.lastplace.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lastplace.app.ui.theme.ParkingTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContextualPermissionTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var isolatedContext: Context
    private lateinit var preferences: SharedPreferences
    private var preferencesName: String? = null
    private val registry = RecordingRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    @Before
    fun isolatePreferencesWithoutChangingDevicePermission() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Notification permission requires Android 13 or later", Build.VERSION.SDK_INT >= 33)
        assumeTrue(
            "Use a test fixture with notification permission denied; this test never revokes permission",
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        )
        val name = "contextual_permission_test_${UUID.randomUUID()}"
        preferencesName = name
        preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "notification_permission") preferences else super.getSharedPreferences(name, mode)
        }
    }

    @After
    fun removeOnlyTestPreferences() {
        preferencesName?.let {
            preferences.edit().clear().commit()
            context.deleteSharedPreferences(it)
        }
    }

    @Test
    fun nullTriggerDoesNotShowRationaleOrLaunchAPermissionRequest() {
        var handled = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides isolatedContext,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                ParkingTheme { ContextualNotificationPermission(null) { handled++ } }
            }
        }
        compose.onNodeWithText("Allow parking reminders?").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, handled)
            assertEquals(0, registry.launches)
            assertFalse(preferences.getBoolean("rationale_seen", false))
        }
    }

    @Test
    fun explicitTriggerShowsRationaleOnceAndNotNowNeverLaunchesPermission() {
        val trigger = mutableStateOf<String?>(null)
        var handled = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides isolatedContext,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                ParkingTheme {
                    ContextualNotificationPermission(trigger.value) {
                        handled++
                        trigger.value = null
                    }
                }
            }
        }
        compose.onNodeWithText("Allow parking reminders?").assertDoesNotExist()
        compose.runOnIdle { trigger.value = "registration-1" }
        compose.onNodeWithText("Allow parking reminders?").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, handled)
            assertEquals(0, registry.launches)
            assertTrue(preferences.getBoolean("rationale_seen", false))
        }
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Allow parking reminders?").assertDoesNotExist()
        compose.runOnIdle { trigger.value = "registration-2" }
        compose.onNodeWithText("Allow parking reminders?").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, handled)
            assertEquals(0, registry.launches)
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        var launches = 0
            private set

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches++
            dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
        }
    }
}
