package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class ChatSuggestionBubblesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyNonBlankSuggestionIsAButtonWithItsText() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            ScreenshotTheme {
                ChatSuggestionBubbles(listOf("  Расскажи подробнее ", "", "Покажи пример"), onClick = { clicks += it })
            }
        }
        compose.onNodeWithText("Расскажи подробнее").performClick()
        compose.onNodeWithText("Покажи пример").performClick()
        assertEquals(listOf("Расскажи подробнее", "Покажи пример"), clicks)
    }

    @Test fun blankSuggestionsRenderNothing() {
        compose.setContent { ScreenshotTheme { ChatSuggestionBubbles(listOf(" ", ""), onClick = {}) } }
        compose.onNodeWithTag("chat_suggestions").assertDoesNotExist()
        compose.onAllNodesWithText(" ").assertCountEquals(0)
    }
}
