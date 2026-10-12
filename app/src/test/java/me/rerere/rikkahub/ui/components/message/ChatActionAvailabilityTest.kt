package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.tts.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose controls and callbacks; assertions use their existing accessible labels. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatActionAvailabilityTest {
    @get:Rule val compose = createComposeRule()
    private val tts = RecordingTts()

    @Test fun rolesControlTtsAndTranslationWhileSharedActionsRemainAvailable() {
        val role = mutableStateOf(MessageRole.USER)
        val translationEnabled = mutableStateOf(true)
        compose.setContent {
            TestTheme {
                Column {
                    val message = UIMessage(role = role.value, parts = listOf(UIMessagePart.Text("Message")))
                    ChatMessageActionButtons(
                        message, MessageNode.of(message), {}, {}, {},
                        onTranslate = if (translationEnabled.value) ({ _, _ -> }) else null,
                    )
                }
            }
        }
        MessageRole.entries.forEach { nextRole ->
            compose.runOnIdle { role.value = nextRole }
            listOf(R.string.copy, R.string.regenerate, R.string.more_options).forEach { resource ->
                compose.onNodeWithContentDescription(label(resource)).assertExists()
            }
            if (nextRole == MessageRole.ASSISTANT) {
                compose.onNodeWithContentDescription(label(R.string.tts)).assertExists()
                compose.onNodeWithContentDescription(label(R.string.translate)).assertExists()
            } else {
                compose.onNodeWithContentDescription(label(R.string.tts)).assertDoesNotExist()
                compose.onNodeWithContentDescription(label(R.string.translate)).assertDoesNotExist()
            }
        }
        compose.runOnIdle { role.value = MessageRole.ASSISTANT; translationEnabled.value = false }
        compose.onNodeWithContentDescription(label(R.string.translate)).assertDoesNotExist()
    }

    @Test fun regenerateConfirmsUserMessagesAndDispatchesOtherRolesImmediately() {
        val role = mutableStateOf(MessageRole.USER)
        var regenerations = 0
        compose.setContent {
            TestTheme {
                Column {
                    val message = UIMessage(role = role.value, parts = listOf(UIMessagePart.Text("Message")))
                    ChatMessageActionButtons(message, MessageNode.of(message), {}, { regenerations++ }, {})
                }
            }
        }
        compose.onNodeWithContentDescription(label(R.string.regenerate)).performClick()
        assertEquals(0, regenerations)
        compose.onNodeWithText(label(R.string.regenerate_confirm_message)).assertExists()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals(0, regenerations)
        compose.onNodeWithContentDescription(label(R.string.regenerate)).performClick()
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        assertEquals(1, regenerations)
        listOf(MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.TOOL).forEach { nextRole ->
            compose.runOnIdle { role.value = nextRole }
            compose.onNodeWithContentDescription(label(R.string.regenerate)).performClick()
        }
        assertEquals(4, regenerations)
    }

    @Test fun speechAvailabilityAndSpeakingStateControlTheProductionToggle() {
        val message = UIMessage.assistant("Read this response")
        compose.setContent {
            TestTheme {
                Column { ChatMessageActionButtons(message, MessageNode.of(message), {}, {}, {}) }
            }
        }
        val control = compose.onNodeWithContentDescription(label(R.string.tts))
        compose.runOnIdle { tts.isAvailable.value = false }
        control.assertIsNotEnabled()
        compose.runOnIdle { tts.isAvailable.value = true }
        control.performClick()
        assertEquals(listOf("speak:Read this response"), tts.invocations)
        control.performClick()
        assertEquals(listOf("speak:Read this response", "stop"), tts.invocations)
    }

    @Test fun readOnlyButtonsKeepSharedActionsAndSpeechWhileDisablingRegeneration() {
        val role = mutableStateOf(MessageRole.USER)
        compose.setContent {
            TestTheme {
                Column {
                    val message = UIMessage(role = role.value, parts = listOf(UIMessagePart.Text("Message")))
                    ChatMessageActionButtons(message, MessageNode.of(message), {}, {}, {},
                        onTranslate = { _, _ -> }, readOnly = true)
                }
            }
        }
        MessageRole.entries.forEach { nextRole ->
            compose.runOnIdle { role.value = nextRole }
            compose.onNodeWithContentDescription(label(R.string.copy)).assertIsEnabled()
            compose.onNodeWithContentDescription(label(R.string.more_options)).assertIsEnabled()
            compose.onNodeWithContentDescription(label(R.string.regenerate)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(label(R.string.translate)).assertDoesNotExist()
            if (nextRole == MessageRole.ASSISTANT) {
                compose.onNodeWithContentDescription(label(R.string.tts)).assertIsEnabled()
            } else {
                compose.onNodeWithContentDescription(label(R.string.tts)).assertDoesNotExist()
            }
        }
    }

    @Test fun readOnlySheetKeepsSelectPreviewAndShareWithoutMutationActions() {
        compose.setContent {
            TestTheme {
                ChatMessageActionsSheet(
                    message = UIMessage.assistant("Answer"), model = null,
                    onDelete = {}, onEdit = {}, onShare = {}, onFork = {}, onSelectAndCopy = {},
                    onToggleFavorite = {}, onWebViewPreview = {}, onDismissRequest = {}, readOnly = true,
                )
            }
        }
        listOf(R.string.select_and_copy, R.string.render_with_webview, R.string.share)
            .forEach { compose.onNodeWithText(label(it)).assertExists() }
        listOf(R.string.edit, R.string.create_fork, R.string.chat_message_add_favorite, R.string.delete)
            .forEach { compose.onNodeWithText(label(it)).assertDoesNotExist() }
    }

    @Test fun moreButtonDispatchesTheProductionSheetCallback() {
        var opens = 0
        val message = UIMessage.assistant("Answer")
        compose.setContent {
            TestTheme { Column { ChatMessageActionButtons(message, MessageNode.of(message), {}, {}, { opens++ }) } }
        }
        compose.onNodeWithContentDescription(label(R.string.more_options)).performClick()
        assertEquals(1, opens)
    }

    @Test fun copyButtonWritesTheActualMessageTextToThePlatformClipboard() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("First paragraph"), UIMessagePart.Reasoning("Private reasoning"),
            UIMessagePart.Text("Second paragraph"),
        ))
        compose.setContent {
            TestTheme { Column { ChatMessageActionButtons(message, MessageNode.of(message), {}, {}, {}) } }
        }
        compose.onNodeWithContentDescription(label(R.string.copy)).performClick()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("First paragraph\n\nSecond paragraph", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun sheetActionsKeepTheirCallbacksAndDismissBeforeDispatch() {
        val calls = mutableListOf<String>()
        compose.setContent {
            TestTheme {
                ChatMessageActionsSheet(
                    message = UIMessage.assistant("**Answer**"), model = null,
                    onDelete = { calls += "delete" }, onEdit = { calls += "edit" },
                    onShare = { calls += "share" }, onFork = { calls += "fork" },
                    onSelectAndCopy = { calls += "select" }, onToggleFavorite = { calls += "favorite" },
                    onWebViewPreview = { calls += "preview" }, onDismissRequest = { calls += "dismiss" },
                )
            }
        }
        listOf(
            R.string.select_and_copy to "select", R.string.render_with_webview to "preview",
            R.string.edit to "edit", R.string.share to "share", R.string.create_fork to "fork",
            R.string.chat_message_add_favorite to "favorite", R.string.delete to "delete",
        ).forEach { (resource, callback) ->
            calls.clear()
            compose.onNodeWithText(label(resource)).performClick()
            assertEquals(callback, listOf("dismiss", callback), calls)
        }
    }

    @Test fun sheetPreviewRequiresNonblankTextAndFavoriteRequiresACallbackForEveryRole() {
        val message = mutableStateOf(UIMessage.user(""))
        val favoriteEnabled = mutableStateOf(false)
        val favorite = mutableStateOf(false)
        compose.setContent {
            TestTheme {
                ChatMessageActionsSheet(
                    message = message.value, model = null, onDelete = {}, onEdit = {}, onShare = {}, onFork = {},
                    onSelectAndCopy = {}, isFavorite = favorite.value,
                    onToggleFavorite = if (favoriteEnabled.value) ({}) else null,
                    onWebViewPreview = {}, onDismissRequest = {},
                )
            }
        }
        MessageRole.entries.forEach { role ->
            listOf(
                emptyList(), listOf(UIMessagePart.Text(" \n")), listOf(UIMessagePart.Image("file:///image.png")),
                listOf(UIMessagePart.Reasoning("Reasoning is not previewable message text")),
            ).forEach { parts ->
                compose.runOnIdle { message.value = UIMessage(role = role, parts = parts) }
                compose.onNodeWithText(label(R.string.render_with_webview)).assertDoesNotExist()
                listOf(R.string.select_and_copy, R.string.edit, R.string.share, R.string.create_fork, R.string.delete)
                    .forEach { compose.onNodeWithText(label(it)).assertExists() }
            }
            compose.runOnIdle { message.value = UIMessage(role = role, parts = listOf(UIMessagePart.Text("Answer"))) }
            compose.onNodeWithText(label(R.string.render_with_webview)).assertExists()
        }
        compose.onNodeWithText(label(R.string.chat_message_add_favorite)).assertDoesNotExist()
        compose.runOnIdle { favoriteEnabled.value = true }
        compose.onNodeWithText(label(R.string.chat_message_add_favorite)).assertExists()
        compose.runOnIdle { favorite.value = true }
        compose.onNodeWithText(label(R.string.chat_message_remove_favorite)).assertExists()
        compose.onNodeWithText(label(R.string.chat_message_add_favorite)).assertDoesNotExist()
    }

    @Test fun branchSelectorNavigatesAlternativesAndDoesNotDispatchPastEitherBoundary() {
        val messages = listOf(UIMessage.assistant("First"), UIMessage.assistant("Second"), UIMessage.assistant("Third"))
        val node = mutableStateOf(MessageNode(messages = messages))
        val updates = mutableListOf<MessageNode>()
        compose.setContent {
            TestTheme { ChatMessageBranchSelector(node.value, onUpdate = { updates += it; node.value = it }) }
        }
        compose.onNodeWithText("1/3").assertExists()
        compose.onNodeWithContentDescription("Prev").performClick()
        assertEquals(0, updates.size)
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("2/3").assertExists()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("3/3").assertExists()
        compose.onNodeWithContentDescription("Next").performClick()
        assertEquals(2, updates.size)
        compose.onNodeWithContentDescription("Prev").performClick()
        assertEquals(listOf(1, 2, 1), updates.map { it.selectIndex })
        assertEquals(listOf(node.value.id, node.value.id, node.value.id), updates.map { it.id })
        assertEquals(listOf(messages, messages, messages), updates.map { it.messages })
        compose.runOnIdle { node.value = MessageNode.of(messages.first()) }
        compose.onNodeWithContentDescription("Prev").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next").assertDoesNotExist()
    }

    @Test fun errorControlsDismissByIdentityAndOnlyOfferClearAllForMultipleErrors() {
        val first = ChatError(error = Throwable("First error"))
        val second = ChatError(error = Throwable())
        val errors = mutableStateOf(listOf(first))
        val dismissed = mutableListOf<kotlin.uuid.Uuid>()
        var cleared = 0
        compose.setContent {
            TestTheme { ErrorCardsDisplay(errors.value, { dismissed += it }, { cleared++ }) }
        }
        compose.onNodeWithText("First error").assertExists()
        compose.onNodeWithText(label(R.string.chat_page_clear_all_errors)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.chat_page_dismiss_error)).performClick()
        assertEquals(listOf(first.id), dismissed)
        compose.runOnIdle { errors.value = listOf(first, second) }
        compose.waitForIdle()
        compose.onNodeWithText("Unknown error").assertExists()
        compose.onNodeWithText(label(R.string.chat_page_clear_all_errors)).performClick()
        assertEquals(1, cleared)
    }

    @Composable private fun TestTheme(content: @Composable () -> Unit) {
        ScreenshotTheme { CompositionLocalProvider(LocalTTSState provides tts, content = content) }
    }

    private fun label(resource: Int) = RuntimeEnvironment.getApplication().getString(resource)

    private class RecordingTts : CustomTtsState {
        override val isAvailable = MutableStateFlow(true)
        override val isSpeaking = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val currentChunk = MutableStateFlow(0)
        override val totalChunks = MutableStateFlow(0)
        override val playbackState = MutableStateFlow(PlaybackState())
        val invocations = mutableListOf<String>()
        override fun speak(text: String, flushCalled: Boolean) { invocations += "speak:$text"; isSpeaking.value = true }
        override fun stop() { invocations += "stop"; isSpeaking.value = false }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun skipNext() = Unit
        override fun fastForward(ms: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun cleanup() = Unit
    }
}
