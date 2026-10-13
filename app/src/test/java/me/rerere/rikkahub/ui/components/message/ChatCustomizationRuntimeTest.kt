package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.components.richtext.splitAssistantParagraphs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ChatCustomizationRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun roleMarkdownSwitchChangesActualDisplayedCharactersImmediately() {
        val display = mutableStateOf(DisplaySetting(enableUserMarkdown = false, enableAssistantMarkdown = true))
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("**Пользователь**")))
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("**Ответ**")))
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides ScreenshotFixtures.settings.copy(displaySetting = display.value)) {
                    Column(Modifier.width(320.dp)) {
                        listOf(user, assistant).forEach { message ->
                            val options = presentMessageDisplay(message, display.value)
                            MessageTextContent((message.parts.single() as UIMessagePart.Text).text,
                                options.enableMarkdown, displayOptions = options.markdownOptions)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("**Пользователь**").assertExists()
        compose.onNodeWithText("Ответ").assertExists()
        compose.onNodeWithText("**Ответ**").assertDoesNotExist()
        compose.runOnIdle { display.value = display.value.copy(enableUserMarkdown = true, enableAssistantMarkdown = false) }
        compose.onNodeWithText("Пользователь").assertExists()
        compose.onNodeWithText("**Пользователь**").assertDoesNotExist()
        compose.onNodeWithText("**Ответ**").assertExists()
    }

    @Test fun separateHeaderNameAndTimestampChoicesUpdateTheirNativeControls() {
        val display = mutableStateOf(DisplaySetting(showUserAvatar = false, showUserName = true,
            userNickname = "Миша", showDateTimeInMessage = true, showUserTimestamp = false))
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Сообщение")))
        compose.setContent {
            ScreenshotTheme {
                ChatMessageHeader(message, presentMessageDisplay(message, display.value), null, null, false)
            }
        }
        compose.onNodeWithTag("chat_message_user_name").assertExists()
        compose.onNodeWithTag("chat_message_user_icon").assertDoesNotExist()
        compose.onNodeWithTag("chat_message_user_time").assertDoesNotExist()
        compose.runOnIdle { display.value = display.value.copy(showUserName = false, showUserTimestamp = true) }
        compose.onNodeWithTag("chat_message_user_name").assertDoesNotExist()
        compose.onNodeWithTag("chat_message_user_time").assertExists()
    }

    @Test fun characterPreviewToggleRevealsOriginalTextAndCollapsesAgain() {
        val text = "Начало " + "а".repeat(70) + " конец сообщения"
        compose.setContent {
            ScreenshotTheme {
                CharacterCollapsibleUserMessageText("message", UserTextDisplayOptions(true, 9, 50), text) { Text(it) }
            }
        }
        compose.onNodeWithText(text).assertDoesNotExist()
        compose.onNodeWithTag("chat_message_user_collapse_toggle").performClick()
        compose.onNodeWithText(text).assertExists()
        compose.onNodeWithTag("chat_message_user_collapse_toggle").performClick()
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test fun missingProviderDoesNotCreateAnEmptyHeaderWhenOtherIdentityChoicesAreHidden() {
        val settings = DisplaySetting(showModelName = false, showModelIcon = false, showProviderInChatMessage = true)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Ответ")))
        compose.setContent {
            ScreenshotTheme {
                ChatMessageHeader(message, presentMessageDisplay(message, settings), null, null, false)
            }
        }
        compose.onNodeWithTag("chat_message_assistant_header").assertDoesNotExist()
    }

    @Test fun paragraphSplittingRetainsRenderedReferenceLinkAndItsActualUriAction() {
        val text = "HTML <b>header</b>\n\n[Open][target]\n\n[target]: https://example.com"
        val split = mutableStateOf(false)
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Column(Modifier.width(320.dp)) {
                        val parts = if (split.value) splitAssistantParagraphs(text) else listOf(text)
                        parts.forEach { MessageTextContent(text = it, markdown = true) }
                    }
                }
            }
        }
        fun openRenderedLink() {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText("Open")
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val links = layout.layoutInput.text.getLinkAnnotations(0, layout.layoutInput.text.length)
            assertEquals("https://example.com", (links.single().item as LinkAnnotation.Url).url)
            node.performTouchInput { click(layout.getBoundingBox(1).center) }
        }
        openRenderedLink()
        compose.runOnIdle { split.value = true }
        openRenderedLink()
        compose.runOnIdle { assertEquals(listOf("https://example.com", "https://example.com"), opened) }
    }
}
