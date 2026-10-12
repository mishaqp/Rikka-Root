package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the same controls and update transforms that the production preference page uses. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class MessageBehaviorDisplaySettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun togglesApplyToCurrentPreferencesAndCollapseRetainsItsChosenLineCount() {
        val persisted = mutableStateOf(DisplaySetting(bubbleOpacity = 0.4f, showThinkingContent = false))
        compose.setContent {
            MaterialTheme {
                Surface {
                    MessageBehaviorDisplaySettings(persisted.value, onUpdate = { change -> persisted.value = change(persisted.value) })
                }
            }
        }
        compose.onNodeWithTag("setting_user_message_collapse").assertIsOn()
        compose.onNodeWithTag("setting_user_message_actions").assertIsOn()
        compose.onNodeWithTag("setting_user_message_collapse_lines").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        compose.runOnIdle { assertEquals(12, persisted.value.userMessageCollapseLines) }

        compose.onNodeWithTag("setting_user_message_collapse").performClick().assertIsOff()
        compose.onNodeWithTag("setting_user_message_collapse_lines").assertDoesNotExist()
        compose.onNodeWithTag("setting_user_message_actions").performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(persisted.value.collapseLongUserMessages)
            assertFalse(persisted.value.showUserMessageActions)
            assertEquals(12, persisted.value.userMessageCollapseLines)
            assertEquals(0.4f, persisted.value.bubbleOpacity)
            assertFalse(persisted.value.showThinkingContent)
        }
        compose.onNodeWithTag("setting_user_message_collapse").performClick().assertIsOn()
        compose.onNodeWithTag("setting_user_message_collapse_lines").assertIsDisplayed()
        compose.runOnIdle { assertEquals(12, persisted.value.userMessageCollapseLines) }
    }

    @Test fun controlsFollowExternallyRestoredSettings() {
        val persisted = mutableStateOf(DisplaySetting())
        compose.setContent {
            MaterialTheme {
                Surface {
                    MessageBehaviorDisplaySettings(persisted.value, onUpdate = { change -> persisted.value = change(persisted.value) })
                }
            }
        }
        compose.runOnIdle {
            persisted.value = persisted.value.copy(collapseLongUserMessages = false, showUserMessageActions = false, userMessageCollapseLines = 20)
        }
        compose.onNodeWithTag("setting_user_message_collapse").assertIsOff()
        compose.onNodeWithTag("setting_user_message_actions").assertIsOff()
        compose.onNodeWithTag("setting_user_message_collapse_lines").assertDoesNotExist()
    }
}
