package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ToolTimelineDisplaySettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchesUpdateStoredToolChoicesWithoutChangingThinkingChoices() {
        val persisted = mutableStateOf(DisplaySetting(showThinkingContent = false, autoCloseThinking = false))
        compose.setContent {
            MaterialTheme {
                Surface {
                    ToolTimelineDisplaySettings(persisted.value, onUpdate = { change ->
                        persisted.value = change(persisted.value)
                    })
                }
            }
        }

        compose.onNodeWithTag("setting_tool_summary").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("setting_tool_result_images").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("setting_tool_auto_collapse").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(persisted.value.showToolSummary)
            assertTrue(persisted.value.hideToolResultImages)
            assertFalse(persisted.value.autoCollapseToolSteps)
            assertFalse(persisted.value.showThinkingContent)
            assertFalse(persisted.value.autoCloseThinking)
        }
    }

    @Test fun switchesFollowRestoredPreferencesAndChangeOnlyTheirOwnField() {
        val restored = DisplaySetting(
            showToolSummary = false,
            hideToolResultImages = true,
            autoCollapseToolSteps = false,
            showThinkingContent = false,
            autoCloseThinking = true,
            bubbleOpacity = 0.4f,
        )
        val persisted = mutableStateOf(DisplaySetting())
        compose.setContent {
            MaterialTheme {
                Surface {
                    ToolTimelineDisplaySettings(persisted.value, onUpdate = { change ->
                        persisted.value = change(persisted.value)
                    })
                }
            }
        }

        compose.runOnIdle { persisted.value = restored }
        compose.onNodeWithTag("setting_tool_summary").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("setting_tool_result_images").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("setting_tool_auto_collapse").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(restored.copy(
                showToolSummary = true,
                hideToolResultImages = false,
                autoCollapseToolSteps = true,
            ), persisted.value)
        }
    }
}
