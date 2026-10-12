package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class MessageActionsDisplaySettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchesFollowStoredChoicesAndChangeOnlyTheirOwnField() {
        val restored = DisplaySetting(showTokenUsage = false, showUserMessageActions = false, bubbleOpacity = 0.4f)
        val persisted = mutableStateOf(restored)
        compose.setContent {
            MaterialTheme {
                Surface {
                    MessageActionsDisplaySettings(persisted.value, onUpdate = { change ->
                        persisted.value = change(persisted.value)
                    })
                }
            }
        }

        compose.onNodeWithText("Действия и подсказки").assertExists()
        compose.onNodeWithTag("setting_confirm_assistant_regeneration").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("setting_insert_suggestion_only").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("setting_show_produced_files").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(restored.copy(
                confirmAssistantRegeneration = true,
                insertSuggestionOnly = false,
                showProducedFiles = false,
            ), persisted.value)
        }
    }
}
