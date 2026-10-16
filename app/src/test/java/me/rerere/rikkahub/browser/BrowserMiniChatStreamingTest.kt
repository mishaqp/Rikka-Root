package me.rerere.rikkahub.browser

import android.app.Application
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserMiniChatStreamingTest {
    @get:Rule val compose = createComposeRule()
    private val assistantMessage = UIMessage.assistant("Ответ начался")
    private val conversation = MutableStateFlow(Conversation(assistantId = Uuid.random(),
        messageNodes = listOf(MessageNode.of(assistantMessage))))
    private val views = mutableListOf<WebView>()

    @Before fun install() {
        val service = mockk<ChatService>()
        every { service.getConversationFlow(conversation.value.id) } returns conversation
        every { service.getProcessingStatusFlow(conversation.value.id) } returns MutableStateFlow("Генерация")
        startKoin { modules(module { single { service } }) }
    }

    @After fun close() {
        views.forEach { BrowserController.unbind(it); it.destroy() }
        stopKoin()
    }

    private fun openCard() {
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.browser_mini_chat_open)).performClick()
        compose.waitForIdle()
    }

    @Test fun `the mini chat updates a streamed message without changing its id or message count`() {
        val conversationId = conversation.value.id
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(411.dp, 891.dp)) {
            BrowserMiniChat(conversationId = conversationId)
        } } }
        openCard()
        compose.onNodeWithText("Ответ начался").assertIsDisplayed()
        compose.runOnIdle {
            conversation.value = conversation.value.copy(messageNodes = listOf(MessageNode.of(
                assistantMessage.copy(parts = listOf(UIMessagePart.Text("Ответ начался и продолжился"))))))
        }
        compose.onNodeWithText("Ответ начался и продолжился").assertIsDisplayed()
    }

    @Test fun `expanding and collapsing mini chat keeps one live browser and its current page`() {
        val progress = mutableStateOf(100)
        val conversationId = conversation.value.id
        val canGoBack = mutableStateOf(false)
        val canGoForward = mutableStateOf(false)
        val currentUrl = mutableStateOf("https://example.test/releases")
        val currentTitle = mutableStateOf("Страница")
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(411.dp, 891.dp)) {
            BrowserView(
                onWebViewReady = { views += it; BrowserController.bindForeground(it, conversationId.toString()) },
                onUrlChange = {}, onTitleChange = {}, onLoadProgress = {},
                onCanGoBackChange = {}, onCanGoForwardChange = {},
                canGoBackState = canGoBack, canGoForwardState = canGoForward,
                currentUrlState = currentUrl,
                currentTitleState = currentTitle, loadProgressState = progress,
                onClose = {}, onBackTap = {}, onForwardTap = {}, onRefreshTap = {},
                onStopAi = {}, onNavigate = {}, initialUrl = "https://example.test/releases",
                conversationId = conversationId,
            )
        } } }
        compose.waitForIdle()
        val original = views.single()
        openCard()
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.browser_mini_chat_close)).performClick()
        compose.runOnIdle { progress.value = 45 }
        compose.waitForIdle()
        assertEquals(1, views.size)
        assertSame(original, BrowserController.activeWebView())
        assertEquals("https://example.test/releases", org.robolectric.Shadows.shadowOf(original).lastLoadedUrl)
        assertEquals(conversation.value.id.toString(),
            (BrowserController.currentMode() as BrowserController.Mode.Foreground).callerConvId)
    }
}
