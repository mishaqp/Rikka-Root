package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTranslationTokenRuntimeTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    @Test fun `translation title toggles content through the whole accessible header`() {
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Column(Modifier.width(width.dp)) {
                    CollapsibleTranslationText("Текст перевода") {}
                }
            }
        }
        val header = compose.onNodeWithContentDescription("Свернуть перевод")
            .assertHasClickAction().fetchSemanticsNode().boundsInRoot
        assertTrue("The entire header is clickable", header.width >= width - 20f)
        compose.onNodeWithText("Текст перевода").assertIsDisplayed()
        compose.onNodeWithText("Перевод").performClick()
        compose.onNodeWithText("Текст перевода").assertDoesNotExist()
        compose.onNodeWithContentDescription("Развернуть перевод").performClick()
        compose.onNodeWithText("Текст перевода").assertIsDisplayed()
    }

    @Test fun `token usage setting controls the complete accessible row`() {
        val showTokens = mutableStateOf(true)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            createdAt = LocalDateTime.parse("2026-01-02T12:00:00"),
            finishedAt = LocalDateTime.parse("2026-01-02T12:00:02"),
            usage = TokenUsage(promptTokens = 1_200, cachedTokens = 1_000, completionTokens = 80, totalTokens = 1_280),
        )
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalSettings provides ScreenshotFixtures.settings.copy(
                    displaySetting = ScreenshotFixtures.settings.displaySetting.copy(showTokenUsage = showTokens.value),
                )) {
                    Box(Modifier.width(width.dp)) { ChatMessageNerdLine(message) }
                }
            }
        }
        val description = "Входные токены: 1200, Токены из кэша: 1000, Выходные токены: 80, Скорость: 40.0 ток/с, Длительность: 2.0 с"
        val row = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Token statistics stay within the message width", row.right <= width)
        compose.runOnIdle { showTokens.value = false }
        compose.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    @Test fun `translation keeps citation callbacks after reopening`() {
        var selectedCitation: String? = null
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Column(Modifier.width(width.dp)) {
                    CollapsibleTranslationText("Текст [citation,docs](abc123)") { selectedCitation = it }
                }
            }
        }
        compose.onNodeWithContentDescription("Свернуть перевод").performClick()
        compose.onNodeWithContentDescription("Развернуть перевод").performClick()
        compose.onNodeWithText("docs").performClick()
        compose.runOnIdle { assertEquals("abc123", selectedCitation) }
    }

    @Test fun `pending translation can collapse and then display the translated result`() {
        val content = mutableStateOf("Перевод...")
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Column(Modifier.width(width.dp)) { CollapsibleTranslationText(content.value) {} }
            }
        }
        compose.onNodeWithTag("chat_message_translation_loading").assertIsDisplayed()
        compose.onNodeWithContentDescription("Свернуть перевод").performClick()
        compose.onNodeWithTag("chat_message_translation_loading").assertDoesNotExist()
        compose.runOnIdle { content.value = "Готовый перевод" }
        compose.onNodeWithContentDescription("Развернуть перевод").performClick()
        compose.onNodeWithText("Готовый перевод").assertIsDisplayed()
        compose.onNodeWithTag("chat_message_translation_loading").assertDoesNotExist()
    }

    @Test fun `Markdown and HTML translations keep selected chat font and saved size ratio`() {
        val settings = ScreenshotFixtures.settings.copy(
            displaySetting = ScreenshotFixtures.settings.displaySetting.copy(fontSizeRatio = 1.4f),
        )
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalSettings provides settings) {
                    // A resolved custom font arrives through this same inherited text style.
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 28.sp,
                        lineHeight = 39.2.sp,
                    )) {
                        Column(Modifier.width(width.dp)) {
                            CollapsibleTranslationText("Markdown перевод") {}
                            CollapsibleTranslationText("<p>HTML перевод</p>") {}
                        }
                    }
                }
            }
        }
        listOf("Markdown перевод", "HTML перевод").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val style = layouts.single().layoutInput.style
            assertEquals("$text preserves the inherited chat family", FontFamily.Serif, style.fontFamily)
            assertEquals("$text applies the saved size ratio", 21.7f, style.fontSize.value, .01f)
            assertEquals("$text scales translation line height", 30.38f, style.lineHeight.value, .01f)
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "width{0}_font{1}") fun cases(): List<Array<Any>> =
            listOf(320, 411).flatMap { width -> listOf(1f, 1.3f).map { font -> arrayOf(width, font) } }
    }
}
