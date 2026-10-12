package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso.pressBack
import coil3.ImageLoader
import coil3.EventListener
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.BitmapFactoryDecoder
import coil3.svg.SvgDecoder
import coil3.svg.css
import androidx.compose.material3.dynamicLightColorScheme
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.utils.toCssHex
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.dropbox.differ.SimpleImageComparator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.hooks.ChatInputState
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import me.rerere.rikkahub.R
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * Captures the production ChatPage after stage 2. The audit-era class name remains so
 * existing targeted commands still run the live visual check. Historical chat-before
 * images are immutable references, not snapshots of a runtime UI that no longer exists.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w411dp-h891dp-mdpi",
    shadows = [FixedSeedDynamicColors::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ChatBeforeScreenshotsTest internal constructor(private val theme: ScreenshotThemeMode, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var imageLoader: ImageLoader
    private val activeImageRequests = AtomicInteger()
    private val imagesIdle = object : IdlingResource {
        override val isIdleNow get() = activeImageRequests.get() == 0
        override fun getDiagnosticMessageIfBusy() = "Waiting for ${activeImageRequests.get()} real Coil requests"
    }
    private var previousSystemFontScale: Float? = null
    private val snapshots get() = ChatStage1ScreenshotPaths(theme.fileName, fontScale)

    @OptIn(DelicateCoilApi::class)
    @Before fun installBoundaries() {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric's plain Application omits this library's startup ContentProvider.
        JLatexMathAndroid.init(context)
        val image = File("/tmp/rikka-chat-audit/audit-image.png")
        requireNotNull(image.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(58, 97, 149))
            drawCircle(60f, 36f, 24f, Paint().apply { color = Color.rgb(255, 207, 76) })
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Keep the real Coil decoder/render path; only remote favicon transport is fixed.
        val offlineClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Screenshot fixture: offline").body(ByteArray(0).toResponseBody()).build()
        }.build()
        imageLoader = ImageLoader.Builder(context).eventListenerFactory {
            object : EventListener() {
                private val started = AtomicBoolean()
                override fun onStart(request: ImageRequest) {
                    if (started.compareAndSet(false, true)) activeImageRequests.incrementAndGet()
                }
                private fun completed() {
                    if (started.compareAndSet(true, false)) activeImageRequests.decrementAndGet()
                }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) = completed()
                override fun onError(request: ImageRequest, result: ErrorResult) = completed()
                override fun onCancel(request: ImageRequest) = completed()
            }
        }.components {
            // Match the activity's real asset decoder: model/provider glyphs are SVG.
            add(SvgDecoder.Factory(scaleToDensity = true))
            // BitmapFactory accepts every source, so it must follow the SVG decoder.
            // ImageDecoder is not implemented for this Robolectric SDK; decode the real PNG.
            add(BitmapFactoryDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { offlineClient }))
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        // Wait for terminal painter states, including the actual offline 404
        // result; placeholder visibility must not depend on worker scheduling.
        compose.registerIdlingResource(imagesIdle)
        val decoded = runBlocking { imageLoader.execute(ImageRequest.Builder(context).data(image.toURI().toString()).build()) }
        assertTrue("The fixture attachment must decode through real Coil: ${(decoded as? ErrorResult)?.throwable}", decoded is SuccessResult)
        val scheme = if (theme == ScreenshotThemeMode.Dynamic) dynamicLightColorScheme(context)
            else findPresetTheme(ScreenshotFixtures.settings.themeId).getColorScheme(dark = theme.dark)
        val decodedModel = runBlocking {
            imageLoader.execute(ImageRequest.Builder(context)
                .data("file:///android_asset/icons/openai.svg")
                .css("svg { fill: ${scheme.onSurface.toCssHex()}; }")
                .size(20, 20).build())
        }
        assertTrue("The real model asset must decode before capture: ${(decodedModel as? ErrorResult)?.throwable}", decodedModel is SuccessResult)
        fixture = ChatBeforeFixture(image)
        fixture.install()
        snapshots.publishBeforeReferences()
    }
    @OptIn(DelicateCoilApi::class)
    @After fun uninstallBoundaries() {
        try {
            compose.unregisterIdlingResource(imagesIdle)
            if (::fixture.isInitialized) fixture.close()
            if (::imageLoader.isInitialized) imageLoader.shutdown()
            SingletonImageLoader.reset()
        } finally {
            previousSystemFontScale?.let(RuntimeEnvironment::setFontScale)
            previousSystemFontScale = null
        }
    }

    @Test fun productionChatAfterStage1() {
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
            }
        }
        compose.waitForIdle()
        val list = compose.onNode(SemanticsMatcher("visible main chat LazyColumn") {
            it.config.contains(SemanticsActions.ScrollToIndex) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                it.boundsInRoot.left >= 0 && it.boundsInRoot.top > 0
        })
        fixture.viewports.forEach { (name, index) ->
            list.performScrollToIndex(index)
            compose.waitForIdle()
            if (name in listOf("terminal", "automation")) {
                val count = if (name == "terminal") 1 else 2
                val label = RuntimeEnvironment.getApplication().getString(R.string.chain_of_thought_show_more_steps, count)
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
            }
            if (name == "reasoning") {
                val label = RuntimeEnvironment.getApplication().getString(R.string.deep_thinking_seconds, 2f)
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
            }
            capture(name)
            if (name == "terminal") {
                captureDetails("root_exec", "tool_success_details")
                captureDetails("termux_run_command", "tool_error_details")
            }
            if (name == "generic") captureDetails("unregistered_fixture_tool", "tool_plaintext_details")
        }
        fixture.showError()
        list.performScrollToIndex(fixture.conversation.messageNodes.size)
        compose.waitForIdle()
        capture("provider_error")
    }

    @Test fun productionStage1States() {
        // A modal dialog owns a separate density, so the outer CompositionLocal
        // override alone cannot test its system font scale. Legacy viewports keep
        // their historical fixture; only these new stage-1 states update resources.
        previousSystemFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        // Replace only mutable VM boundaries; every capture still mounts the real ChatPage.
        val input = ChatInputState()
        val queue = MutableStateFlow(MessageQueueState())
        val job = MutableStateFlow<Job?>(null)
        val assistant = MutableStateFlow(ScreenshotFixtures.assistant)
        val model = MutableStateFlow(ScreenshotFixtures.model)
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val workspaces = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
        val workspaceRepository = GlobalContext.get().get<WorkspaceRepository>()
        every { workspaceRepository.listFlow() } returns workspaces
        every { fixture.vm.inputState } returns input
        every { fixture.vm.messageQueue } returns queue
        every { fixture.vm.conversationJob } returns job
        every { fixture.vm.assistant } returns assistant
        every { fixture.vm.currentChatModel } returns model
        every { fixture.vm.settings } returns settings
        val empty = fixture.conversation.copy(messageNodes = emptyList(), title = "Новый чат")
        fixture.conversationFlow.value = empty
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
            }
        }
        compose.waitForIdle()
        capture("empty_chat")

        compose.runOnIdle {
            val workspaceId = fixture.conversation.messageNodes.first().id
            workspaces.value = listOf(WorkspaceEntity(
                id = workspaceId.toString(), name = "Проверка интерфейса", root = workspaceId.toString(),
                createdAt = 1767355200000L, updatedAt = 1767355200000L,
            ))
            assistant.value = ScreenshotFixtures.assistant.copy(workspaceId = workspaceId)
            fixture.conversationFlow.value = empty.copy(config = empty.config?.copy(workspaceId = workspaceId))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_header_workspace").assertExists()
        capture("workspace_header")

        compose.runOnIdle {
            val activeModel = ScreenshotFixtures.model.copy(abilities = listOf(ModelAbility.REASONING))
            val activeAssistant = ScreenshotFixtures.assistant.copy(enableWebSearch = true, reasoningLevel = ReasoningLevel.HIGH)
            model.value = activeModel
            assistant.value = activeAssistant
            workspaces.value = emptyList()
            settings.value = ScreenshotFixtures.settings.copy(
                assistants = listOf(activeAssistant),
                providers = listOf((ScreenshotFixtures.settings.providers.single() as ProviderSetting.OpenAI).copy(models = listOf(activeModel))),
            )
            fixture.conversationFlow.value = empty.copy(config = ConversationConfig(
                chatModelId = activeModel.id, enableWebSearch = true, reasoningLevel = ReasoningLevel.HIGH,
            ))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_search").assertIsSelected()
        compose.onNodeWithTag("chat_composer_reasoning").assertIsSelected()
        capture("active_search_reasoning")

        compose.runOnIdle {
            queue.value = MessageQueueState(messages = listOf(QueuedMessage(
                id = fixture.conversation.messageNodes.first().id,
                parts = listOf(UIMessagePart.Text("Проверить следующий файл после ответа")),
            )), paused = true)
            input.setMessageText("Черновик остаётся в поле ввода")
        }
        compose.waitForIdle()
        capture("queued_draft")

        compose.runOnIdle {
            queue.value = MessageQueueState()
            val original = fixture.conversation.messageNodes.first().currentMessage
            input.editingMessage = original.id
            input.setContents(original.parts)
            input.setMessageText("Уточнённый текст сообщения")
        }
        compose.waitForIdle()
        capture("editing_message")

        compose.runOnIdle {
            input.clearInput()
            assistant.value = ScreenshotFixtures.assistant
            model.value = ScreenshotFixtures.model
            settings.value = ScreenshotFixtures.settings
            fixture.conversationFlow.value = empty
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_more").performClick()
        compose.waitForIdle()
        assertPlusSheetGeometry()
        capture("plus_sheet", includeWindows = true)
        pressBack()
        // The pressed + button uses Android's independent native ripple clock.
        // Drain it before freezing the subsequent running-command frame.
        settleNativeRipple()

        captureRunningCommand(job)
    }

    @Test fun productionChatStage2MessageStates() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val user = stage2Message(1, MessageRole.USER, "Короткое сообщение пользователя.")
        val assistant = stage2Message(2, MessageRole.ASSISTANT, "Ответ помощника с независимыми настройками оформления.")
        mountStage2(settings, job, listOf(user))
        compose.onNodeWithTag("chat_message_user_header").assertDoesNotExist()
        compose.onNodeWithTag("chat_message_user_time").assertDoesNotExist()
        assertMessageBounds("chat_message_user_bubble")
        capture("user_default")

        showStage2(settings, settings.value.displaySetting.copy(showUserAvatar = true, showDateTimeInMessage = true), listOf(user))
        compose.onNodeWithTag("chat_message_user_header").assertIsDisplayed()
        compose.onNodeWithTag("chat_message_user_time").assertIsDisplayed()
        assertMessageBounds("chat_message_user_bubble")
        capture("user_avatar_time")

        listOf(1f to "assistant_bubble", 0.35f to "assistant_bubble_translucent").forEach { (opacity, name) ->
            showStage2(settings, DisplaySetting(showAssistantBubble = true, bubbleOpacity = opacity), listOf(assistant))
            assertMessageBounds("chat_message_assistant_bubble")
            capture(name)
        }

        val flagsOff = DisplaySetting(showModelIcon = false, showModelName = false,
            showDateTimeInMessage = false, showTokenUsage = false)
        val flagStates = listOf(
            "assistant_metadata_hidden" to flagsOff,
            "assistant_icon_only" to flagsOff.copy(showModelIcon = true),
            "assistant_name_only" to flagsOff.copy(showModelName = true),
            "assistant_time_only" to flagsOff.copy(showDateTimeInMessage = true),
            "assistant_tokens_only" to flagsOff.copy(showTokenUsage = true),
        )
        flagStates.forEach { (name, display) ->
            showStage2(settings, display, listOf(assistant))
            assertFlagVisibility("chat_message_assistant_icon", display.showModelIcon)
            assertFlagVisibility("chat_message_assistant_name", display.showModelName)
            assertFlagVisibility("chat_message_assistant_time", display.showDateTimeInMessage)
            assertFlagVisibility("chat_message_tokens", display.showTokenUsage)
            capture(name)
        }

        val longText = (1..13).joinToString("\n") { "Строка ${it.toString().padStart(2, '0')}: заметка" }
        val longUser = stage2Message(3, MessageRole.USER, longText)
        showStage2(settings, DisplaySetting(), listOf(longUser))
        val toggle = compose.onNodeWithTag("chat_message_user_collapse_toggle")
        toggle.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свернуто"))
        val collapsed = compose.onNodeWithTag("chat_message_user_collapse", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val longLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(longText, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(longLayouts) }
        val nativeLongText = longLayouts.single()
        assertEquals("The real Markdown paragraph retains all thirteen lines", 13, nativeLongText.lineCount)
        assertEquals("Collapsed user content uses nine actual scaled text lines", nativeLongText.getLineBottom(0) * 9f,
            collapsed.height, 1f)
        assertEquals("User message text uses the requested native font scale", fontScale,
            nativeLongText.layoutInput.density.fontScale, 0.001f)
        assertMessageBounds("chat_message_user_bubble")
        capture("user_long_collapsed")
        toggle.performClick()
        settleNativeRipple()
        assertNull("Expanding the user text must not edit its parent bubble", fixture.vm.inputState.editingMessage)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развернуто"))
        val expanded = compose.onNodeWithTag("chat_message_user_collapse", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Expanding a thirteen-line user message must reveal its remaining lines", expanded.height > collapsed.height)
        assertMessageBounds("chat_message_user_bubble")
        capture("user_long_expanded")
        toggle.performClick()
        settleNativeRipple()
        assertNull("Collapsing the user text must not edit its parent bubble", fixture.vm.inputState.editingMessage)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свернуто"))
        showStage2(settings, DisplaySetting(collapseLongUserMessages = false),
            listOf(stage2Message(4, MessageRole.USER, longText)))
        compose.onNodeWithTag("chat_message_user_collapse_toggle").assertDoesNotExist()
        assertEquals("Disabling collapse reveals the complete fresh message", expanded.height,
            compose.onNodeWithTag("chat_message_user_collapse", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height, 1f)
        capture("user_long_collapse_disabled")

        captureStage2Streaming(settings, job)
    }

    @Test fun productionChatStage2UserBranchInteraction() {
        // Incomplete history mounts the real recovery/export bar. Its backup
        // service is an external boundary; no export is requested by this test.
        loadKoinModules(module { single { mockk<BackupManager>(relaxed = true) } })
        val settings = MutableStateFlow(ScreenshotFixtures.settings.copy(
            displaySetting = DisplaySetting(showUserMessageActions = false),
        ))
        val job = MutableStateFlow<Job?>(null)
        val first = stage2Message(30, MessageRole.USER, "Первая версия сообщения.")
        val second = stage2Message(31, MessageRole.USER, "Вторая версия сообщения.").currentMessage
        val branches = first.copy(messages = listOf(first.currentMessage, second))
        every { fixture.vm.updateConversation(any()) } answers {
            fixture.conversationFlow.value = firstArg<Conversation>()
        }
        mountStage2(settings, job, listOf(branches))
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Next").assertCountEquals(1)
        compose.onNodeWithContentDescription("Next").performClick()
        settleNativeRipple()
        assertEquals("The actual ChatPage callback persists the chosen message alternative", 1,
            fixture.conversationFlow.value.messageNodes.single().selectIndex)
        compose.onNodeWithText("Вторая версия сообщения.").assertIsDisplayed()
        verify(exactly = 1) { fixture.vm.updateConversation(any()) }
        verify(exactly = 1) { fixture.vm.saveConversationAsync() }

        showStage2(settings, DisplaySetting(showUserMessageActions = true), fixture.conversationFlow.value.messageNodes)
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Next").assertCountEquals(1)

        val longText = (1..13).joinToString("\n") { "Строка ${it.toString().padStart(2, '0')}: заметка" }
        val readOnlyNode = stage2Message(32, MessageRole.USER, longText).copy(
            messages = listOf(first.currentMessage, stage2Message(33, MessageRole.USER, longText).currentMessage), selectIndex = 1,
        )
        showStage2(settings, DisplaySetting(showUserMessageActions = false), listOf(readOnlyNode))
        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(historyLoadState = HistoryLoadState.INCOMPLETE)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
        compose.onNodeWithTag("chat_message_user_collapse_toggle").performClick()
        settleNativeRipple()
        compose.onNodeWithTag("chat_message_user_collapse_toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развернуто"))
        assertNull("Read-only user text can expand without entering edit mode", fixture.vm.inputState.editingMessage)
        assertEquals("Read-only expansion preserves its selected alternative", 1,
            fixture.conversationFlow.value.messageNodes.single().selectIndex)
        verify(exactly = 1) { fixture.vm.updateConversation(any()) }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(historyLoadState = HistoryLoadState.COMPLETE)
            job.value = Job()
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Next").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).assertDoesNotExist()
        compose.runOnIdle { job.value?.cancel(); job.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
    }

    @Test fun productionChatStage2RichTextStates() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val code = stage2Message(10, MessageRole.ASSISTANT,
            "Значение `answer` хранится в коде.\n\n```kotlin\nval answer = 42\nprintln(answer)\n```\n\nОбычный текст после блока.")
        mountStage2(settings, job, listOf(code))
        assertMessageBounds("markdown_code_block")
        compose.onNodeWithTag("markdown_code_body").assertIsDisplayed()
        capture("code_inline")

        val table = stage2Message(11, MessageRole.ASSISTANT,
            "| Проверка | Значение | Результат |\n|---|---:|---|\n| Код | 42 | Готово |\n| Таблица | 3 строки | Проверено |\n| Оформление | Русский текст | Видно |")
        showStage2(settings, DisplaySetting(), listOf(table))
        assertMessageBounds("markdown_table")
        compose.onNodeWithTag("markdown_table_copy").assertIsDisplayed()
        compose.onNodeWithTag("markdown_table_csv").assertIsDisplayed()
        capture("table")

        val html = stage2Message(12, MessageRole.ASSISTANT,
            "HTML с доступными действиями:\n\n```html\n<section>\n  <h1>Проверка интерфейса</h1>\n  <p>Пример HTML</p>\n</section>\n```")
        showStage2(settings, DisplaySetting(), listOf(html))
        assertMessageBounds("markdown_code_block")
        capture("html_code")

        val sources = stage2Message(13, MessageRole.ASSISTANT,
            "Результат подтверждён документацией [1] и примерами [2].",
            annotations = listOf(
                UIMessageAnnotation.WebContentUsed,
                UIMessageAnnotation.UrlCitation("Документация Compose", "https://developer.android.com/compose"),
                UIMessageAnnotation.UrlCitation("Примеры Kotlin", "https://kotlinlang.org/docs/home.html"),
                UIMessageAnnotation.UrlCitation("Материалы проекта", "https://example.com/project"),
            ))
        showStage2(settings, DisplaySetting(), listOf(sources))
        compose.onNodeWithTag("chat_message_sources").assertIsDisplayed()
        capture("sources_summary")
        compose.onNodeWithTag("chat_message_sources").performClick()
        settleNativeRipple()
        assertStage2SourcesSheet()
        capture("sources_sheet", includeWindows = true)
        pressBack()
        settleNativeRipple()

        val translated = stage2Message(14, MessageRole.ASSISTANT, "Ответ на русском языке.",
            translation = "**English translation.** The result is ready.")
        showStage2(settings, DisplaySetting(), listOf(translated))
        assertMessageBounds("chat_message_translation")
        compose.onNodeWithTag("chat_message_translation_content").assertIsDisplayed()
        capture("translation_expanded")
        compose.onNodeWithTag("chat_message_translation_toggle").performClick()
        settleNativeRipple()
        compose.onNodeWithTag("chat_message_translation_content").assertDoesNotExist()
        capture("translation_collapsed")
        val pending = stage2Message(15, MessageRole.ASSISTANT, "Текст для перевода.",
            translation = RuntimeEnvironment.getApplication().getString(R.string.translating))
        // Finish the preceding list/navigation activity before a fresh pending
        // animation starts, then freeze its clock before mounting that state.
        settleCaptureNavigation()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = listOf(pending))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertCaptureNavigationHidden()
        assertMessageBounds("chat_message_translation")
        compose.onNodeWithTag("chat_message_translation_loading").assertIsDisplayed()
        capture("translation_pending")
        compose.mainClock.autoAdvance = true
    }

    private fun stage2Message(
        index: Int,
        role: MessageRole,
        text: String,
        annotations: List<UIMessageAnnotation> = emptyList(),
        translation: String? = null,
    ): MessageNode {
        val template = fixture.conversation.messageNodes[if (role == MessageRole.USER) 0 else 1]
        val id = Uuid.parse("50000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        return template.copy(id = id, messages = listOf(template.currentMessage.copy(
            id = id, role = role, parts = listOf(UIMessagePart.Text(text)),
            annotations = annotations, translation = translation,
        )), selectIndex = 0)
    }

    private fun mountStage2(settings: MutableStateFlow<Settings>, job: MutableStateFlow<Job?>, nodes: List<MessageNode>) {
        // Dialog windows inherit the platform resource density rather than the
        // outer Compose override. Exercise the requested font in both render paths.
        previousSystemFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        every { fixture.vm.settings } returns settings
        every { fixture.vm.conversationJob } returns job
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = nodes)
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val currentSettings by settings.collectAsState()
                CompositionLocalProvider(LocalSettings provides currentSettings) {
                    Surface(Modifier.requiredSize(411.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showStage2(settings: MutableStateFlow<Settings>, display: DisplaySetting, nodes: List<MessageNode>) {
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = display)
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = nodes)
        }
        compose.waitForIdle()
        mainChatList().performScrollToIndex(0)
        compose.waitForIdle()
    }

    private fun mainChatList() = compose.onNode(SemanticsMatcher("visible main chat LazyColumn") {
        it.config.contains(SemanticsActions.ScrollToIndex) &&
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
            it.boundsInRoot.left >= 0 && it.boundsInRoot.top > 0
    })

    private fun assertFlagVisibility(tag: String, visible: Boolean) {
        if (visible) compose.onNodeWithTag(tag).assertIsDisplayed()
        else compose.onNodeWithTag(tag).assertDoesNotExist()
    }

    private fun assertMessageBounds(tag: String) {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("chat_header_bar").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
        assertTrue("Message must stay inside the chat viewport: $bounds", bounds.left >= 0f && bounds.right <= 411f &&
            bounds.top >= header.bottom && bounds.bottom <= composer.top && bounds.width > 0f && bounds.height > 0f)
    }

    private fun captureStage2Streaming(settings: MutableStateFlow<Settings>, job: MutableStateFlow<Job?>) {
        val initial = stage2Message(20, MessageRole.ASSISTANT, "Ответ появляется постепенно.").let { node ->
            node.copy(messages = listOf(node.currentMessage.copy(finishedAt = null, usage = null)))
        }
        showStage2(settings, DisplaySetting(), listOf(initial))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { job.value = Job() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_message_streaming_text").assertIsDisplayed()
        capture("streaming_text")
        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = listOf(initial.copy(
                messages = listOf(initial.currentMessage.copy(parts = listOf(UIMessagePart.Text(
                    "Ответ появляется постепенно. Новый фрагмент плавно проявляется.")))),
            )))
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        capture("streaming_fade")
        val blank = stage2Message(21, MessageRole.ASSISTANT, "").let { node ->
            node.copy(messages = listOf(node.currentMessage.copy(finishedAt = null, usage = null)))
        }
        compose.runOnIdle { fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = listOf(blank)) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertMessageBounds("chat_message_waiting_dots")
        capture("waiting_dots")
        compose.runOnIdle { job.value?.cancel(); job.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
    }

    private fun assertStage2SourcesSheet() {
        val sheet = compose.onNodeWithTag("chat_message_sources_sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Sources must remain inside the real dialog window", sheet.left >= 0f && sheet.right <= 411f &&
            sheet.top >= 0f && sheet.bottom <= 891f && sheet.height > 0f)
        val title = compose.onNodeWithTag("chat_message_sources_title", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("The sources dialog uses the actual requested platform font", fontScale,
            layouts.single().layoutInput.density.fontScale, 0.001f)
    }

    private fun assertPlusSheetGeometry() {
        // The content cap belongs inside the full-height dialog. Capping the dialog
        // itself leaves a visible gap below the sheet; the real window must end at 891.
        val content = compose.onNodeWithTag("chat_plus_content").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("The + sheet must anchor at the window bottom", 891f, content.bottom, 1f)
        assertTrue("The + content plus 22dp handle must respect the 80% height cap", content.height <= 891f * 0.8f - 22f + 1f)
        val context = RuntimeEnvironment.getApplication()
        val tiles = listOf(R.string.chat_plus_camera, R.string.chat_plus_photos, R.string.chat_plus_files).map { resource ->
            compose.onNode(hasClickAction() and hasText(context.getString(resource)))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        tiles.forEach { bounds ->
            assertTrue("Attachment tiles must be visible inside the sheet", bounds.left >= content.left && bounds.right <= content.right &&
                bounds.top >= content.top && bounds.bottom <= content.bottom && bounds.width > 40f && bounds.height > 40f)
        }
        tiles.zipWithNext().forEach { (left, right) ->
            assertTrue("Attachment tiles must not overlap", left.right <= right.left)
            assertEquals("Attachment tiles share one row", left.top, right.top, 1f)
        }
        val cameraLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(context.getString(R.string.chat_plus_camera), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(cameraLayouts) }
        assertEquals("The actual dialog camera label must use the requested system font scale",
            fontScale, cameraLayouts.single().layoutInput.density.fontScale, 0.001f)
    }

    private fun captureRunningCommand(job: MutableStateFlow<Job?>) {
        val node = fixture.conversation.messageNodes[5]
        val message = node.currentMessage
        val tool = (message.parts.first() as UIMessagePart.Tool).copy(
            toolCallId = "stage1_running_root", approvalState = ToolApprovalState.Auto,
            input = """{"command":"ls /sdcard/Download"}""", output = emptyList(),
        )
        val beforeLaunch = fixture.conversationFlow.value.copy(messageNodes = listOf(node.copy(
            messages = listOf(message.copy(parts = listOf(tool))), selectIndex = 0,
        )))
        // A fresh execution checkpoint is observed after the selected message mounts.
        // Merely queued Auto/Approved tools must never appear as running.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            fixture.conversationFlow.value = beforeLaunch
            job.value = Job()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val checkpoint = tool.copy(output = listOf(UIMessagePart.Text(
            """{"error":"root_execution_indeterminate","reason":"This root command was checkpointed before launch. If execution was interrupted, its effects are unknown. It will not run again automatically. Review the device before requesting a new command."}""",
        )))
        compose.runOnIdle {
            fixture.conversationFlow.value = beforeLaunch.copy(messageNodes = listOf(node.copy(
                messages = listOf(message.copy(parts = listOf(checkpoint))), selectIndex = 0,
            )))
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("running-tool-bar").assertExists()
        capture("running_command")
        compose.runOnIdle {
            job.value?.cancel()
            job.value = null
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
    }

    private fun captureDetails(tool: String, name: String) {
        val label = RuntimeEnvironment.getApplication().getString(R.string.chat_message_tool_call_generic, tool)
        // A later Pending root call can also be precomposed below this viewport.
        compose.onAllNodesWithText(label).onFirst().performClick()
        settleNativeRipple()
        // Material's bottom sheet has its own window and is outside the ChatPage Surface.
        capture(name, includeWindows = true)
        pressBack()
        settleNativeRipple()
    }

    private fun settleNativeRipple() {
        // SDK 35's patterned RippleDrawable uses Android's animation clock (up to
        // 450ms enter + 375ms exit), independently of Compose test idleness.
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        }
        compose.waitForIdle()
    }

    private fun capture(name: String, includeWindows: Boolean = false) {
        settleCaptureNavigation()
        val baseline = snapshots.baseline(name)
        val options = RoborazziOptions(
            taskType = if (snapshots.record) RoborazziTaskType.Record else RoborazziTaskType.Verify,
            // Native text/shadow rasterization can round a channel by one RGB level.
            // Keep the comparator's standard per-pixel tolerance; layout changes fail.
            compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = 0.007f)),
        )
        requireNotNull(baseline.parentFile).mkdirs()
        fun captureTo(file: File, captureOptions: RoborazziOptions) {
            if (includeWindows) {
                captureScreenRoboImage(file.absolutePath, roborazziOptions = captureOptions)
            } else {
                compose.onNodeWithTag("chat_stage1").captureRoboImage(file.absolutePath, roborazziOptions = captureOptions)
            }
        }
        captureTo(baseline, options)
        assertTrue("Missing committed baseline: $baseline", baseline.isFile && baseline.length() > 0)
        // Publish the actual settled frame, including allowed native rounding, rather
        // than copying the expected baseline and labeling it as a current capture.
        captureTo(snapshots.artifact(name), RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    private fun settleCaptureNavigation() {
        // Native rendering/verification can take different wall time. List
        // anchors changing between fixtures start the real 1500ms navigation
        // idle timer, which Compose idleness alone does not necessarily drain.
        // Frozen streaming/running/loading captures keep their chosen phase.
        if (!compose.mainClock.autoAdvance) return
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnUiThread {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)
        }
        compose.waitForIdle()
        assertCaptureNavigationHidden()
    }

    private fun assertCaptureNavigationHidden() {
        val context = RuntimeEnvironment.getApplication()
        listOf("К началу чата", "Предыдущее сообщение", "Следующее сообщение",
            context.getString(R.string.chat_page_scroll_to_bottom)).forEach { label ->
            compose.onNodeWithContentDescription(label).assertDoesNotExist()
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}_font{1}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme -> listOf(1f, 1.3f).map { arrayOf(theme, it) } }
    }
}
