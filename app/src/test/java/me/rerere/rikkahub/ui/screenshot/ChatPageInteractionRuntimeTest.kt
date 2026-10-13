package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.asr.ASRState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.browser.BrowserActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.uuid.Uuid

/** ChatPage callbacks are reached through native Compose controls, without recording PNGs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatPageInteractionRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    private val settings = MutableStateFlow(ScreenshotFixtures.settings)
    private val navigation = mutableListOf<androidx.navigation3.runtime.NavKey>()

    @Before fun install() {
        fixture = ChatBeforeFixture(File("/tmp/rikka-chat-audit/audit-image.png"))
        fixture.install()
        every { fixture.vm.settings } returns settings
        val store = mockk<SettingsStore>(relaxed = true)
        every { store.settingsFlow } returns settings
        loadKoinModules(module { single { store } })
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = listOf(
            MessageNode.of(UIMessage.assistant("Завершённый ответ"))), chatSuggestions = listOf(SUGGESTION))
    }
    @After fun close() { fixture.close() }

    private fun render() {
        val asr = mockk<CustomAsrState>(relaxed = true)
        val tts = mockk<CustomTtsState>(relaxed = true)
        every { asr.state } returns MutableStateFlow(ASRState())
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        compose.setContent {
            ScreenshotTheme {
                val currentSettings = settings.collectAsState().value
                Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                    val toaster = rememberToasterState()
                    SharedTransitionLayout {
                        CompositionLocalProvider(LocalNavController provides Navigator(navigation), LocalSettings provides currentSettings,
                            LocalASRState provides asr, LocalTTSState provides tts, LocalToaster provides toaster,
                            LocalSharedTransitionScope provides this) {
                            Box {
                                ChatPage(fixture.conversation.id, null, emptyList())
                                Toaster(state = toaster, alignment = Alignment.TopCenter)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun terminalCapsuleRoutesToTheConversationWorkspace() {
        val id = Uuid.parse("50000000-0000-0000-0000-000000000004")
        every { fixture.vm.assistant } returns MutableStateFlow(ScreenshotFixtures.assistant.copy(workspaceId = id))
        val repository = GlobalContext.get().get<WorkspaceRepository>()
        every { repository.listFlow() } returns flowOf(listOf(WorkspaceEntity(id.toString(), "Рабочая область", "/workspace", createdAt = 0, updatedAt = 0)))
        render()
        compose.onNodeWithContentDescription("Терминал").assertIsDisplayed().performClick()
        assertEquals(listOf(Screen.WorkspaceTerminal(id.toString())), navigation)
    }

    @Test fun browserCapsuleStartsBrowserWithTheCurrentConversationId() {
        render()
        compose.onNodeWithContentDescription("Браузер").assertIsDisplayed().performClick()
        val intent = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(BrowserActivity::class.java.name, intent.component?.className)
        assertEquals(fixture.conversation.id.toString(), intent.getStringExtra(BrowserActivity.EXTRA_CONVERSATION_ID))
        assertEquals(emptyList<androidx.navigation3.runtime.NavKey>(), navigation)
    }

    @Test fun openingDrawerDoesNotStartTypingWithoutTappingTheSearchField() {
        render()
        compose.onNodeWithTag("chat_input").assertIsNotFocused()
        compose.onNodeWithContentDescription("Открыть список чатов").performClick()
        compose.onNodeWithTag("chat_drawer_topic_search").assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithTag("chat_input").assertIsNotFocused()
        // The pane has a neutral initial focus; keyboard navigation still enters search.
        compose.onNodeWithTag("chat_drawer_sheet").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("chat_drawer_topic_search").assertIsFocused()
    }

    @Test fun closingDrawerEndsSearchFocusAndReopeningDoesNotRestoreIt() {
        render()
        compose.onNodeWithContentDescription("Открыть список чатов").performClick()
        compose.onNodeWithTag("chat_drawer_topic_search").performClick().performTextReplacement("Проверка")
        compose.onNodeWithTag("chat_drawer_topic_search").assertIsFocused()
        // Touch the actual modal scrim; this goes through the same close action as on the phone.
        compose.onNodeWithTag("chat_header_bar").performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.onNodeWithTag("chat_drawer_topic_search").assertIsNotFocused()
        compose.onNodeWithTag("chat_input").assertIsNotFocused()
        compose.onNodeWithContentDescription("Открыть список чатов").performClick()
        compose.onNodeWithTag("chat_drawer_topic_search").assertIsDisplayed().assertIsNotFocused()
        compose.onNodeWithTag("chat_drawer_topic_search").performClick().assertIsFocused()
        compose.onNodeWithText("Проверка", useUnmergedTree = true).assertExists()
    }

    @Test fun openingDrawerEndsComposerFocusAndComposerStillAcceptsExplicitTypingAfterClosing() {
        render()
        compose.onNodeWithTag("chat_input").performClick().performTextReplacement("Черновик")
        compose.onNodeWithTag("chat_input").assertIsFocused()
        compose.onNodeWithContentDescription("Открыть список чатов").performClick()
        compose.onNodeWithTag("chat_input").assertIsNotFocused()
        compose.onNodeWithTag("chat_drawer_topic_search").assertIsNotFocused()
        compose.onNodeWithTag("chat_header_bar").performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.onNodeWithTag("chat_input").assertIsNotFocused().performClick().assertIsFocused()
        compose.runOnIdle { assertEquals("Черновик", fixture.vm.inputState.textContent.text.toString()) }
    }

    @Test fun insertSuggestionChangesActualComposerAndExitsEditingWithoutSending() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(insertSuggestionOnly = true))
        val input = fixture.vm.inputState
        input.editingMessage = Uuid.random()
        input.setContents(listOf(UIMessagePart.Text("Редактируемый ответ"),
            UIMessagePart.Reasoning("Мышление редактируемого ответа"),
            UIMessagePart.Tool("old-tool", "search_web", "{}", output = listOf(UIMessagePart.Text("Результат")))))
        render()
        compose.onNodeWithText(SUGGESTION).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(SUGGESTION, input.textContent.text.toString())
            assertNull(input.editingMessage)
            assertEquals("Leaving an assistant edit must not copy its tool/reasoning parts into a new user draft",
                listOf(UIMessagePart.Text(SUGGESTION)), input.getContents())
        }
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        verify(exactly = 0) { fixture.vm.handleMessageSend(any(), any()) }
    }

    @Test fun insertingSuggestionPreservesAttachmentsOfAnOrdinaryDraft() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(insertSuggestionOnly = true))
        val attachment = UIMessagePart.Document("content://fixture/draft", "Черновик.txt", "text/plain")
        val input = fixture.vm.inputState
        input.setMessageText("Обычный черновик")
        input.messageContent = listOf(attachment)
        render()
        compose.onNodeWithText(SUGGESTION).performClick()
        compose.runOnIdle { assertEquals(listOf(UIMessagePart.Text(SUGGESTION), attachment), input.getContents()) }
        verify(exactly = 0) { fixture.vm.handleMessageSend(any(), any()) }
    }

    @Test fun changingSuggestionSettingSwitchesMountedChatFromInsertToSend() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(insertSuggestionOnly = true))
        render()
        compose.onNodeWithText(SUGGESTION).performClick()
        verify(exactly = 0) { fixture.vm.handleMessageSend(any(), any()) }
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(insertSuggestionOnly = false))
            fixture.vm.inputState.setMessageText("Сохранить черновик")
        }
        compose.onNodeWithText(SUGGESTION).performClick()
        verify(exactly = 1) { fixture.vm.handleMessageSend(listOf(UIMessagePart.Text(SUGGESTION)), true) }
        compose.runOnIdle { assertEquals("Сохранить черновик", fixture.vm.inputState.textContent.text.toString()) }
    }

    @Test fun sendSuggestionWithoutModelShowsExistingErrorAndDoesNotSend() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(insertSuggestionOnly = false))
        every { fixture.vm.currentChatModel } returns MutableStateFlow<Model?>(null)
        render()
        compose.onNodeWithText(SUGGESTION).performClick()
        compose.onNodeWithText("Сначала выберите модель").assertIsDisplayed()
        verify(exactly = 0) { fixture.vm.handleMessageSend(any(), any()) }
    }

    @Test fun originalSearchCitationOpensTheSearchResultUrl() {
        prepareSearchCitation()
        render()
        compose.onNodeWithText("Исходник").assertIsDisplayed().performClick()
        assertEquals("https://example.com/source", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.dataString)
    }

    @Test fun translatedSearchCitationOpensTheSameSearchResultUrlAfterReopening() {
        prepareSearchCitation()
        render()
        compose.onNodeWithContentDescription("Свернуть перевод").performClick()
        compose.onNodeWithContentDescription("Развернуть перевод").performClick()
        compose.onNodeWithText("Источник перевода").assertIsDisplayed().performClick()
        assertEquals("Translated citations must reach the browser like original citations",
            "https://example.com/source", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.dataString)
    }

    @Test fun translatedCitationUsesUpdatedSearchResultOnTheMountedMessage() {
        prepareSearchCitation()
        render()
        compose.onNodeWithText("Источник перевода").performClick()
        assertEquals("https://example.com/source", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.dataString)
        compose.runOnIdle {
            val conversation = fixture.conversationFlow.value
            val node = conversation.messageNodes.single()
            val message = node.currentMessage
            fixture.conversationFlow.value = conversation.copy(messageNodes = listOf(node.copy(messages = listOf(message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text(
                        """{"items":[{"id":"source","url":"https://example.com/updated","title":"Новый источник"}]}"""))) else part
                })))))
        }
        compose.onNodeWithText("Источник перевода").performClick()
        assertEquals("https://example.com/updated", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.dataString)
    }

    private fun prepareSearchCitation() {
        settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showToolCards = false))
        val message = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("search", "search_web", "{}", output = listOf(UIMessagePart.Text(
                """{"items":[{"id":"source","url":"https://example.com/source","title":"Источник"}]}"""))),
            UIMessagePart.Text("Оригинал [citation,Исходник](source)")),
            translation = "Перевод [citation,Источник перевода](source)")
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = listOf(MessageNode.of(message)), chatSuggestions = emptyList())
    }

    companion object { private const val SUGGESTION = "Покажи следующий пример" }
}
