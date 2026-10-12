package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/** Exercises the production composer; external audio and disk services are the only boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ChatComposerInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val input = ChatInputState()
    private val audio = MutableStateFlow(ASRState())
    private val asr = mockk<CustomAsrState>(relaxed = true)
    private var sends = 0
    private var longSends = 0
    private var stops = 0
    private var more = 0
    private var searchMode: SearchMode? = null
    private var selectedModel: Model? = null
    private val persistedSettings = MutableStateFlow(ScreenshotFixtures.settings)

    @Before fun installBoundaries() {
        every { asr.state } returns audio
        val files = mockk<FilesManager>(relaxed = true)
        every { files.observe() } returns flowOf(emptyList())
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns persistedSettings
        stopKoin()
        startKoin { modules(module {
            single { files }
            single { settingsStore }
            single { mockk<SoundEffectPlayer>(relaxed = true) }
        }) }
    }

    @After fun closeBoundaries() { stopKoin() }

    @Test fun `fullscreen editor has an accessible action and shares the draft`() {
        input.setMessageText("Черновик")
        show()
        compose.onNodeWithTag("chat_input").performClick()
        compose.onNodeWithContentDescription("Развернуть редактор").assertExists().performClick()
        compose.onNodeWithTag("chat_fullscreen_editor").performTextReplacement("Изменённый черновик")
        compose.runOnIdle { assertEquals("Изменённый черновик", input.textContent.text.toString()) }
    }

    @Test fun `normal and long send reach their separate callbacks`() {
        input.setMessageText("Сообщение")
        show()
        compose.onNodeWithTag("chat_send_button").performClick()
        compose.onNodeWithTag("chat_send_button").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals(1, longSends)
            assertEquals(0, stops)
        }
    }

    @Test fun `empty loading composer stops on normal and long press`() {
        show(loading = true)
        compose.onNodeWithTag("chat_send_button").assertIsEnabled().performClick()
        compose.onNodeWithTag("chat_send_button").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(2, stops)
            assertEquals(0, sends)
            assertEquals(0, longSends)
        }
    }

    @Test fun `draft during generation still sends instead of stopping`() {
        input.setMessageText("В очередь")
        show(loading = true)
        compose.onNodeWithTag("chat_send_button").performClick()
        compose.runOnIdle { assertEquals(1, sends); assertEquals(0, stops) }
    }

    @Test fun `attachment only draft still sends`() {
        input.messageContent = listOf(UIMessagePart.Document("content://fixture/report", "Отчёт.txt", "text/plain"))
        show()
        compose.onNodeWithTag("chat_send_button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun `IME send keeps the existing send setting`() {
        input.setMessageText("Отправить с клавиатуры")
        show(sendOnEnter = true)
        compose.onNodeWithTag("chat_input").performImeAction()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun `plus opens the existing tools callback`() {
        show()
        compose.onNodeWithContentDescription("Дополнительные действия").performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }

    @Test fun `model tap opens the existing picker and selection reaches its callback`() {
        val otherModel = ScreenshotFixtures.model.copy(
            id = Uuid.parse("10000000-0000-0000-0000-000000000002"),
            displayName = "Другая модель",
        )
        show(models = listOf(ScreenshotFixtures.model, otherModel))
        compose.onNodeWithTag("chat_composer_model").performClick()
        compose.onNodeWithText("Другая модель").performClick()
        compose.runOnIdle { assertEquals(otherModel, selectedModel) }
    }

    @Test fun `model long press shows the full name without opening the picker`() {
        val fullName = "Полное имя модели для проверки подсказки без сокращения"
        show(modelName = fullName)
        compose.onNodeWithTag("chat_composer_model").performTouchInput { longClick() }
        compose.onNodeWithText(fullName).assertExists()
        compose.onNodeWithText("Введите название модели для поиска").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, selectedModel) }
    }

    @Test fun `empty composer uses the exact short placeholder`() {
        show()
        compose.onNodeWithText("Сообщение").assertExists()
        compose.onNodeWithText("Общайтесь с ИИ").assertDoesNotExist()
    }

    @Test fun `model is the first action before search and reasoning`() {
        show(reasoningLevel = ReasoningLevel.HIGH)
        val model = compose.onNodeWithTag("chat_composer_model").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("chat_composer_search").fetchSemanticsNode().boundsInRoot
        val reasoning = compose.onNodeWithTag("chat_composer_reasoning").fetchSemanticsNode().boundsInRoot
        assertTrue("Model must precede search", model.right <= search.left)
        assertTrue("Search must precede reasoning", search.right <= reasoning.left)
        assertEquals(32f, model.width, 0.01f)
        assertEquals(32f, model.height, 0.01f)
    }

    @Test fun `send remains hidden during dictation`() {
        audio.value = ASRState(status = ASRStatus.Listening, isAvailable = true)
        show()
        compose.onNodeWithTag("chat_send_button").assertDoesNotExist()
    }

    @Test fun `unknown prompt usage does not fabricate a zero ring`() {
        show()
        compose.onNodeWithTag("chat_context_usage").assertDoesNotExist()
    }

    @Test fun `reported prompt tokens open an honest unknown window tooltip`() {
        show(contextUsageTokens = 120)
        compose.onNodeWithTag("chat_context_usage").performClick()
        compose.onNodeWithText(
            "Входные токены последнего запроса: 120. Размер контекста модели неизвестен.",
        ).assertExists()
    }

    @Test fun `unknown model window displays compact usage without a ring`() {
        show(contextUsageTokens = 214_000)
        compose.onNodeWithText("214K").assertExists()
        compose.onNodeWithTag("chat_context_usage_ring", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat_context_usage").performClick()
        compose.onNodeWithText(
            "Входные токены последнего запроса: 214000. Размер контекста модели неизвестен.",
        ).assertExists()
    }

    @Test fun `known model window displays a ring with usage and percentage tooltip`() {
        show(contextUsageTokens = 214_000, modelId = "deepseek-v4-pro")
        compose.onNodeWithTag("chat_context_usage_ring", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("214K").assertDoesNotExist()
        compose.onNodeWithTag("chat_context_usage").performClick()
        compose.onNodeWithText("Входные токены последнего запроса: 214000 из 1000000 (21%).").assertExists()
    }

    @Test fun `reported zero usage is shown truthfully with a known window`() {
        show(contextUsageTokens = 0, modelId = "deepseek-v4-pro")
        compose.onNodeWithTag("chat_context_usage_ring", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("chat_context_usage").performClick()
        compose.onNodeWithText("Входные токены последнего запроса: 0 из 1000000 (0%).").assertExists()
    }

    @Test fun `active compact modes remain selected and search changes reach its callback`() {
        show(enableSearch = true, reasoningLevel = ReasoningLevel.HIGH)
        compose.onNodeWithTag("chat_composer_search").assertIsSelected().performClick()
        compose.onNodeWithText("Выкл.").performClick()
        compose.runOnIdle { assertEquals(SearchMode.OFF, searchMode) }
    }

    @Test fun `compact reasoning still opens the existing picker`() {
        show(reasoningLevel = ReasoningLevel.HIGH)
        compose.onNodeWithTag("chat_composer_reasoning").assertIsSelected().performClick()
        compose.onNodeWithText("Настройка глубины мышления").assertExists()
    }

    @Test fun `all primary actions stay inside a narrow screen with microphone and context`() {
        audio.value = ASRState(isAvailable = true)
        show(width = 320.dp, contextUsageTokens = 120, enableSearch = true, reasoningLevel = ReasoningLevel.HIGH)
        for (tag in listOf("chat_composer_model", "chat_composer_search", "chat_composer_reasoning", "chat_context_usage", "chat_composer_more", "chat_send_button")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag left edge must remain visible: $bounds", bounds.left >= 0f)
            assertTrue("$tag right edge must remain visible: $bounds", bounds.right <= 320f)
        }
    }

    @Test fun `multiline draft keeps all twenty lines within a five line viewport`() {
        input.setMessageText("Строка")
        show()
        val singleNode = compose.onNodeWithTag("chat_input").fetchSemanticsNode()
        val singleLineHeight = singleNode.boundsInRoot.height
        val singleLayouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            assertTrue(singleNode.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(singleLayouts))
        }
        compose.runOnIdle { input.setMessageText((1..20).joinToString("\n") { "Строка $it" }) }
        val cappedNode = compose.onNodeWithTag("chat_input").fetchSemanticsNode()
        val cappedHeight = cappedNode.boundsInRoot.height
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            assertTrue(cappedNode.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        }
        val layout = layouts.single()
        val lineAdvance = layout.getLineBaseline(1) - layout.getLineBaseline(0)
        // First-line font padding and field padding remain once as the viewport grows.
        // Total height ratios wrongly multiply that fixed padding for every line.
        assertEquals(1, singleLayouts.single().lineCount)
        assertEquals(20, layout.lineCount)
        assertEquals(
            "Five visible lines add four real baseline intervals to the single-line field",
            singleLineHeight + 4f * lineAdvance,
            cappedHeight,
            1f,
        )
        assertTrue("Remaining lines stay inside the scrolling field", layout.size.height > cappedHeight)
        assertEquals(20, input.textContent.text.toString().lines().size)
    }

    private fun show(
        loading: Boolean = false,
        sendOnEnter: Boolean = false,
        contextUsageTokens: Int? = null,
        enableSearch: Boolean = false,
        reasoningLevel: ReasoningLevel? = null,
        width: Dp = 411.dp,
        modelId: String = ScreenshotFixtures.model.modelId,
        modelName: String = ScreenshotFixtures.model.displayName,
        models: List<Model>? = null,
    ) {
        val model = ScreenshotFixtures.model.copy(
            modelId = modelId,
            displayName = modelName,
            abilities = if (reasoningLevel != null) listOf(ModelAbility.REASONING) else emptyList(),
        )
        val settings = ScreenshotFixtures.settings.copy(
            displaySetting = ScreenshotFixtures.settings.displaySetting.copy(sendOnEnter = sendOnEnter),
            providers = listOf(ScreenshotFixtures.settings.providers.single().copyProvider(models = models ?: listOf(model))),
        )
        persistedSettings.value = settings
        compose.setContent {
            ScreenshotTheme {
                Box(Modifier.width(width)) {
                    CompositionLocalProvider(
                        LocalASRState provides asr,
                        LocalSettings provides settings,
                        LocalToaster provides rememberToasterState(),
                    ) {
                        ChatInput(
                            state = input,
                            loading = loading,
                            settings = settings,
                            assistant = ScreenshotFixtures.assistant.copy(
                                enableWebSearch = enableSearch,
                                reasoningLevel = reasoningLevel ?: ReasoningLevel.AUTO,
                            ),
                            chatModel = model,
                            hazeState = HazeState(),
                            onUpdateSearchMode = { searchMode = it },
                            onUpdateChatModel = { selectedModel = it },
                            onUpdateAssistant = {},
                            onUpdateSearchService = {},
                            onMoreClick = { more++ },
                            onCancelClick = { stops++ },
                            onSendClick = { sends++ },
                            onLongSendClick = { longSends++ },
                            contextUsageTokens = contextUsageTokens,
                        )
                    }
                }
            }
        }
    }
}
