package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso.pressBack
import coil3.ImageLoader
import coil3.EventListener
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.serviceLoaderEnabled
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
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ServerToolStatus
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
import me.rerere.rikkahub.service.ChatService.ApprovalScope
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.components.message.LocalChatTimelineClock
import okhttp3.Dispatcher
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
import kotlin.time.Instant
import kotlin.time.Clock
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
class ChatBeforeScreenshotsTest internal constructor(private val theme: ScreenshotThemeMode, private val fontScale: Float, private val widthDp: Int) {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture
    private lateinit var imageLoader: ImageLoader
    private val activeImageRequests = AtomicInteger()
    private val pendingImagePainters = java.util.concurrent.ConcurrentHashMap<AsyncImagePainter, ImageRequest>()
    private val imagesIdle = object : IdlingResource {
        override val isIdleNow: Boolean
            get() {
                if (activeImageRequests.get() != 0) return false
                pendingImagePainters.entries.removeIf { (painter, request) ->
                    when (val state = painter.state.value) {
                        is AsyncImagePainter.State.Success -> state.result.request === request
                        is AsyncImagePainter.State.Error -> state.result.request === request
                        else -> false
                    }
                }
                return activeImageRequests.get() == 0 && pendingImagePainters.isEmpty()
            }
        override fun getDiagnosticMessageIfBusy() =
            "Waiting for ${activeImageRequests.get()} real Coil requests and ${pendingImagePainters.size} painter results"
    }
    private var previousSystemFontScale: Float? = null
    private val snapshots get() = ChatStage1ScreenshotPaths(theme.fileName, fontScale, widthDp)

    @OptIn(DelicateCoilApi::class)
    @Before fun installBoundaries() {
        RuntimeEnvironment.setQualifiers("ru-rRU-w${widthDp}dp-h891dp-mdpi")
        previousSystemFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
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
        // The offline reply runs on the calling thread instead of OkHttp's worker pool, so a
        // favicon request cannot still be in flight (placeholder) when a frame is captured.
        val offlineClient = OkHttpClient.Builder().dispatcher(Dispatcher(DirectExecutorService())).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Screenshot fixture: offline").body(ByteArray(0).toResponseBody()).build()
        }.build()
        imageLoader = ImageLoader.Builder(context).eventListenerFactory {
            object : EventListener() {
                private val started = AtomicBoolean()
                private var painter: AsyncImagePainter? = null
                override fun onStart(request: ImageRequest) {
                    if (started.compareAndSet(false, true)) {
                        activeImageRequests.incrementAndGet()
                        // Coil's EventListener completes before AsyncImagePainter publishes
                        // execute()'s result. Observe that real painter's public StateFlow too.
                        // The pinned Coil target captures it in one directly typed field.
                        val target = request.target
                        val painterField = target?.javaClass?.declaredFields?.singleOrNull {
                            AsyncImagePainter::class.java.isAssignableFrom(it.type)
                        }
                        check(target?.javaClass?.name?.startsWith("coil3.compose.AsyncImagePainter") != true ||
                            painterField != null) { "Coil Compose target no longer exposes the expected painter field" }
                        painter = painterField?.let { field ->
                            field.isAccessible = true
                            field.get(target) as AsyncImagePainter
                        }
                        painter?.let { pendingImagePainters[it] = request }
                    }
                }
                private fun completed() {
                    if (started.compareAndSet(true, false)) activeImageRequests.decrementAndGet()
                }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) = completed()
                override fun onError(request: ImageRequest, result: ErrorResult) = completed()
                override fun onCancel(request: ImageRequest) {
                    painter?.let { cancelledPainter ->
                        pendingImagePainters.computeIfPresent(cancelledPainter) { _, current ->
                            if (current === request) null else current
                        }
                    }
                    completed()
                }
            }
        }
            // Keep the whole Coil pipeline on the requesting (main) coroutine: each request reaches
            // its terminal state before the frame that started it settles, on every machine.
            // No auto-registered network fetcher: favicons must never reach the real internet.
            .serviceLoaderEnabled(false)
            // Coil's default disk cache is process-wide: another test with network access may
            // already hold the real favicon there, and the offline fetcher would serve it.
            .diskCache(null)
            .interceptorCoroutineContext(EmptyCoroutineContext)
            .fetcherCoroutineContext(EmptyCoroutineContext)
            .decoderCoroutineContext(EmptyCoroutineContext)
            .components {
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
                Surface(Modifier.requiredSize(widthDp.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
            }
        }
        compose.waitForIdle()
        val list = compose.onNode(SemanticsMatcher("visible main chat LazyColumn") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "chat_list_timeline" &&
            it.config.contains(SemanticsActions.ScrollToIndex) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                it.boundsInRoot.left >= 0 && it.boundsInRoot.top >= 0
        })
        fixture.viewports.forEach { (name, index) ->
            list.performScrollToIndex(index)
            compose.waitForIdle()
            if (name == "terminal") {
                val terminalCard = hasTestTag("chat_timeline_card") and
                    hasAnyDescendant(hasTestTag("chat_timeline_tool_details:call_termux_run_command"))
                compose.onNode(hasTestTag("chat_timeline_show_more") and hasAnyAncestor(terminalCard)).performClick()
                compose.waitForIdle()
            }
            if (name in listOf("automation", "reasoning")) {
                val context = RuntimeEnvironment.getApplication()
                val summary = if (name == "reasoning") {
                    context.getString(R.string.chat_timeline_processed_seconds, "2.0")
                } else {
                    context.resources.getQuantityString(R.plurals.chat_timeline_processed_steps, 4, 4)
                }
                val expectedSummary = hasTestTag("chat_timeline_summary") and hasText(summary)
                compose.onAllNodes(expectedSummary).assertCountEquals(1)
                compose.onNode(expectedSummary).performClick()
                compose.waitForIdle()
            }
            if (name == "generic") {
                // This is the final message in the fixture. At the end of the
                // list, earlier two-step cards can remain in the viewport.
                val summary = RuntimeEnvironment.getApplication().resources
                    .getQuantityString(R.plurals.chat_timeline_processed_steps, 2, 2)
                compose.onAllNodes(hasTestTag("chat_timeline_summary") and hasText(summary))
                    .onLast().performClick()
                compose.onNodeWithTag("chat_timeline_tool_details:call_unregistered_fixture_tool")
                    .assertIsDisplayed()
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

    @Test fun productionToolDetailsUseRequestedNativeFontScale() {
        val settings = ScreenshotFixtures.settings.copy(displaySetting = ScreenshotFixtures.settings.displaySetting.copy(autoCollapseToolSteps = false))
        every { fixture.vm.settings } returns MutableStateFlow(settings)
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = listOf(fixture.conversation.messageNodes[5]))
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                Surface(Modifier.requiredSize(widthDp.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
            }
        }
        compose.onNodeWithTag("chat_timeline_tool_details:call_termux_run_command").performClick()
        settleNativeRipple()
        val sheetText = hasAnyAncestor(hasTestTag("chat_timeline_tool_sheet:call_termux_run_command")) and
            SemanticsMatcher("native tool sheet text") { it.config.contains(SemanticsActions.GetTextLayoutResult) }
        val nodes = compose.onAllNodes(sheetText, useUnmergedTree = true)
        assertTrue("The production tool sheet must render native text", nodes.fetchSemanticsNodes().isNotEmpty())
        val layouts = mutableListOf<TextLayoutResult>()
        nodes.onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Separate tool window uses the requested actual system font scale", fontScale,
            layouts.single().layoutInput.density.fontScale, .001f)
        capture("tool_native_font_scale", includeWindows = true)
    }

    @Test fun productionStage1States() {
        // A modal dialog owns a separate density, so the outer CompositionLocal
        // override alone cannot test its system font scale. Legacy viewports keep
        // their historical fixture; only these new stage-1 states update resources.
        previousSystemFontScale = previousSystemFontScale ?: RuntimeEnvironment.getFontScale()
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
                Surface(Modifier.requiredSize(widthDp.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
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
        val expectedLines = if (widthDp == 320 && fontScale == 1.3f) 26 else 13
        assertEquals("The real Markdown paragraph retains every explicit and wrapped line", expectedLines, nativeLongText.lineCount)
        assertEquals("Wrapping must retain the final character", longText.length,
            nativeLongText.getLineEnd(nativeLongText.lineCount - 1))
        assertEquals("Collapsed user content uses nine actual scaled text lines", nativeLongText.getLineBottom(0) * 9f,
            collapsed.height, 1f)
        assertEquals("User message text uses the requested native font scale", fontScale,
            nativeLongText.layoutInput.density.fontScale, 0.001f)
        assertMessageBounds("chat_message_user_bubble")
        capture("user_long_collapsed")
        // Prepare the real expanded state through its accessibility callback;
        // this screenshot checks content and geometry, while pointer propagation
        // retains its dedicated productionChatStage2UserBranchInteraction test.
        toggle.performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue("The production user-text expand action must accept its click", click())
        }
        settleNativeRipple()
        assertNull("Expanding the user text must not edit its parent bubble", fixture.vm.inputState.editingMessage)
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развернуто"))
        val expanded = compose.onNodeWithTag("chat_message_user_collapse", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Expanding a thirteen-line user message must reveal its remaining lines", expanded.height > collapsed.height)
        if (expectedLines == 13) assertMessageBounds("chat_message_user_bubble")
        else {
            // Twenty-six scaled lines exceed one viewport: the latest message's
            // actions still have to be reachable above the measured composer.
            mainChatList().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            assertMessageBounds("chat_message_user_actions")
        }
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

    @Test fun productionChatContentKeepsBoth16dpEdgesWithNavigationEnabled() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val shortUser = stage2Message(70, MessageRole.USER, "Коротко.")
        val longUser = stage2Message(71, MessageRole.USER, "Длинный текст пользователя. ".repeat(24))
        val tool = fixture.conversation.messageNodes[5]
        mountStage2(settings, job, listOf(shortUser, longUser, tool))
        for (onLeft in listOf(false, true)) {
            for (avatar in listOf(false, true)) {
                showStage2(settings, DisplaySetting(showMessageJumper = true,
                    messageJumperOnLeft = onLeft, showUserAvatar = avatar), listOf(shortUser, longUser, tool))
                val list = mainChatList().fetchSemanticsNode().boundsInRoot
                assertEquals("The production list starts at the viewport edge", 0f, list.left, 1f)
                assertEquals("The production list ends at the viewport edge", widthDp.toFloat(), list.right, 1f)
                compose.onAllNodesWithTag("chat_message_row").fetchSemanticsNodes().forEach { row ->
                    assertEquals("Every message starts 16dp from the left", 16f, row.boundsInRoot.left, 1f)
                    assertEquals("Every message ends 16dp from the right", widthDp - 16f, row.boundsInRoot.right, 1f)
                }
            }
        }
    }

    @Test fun productionChatStage2FixesVisualCases() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val shortUser = stage2Message(72, MessageRole.USER, "Коротко.")
        val longUser = stage2Message(73, MessageRole.USER, "Длинный текст пользователя. ".repeat(24))
        mountStage2(settings, job, listOf(shortUser))
        for ((avatar, node, name) in listOf(
            Triple(false, shortUser, "geometry_user_short"),
            Triple(false, longUser, "geometry_user_long"),
            Triple(true, shortUser, "geometry_user_avatar_short"),
            Triple(true, longUser, "geometry_user_avatar_long"),
        )) {
            showStage2(settings, DisplaySetting(showUserAvatar = avatar), listOf(node))
            compose.onNodeWithTag("chat_message_user_bubble").assertIsDisplayed()
            assertFlagVisibility("chat_message_user_header", avatar)
            assertActualMessagePartsInside16dp(user = true)
            capture(name)
        }
        val attachment = fixture.conversation.messageNodes[3]
        for (avatar in listOf(false, true)) {
            showStage2(settings, DisplaySetting(showUserAvatar = avatar), listOf(attachment))
            assertFlagVisibility("chat_message_user_header", avatar)
            assertActualMessagePartsInside16dp(user = true)
            capture(if (avatar) "geometry_attachment_avatar" else "geometry_attachment")
        }
        showStage2(settings, DisplaySetting(), listOf(fixture.conversation.messageNodes[5]))
        assertActualMessagePartsInside16dp(tool = true)
        capture("geometry_tool_fullwidth")

        // Space break opportunities keep native AST/HTML wraps stable across font resolution.
        val wrap = "su_012345678901234567 ".repeat(6).trimEnd()
        for ((markup, name) in listOf(
            "Начало `${wrap}` конец" to "inline_ast_wrap",
            "<p>Начало <code>$wrap</code> конец</p>" to "inline_html_wrap",
        )) {
            showStage2(settings, DisplaySetting(), listOf(stage2Message(74, MessageRole.ASSISTANT, markup)))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Начало $wrap конец")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Inline su code must wrap in the native text layout", layouts.single().lineCount > 1)
            capture(name)
        }
        for (onLeft in listOf(false, true)) {
            val display = DisplaySetting(showMessageJumper = true, messageJumperOnLeft = onLeft)
            showStage2(settings, display, listOf(shortUser, longUser, fixture.conversation.messageNodes[5]))
            mainChatList().performScrollToIndex(1)
            compose.waitForIdle()
            compose.onNodeWithContentDescription("К началу чата").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            capture(if (onLeft) "overlay_left" else "overlay_right")
            compose.mainClock.autoAdvance = true
        }
    }

    private fun assertActualMessagePartsInside16dp(user: Boolean = false, tool: Boolean = false) {
        val row = compose.onNodeWithTag("chat_message_row").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(16f, row.left, 1f)
        assertEquals(widthDp - 16f, row.right, 1f)
        listOf("chat_message_user_bubble", "chat_message_assistant_bubble",
            "chat_message_user_header", "chat_message_assistant_header",
            "chat_message_user_actions", "chat_message_assistant_actions",
            "chat_message_tool_card").forEach { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().forEach { part ->
                val bounds = part.boundsInRoot
                assertTrue("$tag must begin inside the message inset: $bounds", bounds.left >= 15f)
                assertTrue("$tag must end inside the message inset: $bounds", bounds.right <= widthDp - 15f)
                if (tag == "chat_message_user_bubble" || tag == "chat_message_user_header" ||
                    tag == "chat_message_user_actions") {
                    assertEquals("Right-aligned $tag ends at the message inset", widthDp - 16f, bounds.right, 1f)
                }
            }
        }
        if (user) compose.onNodeWithTag("chat_message_user_actions").assertIsDisplayed()
        if (tool) {
            val card = compose.onNodeWithTag("chat_message_tool_card").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Full-width tool card starts at the message inset", 16f, card.left, 1f)
            assertEquals("Full-width tool card ends at the message inset", widthDp - 16f, card.right, 1f)
        }
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
        // Branch arrows go through the view model's id-based selection, not a whole-conversation snapshot.
        every { fixture.vm.selectMessageNode(any(), any()) } answers {
            val nodeId = firstArg<kotlin.uuid.Uuid>()
            val index = secondArg<Int>()
            fixture.conversationFlow.value = fixture.conversationFlow.value.let { current ->
                current.copy(messageNodes = current.messageNodes.map { if (it.id == nodeId) it.copy(selectIndex = index) else it })
            }
        }
        mountStage2(settings, job, listOf(branches))
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Next").assertCountEquals(1)
        compose.onNodeWithContentDescription("Next").performClick()
        settleNativeRipple()
        assertEquals("The actual ChatPage callback persists the chosen message alternative", 1,
            fixture.conversationFlow.value.messageNodes.single().selectIndex)
        // Markdown reparses a changed branch on Dispatchers.Default, outside
        // Compose idleness. Wait for that text to be placed before asserting it.
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onNodeWithText("Вторая версия сообщения.").isDisplayed()
        }
        compose.onNodeWithText("Вторая версия сообщения.").assertIsDisplayed()
        verify(exactly = 1) { fixture.vm.selectMessageNode(branches.id, 1) }
        verify(exactly = 0) { fixture.vm.updateConversation(any()) }
        verify(exactly = 0) { fixture.vm.saveConversationAsync() }

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
        verify(exactly = 1) { fixture.vm.selectMessageNode(any(), any()) }

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
        // SDK 35's native touch ripple behind the modal can differ between the
        // artifact and Verify window captures despite settled test clocks. Open
        // through the same production accessibility callback without a tap ripple.
        compose.onNodeWithTag("chat_message_sources").performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue("The production sources action must accept its click", click())
        }
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
        // The collapsed snapshot checks the real callback result; separate native
        // captures can otherwise sample different SDK 35 patterned tap-ripple frames.
        compose.onNodeWithTag("chat_message_translation_toggle").performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue("The production translation toggle must accept its click", click())
        }
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

    @Test fun productionChatStage3TimelineStates() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val ordered = stage3Message(80,
            UIMessagePart.Text("Сначала проверю данные."),
            stage3Tool("ordered_read", "workspace_read_file", """{"path":"/workspace/notes.txt"}""",
                """{"text":"Данные для графика готовы"}"""),
            UIMessagePart.Tool("ordered_chart", "chart_display",
                """{"style":"bar","title":"Порядок блоков","x_axis":{"data":["A","B"]},"series":[{"name":"Проверка","values":[2,3]}]}""",
                listOf(UIMessagePart.Text("""{"success":true}"""))),
            UIMessagePart.Text("После графика — итог."),
        )
        mountStage2(settings, job, listOf(ordered))
        compose.onNodeWithText("Сначала проверю данные.").assertIsDisplayed()
        compose.onNodeWithText("После графика — итог.").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline_card").assertIsDisplayed()
        val firstBounds = compose.onNodeWithText("Сначала проверю данные.").fetchSemanticsNode().boundsInRoot
        val timelineBounds = compose.onNodeWithTag("chat_timeline_card").fetchSemanticsNode().boundsInRoot
        val chartBounds = compose.onNodeWithText("Порядок блоков").fetchSemanticsNode().boundsInRoot
        val lastBounds = compose.onNodeWithText("После графика — итог.").fetchSemanticsNode().boundsInRoot
        assertTrue("Production page must retain text → tool → chart → text order",
            firstBounds.bottom <= timelineBounds.top && timelineBounds.bottom <= chartBounds.top &&
                chartBounds.bottom <= lastBounds.top)
        capture("timeline_text_tool_chart_text")

        val collapsed = stage3Message(81,
            stage3Tool("finished_one", "get_time_info", output = """{"time":"2026-01-02T12:00:00Z"}"""),
            stage3Tool("finished_two", "workspace_read_file", output = """{"text":"Прочитано"}"""),
            stage3Tool("finished_three", "list_files", output = """{"files":[]}"""),
            stage3Tool("finished_four", "clipboard_tool", output = """{"text":"Готово"}"""),
        )
        showStage2(settings, DisplaySetting(), listOf(collapsed))
        compose.onNodeWithTag("chat_timeline_summary").assertIsDisplayed()
        capture("timeline_processed_count")
        compose.onNodeWithTag("chat_timeline_summary").performClick()
        listOf("finished_one", "finished_two", "finished_three", "finished_four").forEach { id ->
            compose.onNodeWithTag("chat_timeline_tool_details:$id").assertIsDisplayed()
        }
        capture("timeline_summary_expanded")
        showStage2(settings, DisplaySetting(showToolSummary = false), listOf(collapsed))
        compose.onNodeWithTag("chat_timeline_summary").assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline_show_more").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline_tool_details:finished_one").assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline_tool_details:finished_two").assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline_tool_details:finished_three").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline_tool_details:finished_four").assertIsDisplayed()
        capture("timeline_collapsed_steps")
        compose.onNodeWithTag("chat_timeline_show_more").performClick()
        listOf("finished_one", "finished_two", "finished_three", "finished_four").forEach { id ->
            compose.onNodeWithTag("chat_timeline_tool_details:$id").assertIsDisplayed()
        }
        capture("timeline_expanded_steps")
        showStage2(settings, DisplaySetting(showToolSummary = false, autoCollapseToolSteps = false), listOf(collapsed))
        compose.onNodeWithTag("chat_timeline_show_more").assertDoesNotExist()
        listOf("finished_one", "finished_two", "finished_three", "finished_four").forEach { id ->
            compose.onNodeWithTag("chat_timeline_tool_details:$id").assertIsDisplayed()
        }
        capture("timeline_autocollapse_off")
        showStage2(settings, DisplaySetting(showToolSummary = true, autoCollapseToolSteps = false), listOf(collapsed))
        compose.onNodeWithTag("chat_timeline_summary").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline_show_more").assertDoesNotExist()
        listOf("finished_one", "finished_two", "finished_three", "finished_four").forEach { id ->
            compose.onNodeWithTag("chat_timeline_tool_details:$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("chat_timeline_summary").performClick()
        listOf("finished_one", "finished_two", "finished_three", "finished_four").forEach { id ->
            compose.onNodeWithTag("chat_timeline_tool_details:$id").assertDoesNotExist()
        }
        compose.onNodeWithTag("chat_timeline_summary").performClick()
        listOf("finished_one", "finished_two", "finished_three", "finished_four").forEach { id ->
            compose.onNodeWithTag("chat_timeline_tool_details:$id").assertIsDisplayed()
        }
        showStage2(settings, DisplaySetting(), listOf(collapsed))
        compose.onNodeWithTag("chat_timeline_summary").assertIsDisplayed()

        val mandatory = stage3Message(82,
            stage3Tool("prefix_success", "get_time_info", output = """{"time":"2026-01-02T12:00:00Z"}"""),
            stage3Tool("prefix_pending", "root_exec", """{"command":"id"}""", output = null,
                approval = ToolApprovalState.Pending),
            stage3Tool("prefix_ask", "ask_user",
                """{"questions":[{"id":"q1","question":"Нужна проверка?","options":["Да","Нет"],"selection_type":"single"}]}""",
                output = null, approval = ToolApprovalState.Pending),
            UIMessagePart.ServerTool("prefix_failed", "web_search", status = ServerToolStatus.FAILED),
            stage3Tool("prefix_denied", "browser_eval_js", output = null,
                approval = ToolApprovalState.Denied("Доступ отклонён")),
            stage3Tool("prefix_checkpoint", "root_exec", """{"command":"id"}""",
                """{"error":"root_execution_indeterminate","reason":"checkpointed before launch"}"""),
            stage3Tool("suffix_success", "get_time_info", output = """{"time":"2026-01-02T12:00:01Z"}"""),
        )
        showStage2(settings, DisplaySetting(), listOf(mandatory))
        compose.onNodeWithTag("chat_timeline_approval_once:prefix_pending").assertExists()
        compose.onAllNodesWithText("Нужна проверка?").onLast().assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline_tool_details:prefix_checkpoint").assertExists()
        capture("timeline_mandatory_prefix")
        compose.onNodeWithTag("chat_timeline_tool_details:prefix_checkpoint").performScrollTo()
        compose.onNodeWithTag("chat_timeline_tool_details:prefix_checkpoint").assertIsDisplayed()
        capture("timeline_mandatory_checkpoint")

        val preview = stage3Message(83, stage3Tool("long_result", "unregistered_fixture_tool", output =
            "Первая строка\\nВторая строка\\nТретья строка\\nЧетвёртая строка\\nПятая строка\\nШестая строка")
            .copy(output = listOf(UIMessagePart.Text("Первая строка\nВторая строка\nТретья строка\nЧетвёртая строка\nПятая строка\nШестая строка"),
                UIMessagePart.Image("file:///tmp/rikka-chat-audit/audit-image.png"))))
        showStage2(settings, DisplaySetting(hideToolResultImages = true, autoCollapseToolSteps = false), listOf(preview))
        compose.onNodeWithTag("chat_timeline_tool_preview:long_result").assertIsDisplayed()
        val previewLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("chat_timeline_tool_preview:long_result")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(previewLayouts) }
        assertTrue("Inline result preview must fit four visual lines", previewLayouts.single().lineCount <= 4)
        capture("timeline_preview_images_hidden")
        compose.onNodeWithTag("chat_timeline_tool_details:long_result").performClick()
        settleNativeRipple()
        capture("timeline_full_details", includeWindows = true)
        pressBack()
        settleNativeRipple()
        showStage2(settings, DisplaySetting(hideToolResultImages = false, autoCollapseToolSteps = false), listOf(preview))
        capture("timeline_preview_images_shown")
    }

    @Test fun productionChatStage3ApprovalAndInteractiveStates() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val approvalJob = Job()
        every { fixture.vm.handleToolApproval(any(), any(), any(), any(), any()) } returns approvalJob
        val pending = stage3PendingApprovalMessage()
        mountStage2(settings, job, listOf(pending))
        compose.onNodeWithTag("chat_timeline_approval_once:stage3_once").assertIsDisplayed()
        capture("timeline_pending_approvals")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chat_timeline_approval_once:stage3_once").performClick()
        compose.onNodeWithTag("chat_timeline_approval_once:stage3_once").performClick()
        verify(exactly = 1) {
            fixture.vm.handleToolApproval("stage3_once", true, "", ApprovalScope.Once, "workspace_write_file")
        }
        compose.onNodeWithTag("chat_timeline_approval_scopes:stage3_scoped").performClick()
        settleNativeRipple()
        compose.onNodeWithTag("chat_timeline_tool_sheet:stage3_scoped").assertIsDisplayed()
        capture("timeline_approval_scopes", includeWindows = true)
        compose.onNodeWithText("Для этого чата").performClick()
        verify(exactly = 1) {
            fixture.vm.handleToolApproval("stage3_scoped", true, "", ApprovalScope.ChatScope, "workspace_read_file")
        }
        compose.runOnIdle { approvalJob.complete() }
        compose.mainClock.advanceTimeByFrame()
        // Only the in-flight duplicate proof needs a frozen clock. Dialog text
        // fields must be able to advance their focus/cursor animation afterward.
        compose.mainClock.autoAdvance = true
        pressBack()
        settleNativeRipple()
        compose.onNodeWithTag("chat_timeline_tool_sheet:stage3_scoped").assertDoesNotExist()
        compose.onNodeWithTag("chat_timeline_approval_scopes:stage3_always").performClick()
        settleNativeRipple()
        compose.onNodeWithTag("chat_timeline_tool_sheet:stage3_always").assertIsDisplayed()
        compose.onNodeWithText("∞ Всегда").performClick()
        verify(exactly = 1) {
            fixture.vm.handleToolApproval("stage3_always", true, "", ApprovalScope.Always, "workspace_read_file")
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and
                hasAnyAncestor(hasTestTag("chat_timeline_tool_sheet:stage3_always")),
        ).performSemanticsAction(SemanticsActions.Dismiss) { it() }
        settleNativeRipple()
        compose.onNodeWithTag("chat_timeline_tool_sheet:stage3_always").assertDoesNotExist()
    }

    @Test fun productionChatStage3DenyAndServerStates() {
        // Read-only history mounts the production recovery/export bar; this
        // external service is present, but no backup action is requested.
        loadKoinModules(module { single { mockk<BackupManager>(relaxed = true) } })
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        every { fixture.vm.handleToolApproval(any(), any(), any(), any(), any()) } answers { Job().apply { complete() } }
        val pending = stage3PendingApprovalMessage()
        mountStage2(settings, job, listOf(pending))
        compose.onNodeWithTag("chat_timeline_approval_deny:stage3_deny").performClick()
        settleNativeRipple()
        capture("timeline_deny_reason", includeWindows = true)
        // Like ChatHeaderIntegrationTest, avoid Robolectric's wrap-content
        // selection-wrapper placement loop while exercising real field actions.
        val denialField = hasSetTextAction() and hasAnyAncestor(isDialog())
        val denialReason = "Отклонено пользователем"
        compose.runWithoutImplicitWait {
            compose.onNode(denialField).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(denialReason)) }
        }
        layoutDenyDialog()
        compose.runWithoutImplicitWait {
            compose.onNode(denialField and hasText(denialReason)).assertIsDisplayed()
            compose.onNode(hasText(RuntimeEnvironment.getApplication().getString(R.string.chat_message_tool_deny)) and
                hasAnyAncestor(isDialog())).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        verify(exactly = 1) {
            fixture.vm.handleToolApproval("stage3_deny", false, "Отклонено пользователем", ApprovalScope.Once, "root_exec")
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_message_tool_deny_dialog_title))
            .assertDoesNotExist()

        val interactive = stage3Message(85,
            stage3Tool("stage3_ask", "ask_user",
                """{"questions":[{"id":"q1","question":"Продолжить проверку?","options":["Да","Нет"],"selection_type":"single"}]}""",
                output = null, approval = ToolApprovalState.Pending),
            UIMessagePart.ServerTool("stage3_server", "web_search", status = ServerToolStatus.FAILED),
        )
        showStage2(settings, DisplaySetting(), listOf(interactive))
        compose.onAllNodesWithText("Продолжить проверку?").onLast().assertIsDisplayed()
        capture("timeline_ask_user_server_failed")

        val server = stage3Message(95, UIMessagePart.ServerTool(
            toolCallId = "stage3_provider_search", toolName = "web_search",
            input = Json.parseToJsonElement("""{"query":"Compose timeline"}"""),
            output = Json.parseToJsonElement("""{"results":[{"title":"Документация Compose","url":"https://example.com/compose","snippet":"Строка первая"},{"title":"Материал","url":"https://example.com/guide","snippet":"Строка вторая"}],"summary":"Полный ответ сервера с дополнительными деталями"}"""),
            status = ServerToolStatus.COMPLETED,
        ))
        showStage2(settings, DisplaySetting(), listOf(server))
        compose.onNodeWithTag("chat_timeline_summary").performClick()
        compose.onNodeWithTag("chat_timeline_server_preview:stage3_provider_search").assertIsDisplayed()
        val serverPreviewLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("chat_timeline_server_preview:stage3_provider_search")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(serverPreviewLayouts) }
        assertTrue("Server result preview must fit four visual lines", serverPreviewLayouts.single().lineCount <= 4)
        capture("timeline_server_preview")
        compose.onNodeWithTag("chat_timeline_server_details:stage3_provider_search").performClick()
        settleNativeRipple()
        capture("timeline_server_details", includeWindows = true)
        pressBack()
        settleNativeRipple()

        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(historyLoadState = HistoryLoadState.INCOMPLETE)
        }
        compose.waitForIdle()
        showStage2(settings, DisplaySetting(), listOf(pending))
        compose.onNodeWithTag("chat_timeline_approval_once:stage3_once").assertDoesNotExist()
        capture("timeline_read_only_pending")
        compose.mainClock.autoAdvance = true
    }

    @Test fun productionChatStage3RemoteMcpPendingArguments() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val remote = stage3Message(96, stage3Tool(
            "stage3_remote_mcp", "mcp__1234abcd_Documents__search",
            """{"query":"stage3-remote-query-unique"}""",
            output = null, approval = ToolApprovalState.Pending,
        ))
        mountStage2(settings, job, listOf(remote))
        compose.onNodeWithText("stage3-remote-query-unique", substring = true).assertIsDisplayed()
        compose.onNodeWithText("no_input", substring = true).assertDoesNotExist()
        capture("timeline_remote_mcp_pending_arguments")
        compose.onNodeWithTag("chat_timeline_tool_details:stage3_remote_mcp").performClick()
        settleNativeRipple()
        compose.onNodeWithTag("chat_timeline_tool_sheet:stage3_remote_mcp").assertIsDisplayed()
        compose.onAllNodesWithText("stage3-remote-query-unique", substring = true).onLast().assertIsDisplayed()
        compose.onNodeWithText("no_input", substring = true).assertDoesNotExist()
        capture("timeline_remote_mcp_pending_details", includeWindows = true)
        pressBack()
        settleNativeRipple()
        val control = stage3Message(97, stage3Tool(
            "stage3_native_mcp", "mcp_add",
            """{"name":"Аудит MCP","transport":"sse","url":"https://example.com/mcp","headers":[{"name":"Authorization","value":"Bearer stage3-secret-must-stay-hidden"}],"enabled":true}""",
            output = null, approval = ToolApprovalState.Pending,
        ))
        showStage2(settings, DisplaySetting(), listOf(control))
        compose.onNodeWithText("stage3-secret-must-stay-hidden", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Этот инструмент всегда требует отдельного подтверждения.").assertIsDisplayed()
        compose.onNodeWithTag("chat_timeline_tool_details:stage3_native_mcp").performClick()
        settleNativeRipple()
        compose.onNodeWithTag("chat_timeline_tool_sheet:stage3_native_mcp").assertIsDisplayed()
        compose.onNodeWithText("stage3-secret-must-stay-hidden", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Для этого чата").assertIsNotEnabled()
        compose.onNodeWithText("∞ Всегда").assertIsNotEnabled()
        capture("timeline_native_mcp_redacted_details", includeWindows = true)
    }

    @Test fun productionChatStage3ReasoningAndProducedFiles() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings.copy(displaySetting =
            DisplaySetting(autoCloseThinking = false)))
        val job = MutableStateFlow<Job?>(null)
        val created = Instant.parse("2026-01-02T12:00:00Z")
        val fixedClock = object : Clock { override fun now(): Instant = created + kotlin.time.Duration.parse("7s") }
        val reasoningText = (1..18).joinToString("\n") { "Шаг размышления $it: проверяю данные." }
        val active = stage3Message(86, UIMessagePart.Reasoning(reasoningText, created, null))
        previousSystemFontScale = previousSystemFontScale ?: RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        every { fixture.vm.settings } returns settings
        every { fixture.vm.conversationJob } returns job
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = listOf(active))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val currentSettings by settings.collectAsState()
                CompositionLocalProvider(LocalSettings provides currentSettings,
                    LocalChatTimelineClock provides fixedClock) {
                    Surface(Modifier.requiredSize(widthDp.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        mainChatList().performScrollToIndex(0)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("chat_reasoning_label", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("chat_reasoning_label", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_reasoning_preview").assertIsDisplayed()
        capture("reasoning_active_preview")
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showThinkingContent = false))
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("chat_reasoning_preview").assertDoesNotExist()
        capture("reasoning_auto_preview_off")
        compose.onNodeWithTag("chat_reasoning_step").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("chat_reasoning_expanded").assertIsDisplayed()
        capture("reasoning_manual_open_when_preview_off")
        compose.runOnIdle {
            settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(showThinkingContent = true))
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("chat_reasoning_label", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("chat_reasoning_step").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("chat_reasoning_preview").assertIsDisplayed()
        val completed = active.copy(messages = listOf(active.currentMessage.copy(parts = listOf(
            UIMessagePart.Reasoning(reasoningText, created, created + kotlin.time.Duration.parse("9s"))
        ))))
        compose.runOnIdle { fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = listOf(completed)) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("chat_reasoning_expanded").assertIsDisplayed()
        capture("reasoning_completed_open")
        compose.mainClock.autoAdvance = true
        val timed = stage3Message(93, UIMessagePart.Reasoning("Первая проверка", created,
            created + kotlin.time.Duration.parse("4s")), UIMessagePart.Reasoning("Вторая проверка",
            created + kotlin.time.Duration.parse("4s"), created + kotlin.time.Duration.parse("9s")))
        showStage2(settings, DisplaySetting(), listOf(timed))
        compose.onNodeWithTag("chat_timeline_summary").assertIsDisplayed()
        capture("timeline_processed_seconds")

        val workspaceId = Uuid.parse("60000000-0000-0000-0000-000000000001")
        val workspaceAssistant = ScreenshotFixtures.assistant.copy(workspaceId = workspaceId)
        compose.runOnIdle {
            settings.value = settings.value.copy(assistants = listOf(workspaceAssistant))
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(
                config = fixture.conversationFlow.value.config?.copy(workspaceId = workspaceId))
        }
        val files = stage3Message(88,
            stage3Tool("created_workspace", "workspace_write_file", """{"path":"/workspace/notes.txt","text":"hello"}""",
                """{"path":"/workspace/notes.txt","name":"notes.txt","isDirectory":false,"sizeBytes":5,"updatedAt":1}"""),
            stage3Tool("failed_workspace", "workspace_shell", """{"command":"echo /workspace/fake.txt"}""",
                """{"exitCode":1,"stderr":"failed","stdout":"/workspace/fake.txt"}"""),
        )
        showStage2(settings, DisplaySetting(autoCollapseToolSteps = false), listOf(files))
        compose.onNodeWithTag("chat_produced_files_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_produced_file_chip").assertIsDisplayed()
        compose.onNodeWithTag("chat_produced_file_chip").performClick()
        compose.onNodeWithTag("chat_produced_file_actions").assertIsDisplayed()
        compose.onNodeWithTag("chat_produced_file_open").assertDoesNotExist()
        capture("files_created_workspace_only", includeWindows = true)
        pressBack()
        settleNativeRipple()
        val document = stage3Message(89, UIMessagePart.Tool("created_report", "generate_bug_report", "{}",
            listOf(UIMessagePart.Text("""{"ok":true}"""),
                UIMessagePart.Document("content://me.rerere.rikkahub.test/report.zip", "report.zip", "application/zip"))))
        showStage2(settings, DisplaySetting(autoCollapseToolSteps = false), listOf(document))
        compose.onNodeWithTag("chat_produced_file_chip").performClick()
        compose.onNodeWithTag("chat_produced_file_open").assertIsDisplayed()
        capture("files_document_open_route", includeWindows = true)
        pressBack()
        settleNativeRipple()
        val overflow = stage3Message(92, *(1..13).map { number ->
            stage3Tool("written_$number", "workspace_write_file",
                """{"path":"/workspace/file$number.txt","text":"ok"}""",
                """{"path":"/workspace/file$number.txt","name":"file$number.txt","isDirectory":false,"sizeBytes":2,"updatedAt":1}""")
        }.toTypedArray())
        showStage2(settings, DisplaySetting(autoCollapseToolSteps = true), listOf(overflow))
        compose.onNodeWithTag("chat_produced_files_more").assertIsDisplayed()
        capture("files_thirteen_more")
        val forty = stage3Message(94, *(1..40).map { number ->
            stage3Tool("written_long_$number", "workspace_write_file",
                """{"path":"/workspace/file-$number.txt","text":"ok"}""",
                """{"path":"/workspace/file-$number.txt","name":"file-$number.txt","isDirectory":false,"sizeBytes":2,"updatedAt":1}""")
        }.toTypedArray())
        showStage2(settings, DisplaySetting(autoCollapseToolSteps = true), listOf(forty))
        compose.onNodeWithTag("chat_produced_files_more").performClick()
        compose.onNodeWithTag("chat_produced_files_all_list")
            .performScrollToNode(hasText("/workspace/file-39.txt"))
        compose.onNodeWithText("/workspace/file-39.txt").performClick()
        compose.onNodeWithTag("chat_produced_file_actions").assertIsDisplayed()
        compose.onNodeWithTag("chat_produced_file_export").assertIsDisplayed()
        compose.onNodeWithTag("chat_produced_file_share").assertIsDisplayed()
    }

    @Test fun productionChatStage3NarrowPendingAndFiles() {
        if (fontScale != 1.3f) return
        val settings = MutableStateFlow(ScreenshotFixtures.settings.copy(displaySetting =
            DisplaySetting(showUserAvatar = true, autoCollapseToolSteps = false)))
        val job = MutableStateFlow<Job?>(null)
        val user = stage2Message(90, MessageRole.USER, "Проверь длинное имя файла на узком экране.")
        val assistant = stage3Message(91,
            stage3Tool("narrow_long", "workspace_write_file",
                """{"path":"/workspace/a-very-long-filename-for-small-screen.txt","text":"hello"}""",
                """{"path":"/workspace/a-very-long-filename-for-small-screen.txt","name":"a-very-long-filename-for-small-screen.txt","isDirectory":false,"sizeBytes":5,"updatedAt":1}"""),
            stage3Tool("narrow_pending", "root_exec", """{"command":"ls /sdcard/Download"}""",
                output = null, approval = ToolApprovalState.Pending),
        )
        previousSystemFontScale = previousSystemFontScale ?: RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        val workspaceId = Uuid.parse("60000000-0000-0000-0000-000000000002")
        settings.value = settings.value.copy(assistants = listOf(ScreenshotFixtures.assistant.copy(workspaceId = workspaceId)))
        every { fixture.vm.settings } returns settings
        every { fixture.vm.conversationJob } returns job
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = listOf(user, assistant),
            config = fixture.conversation.config?.copy(workspaceId = workspaceId))
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val currentSettings by settings.collectAsState()
                CompositionLocalProvider(LocalSettings provides currentSettings) {
                    Surface(Modifier.requiredSize(320.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_timeline_approval_once:narrow_pending").assertIsDisplayed()
        compose.onNodeWithTag("chat_produced_file_chip").assertIsDisplayed()
        compose.onAllNodesWithTag("chat_message_row").fetchSemanticsNodes().forEach { row ->
            assertEquals("Narrow message starts at 16dp", 16f, row.boundsInRoot.left, 1f)
            assertEquals("Narrow message ends at 16dp", 304f, row.boundsInRoot.right, 1f)
        }
        capture("width320")
    }

    @Test fun productionChatStage4ToolPreviewsAndTitles() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val previews = stage3Message(96,
            stage3Tool("preview_root", "root_exec", """{"command":"ls /sdcard"}""",
                """{"backend":"root","success":true,"stdout":"Alarms\nDCIM\nDownload\nMusic\nPictures\n","stderr":"","exit_code":0}"""),
            stage3Tool("preview_termux", "termux_run_command", """{"command":"echo привет"}""",
                """{"error":"termux_run_failed","reason":"Not allowed to start service Intent: app is in background"}"""),
            stage3Tool("preview_ssh", "ssh_exec_saved", """{"name":"home","command":"cat /etc/missing"}""",
                """{"success":false,"exit_code":1,"stdout":"","stderr":"cat: /etc/missing: No such file or directory"}"""),
            stage3Tool("preview_file", "write_text_file", """{"path":"/sdcard/Download/test.txt","content":"x"}""",
                """{"success":true,"path":"/sdcard/Download/test.txt","bytes_written":12}"""),
            stage3Tool("preview_browser", "browser_open", """{"url":"https://example.com/"}""",
                """{"success":true,"url":"https://example.com/","title":"Example Domain"}"""),
        )
        mountStage2(settings, job, listOf(previews))
        showStage2(settings, DisplaySetting(autoCollapseToolSteps = false), listOf(previews))
        listOf("Root: ls /sdcard", "Termux: echo привет", "SSH home: cat /etc/missing",
            "Файл: записан test.txt", "Браузер: открыта example.com").forEach { title ->
            // Large text can make this five-tool card taller than the measured
            // viewport. Exercise the user's scroll rather than text below the input.
            mainChatList().performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        val termuxWarning = "Termux не разрешает запуск из фона — дайте Termux разрешение «Поверх других приложений»"
        mainChatList().performScrollToNode(hasText(termuxWarning))
        compose.onNodeWithText(termuxWarning).assertIsDisplayed()
        mainChatList().performScrollToNode(hasText("код выхода 1"))
        compose.onNodeWithText("код выхода 1").assertIsDisplayed()
        val rootLayouts = mutableListOf<TextLayoutResult>()
        mainChatList().performScrollToNode(hasTestTag("chat_timeline_tool_preview:preview_root"))
        compose.onNodeWithTag("chat_timeline_tool_preview:preview_root")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rootLayouts) }
        assertEquals("Command output keeps real line breaks up to four lines", 4, rootLayouts.single().lineCount)
        compose.onAllNodesWithText("{", substring = true).assertCountEquals(0)
        mainChatList().performScrollToIndex(0)
        compose.waitForIdle()
        capture("timeline_tool_previews")
    }

    @Test fun productionChatStage4ActionsTokensAndSuggestions() {
        val settings = MutableStateFlow(ScreenshotFixtures.settings)
        val job = MutableStateFlow<Job?>(null)
        val user = stage2Message(97, MessageRole.USER, "Сравни два варианта.")
        val answer = stage2Message(98, MessageRole.ASSISTANT, "Второй вариант ответа.").let { node ->
            val selected = node.currentMessage
            node.copy(messages = listOf(selected.copy(id = Uuid.random()), selected, selected.copy(id = Uuid.random())),
                selectIndex = 1)
        }
        mountStage2(settings, job, listOf(user, answer))
        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(
                chatSuggestions = listOf("Покажи таблицу", "А если взять третий вариант?", "Кратко"))
        }
        compose.waitForIdle()
        compose.onNodeWithText("2/3").assertIsDisplayed()
        compose.onNodeWithTag("chat_suggestions").assertIsDisplayed()
        compose.onNodeWithTag("chat_message_tokens").assertIsDisplayed()
        val context = RuntimeEnvironment.getApplication()
        compose.onAllNodesWithContentDescription(context.getString(R.string.edit)).assertCountEquals(1)
        capture("actions_tokens_suggestions")

        compose.onNodeWithText("Покажи таблицу").performClick()
        compose.waitForIdle()
        verify(exactly = 0) { fixture.vm.handleMessageSend(any(), any()) }
        showStage2(settings, DisplaySetting(insertSuggestionOnly = false), listOf(user, answer))
        compose.onNodeWithText("Кратко").performClick()
        compose.waitForIdle()
        verify(exactly = 1) { fixture.vm.handleMessageSend(listOf(UIMessagePart.Text("Кратко")), any()) }
    }

    private fun stage3PendingApprovalMessage() = stage3Message(84,
            stage3Tool("stage3_once", "workspace_write_file", """{"path":"/workspace/a.txt","text":"a"}""",
                output = null, approval = ToolApprovalState.Pending),
            stage3Tool("stage3_scoped", "workspace_read_file", """{"path":"/workspace/a.txt"}""",
                output = null, approval = ToolApprovalState.Pending),
            stage3Tool("stage3_always", "workspace_read_file", """{"path":"/workspace/b.txt"}""",
                output = null, approval = ToolApprovalState.Pending),
            stage3Tool("stage3_deny", "root_exec", """{"command":"id"}""",
                output = null, approval = ToolApprovalState.Pending),
        )

    private fun stage3Tool(
        id: String,
        name: String,
        input: String = "{}",
        output: String? = """{"success":true,"result":"Готово"}""",
        approval: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(id, name, input, output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(), approval)

    private fun stage3Message(index: Int, vararg parts: UIMessagePart): MessageNode =
        stage2Message(index, MessageRole.ASSISTANT, "").let { node ->
            node.copy(messages = listOf(node.currentMessage.copy(parts = parts.toList())))
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
        previousSystemFontScale = previousSystemFontScale ?: RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        every { fixture.vm.settings } returns settings
        every { fixture.vm.conversationJob } returns job
        fixture.conversationFlow.value = fixture.conversation.copy(messageNodes = nodes)
        compose.setContent {
            ScreenshotTheme(theme, fontScale) {
                val currentSettings by settings.collectAsState()
                CompositionLocalProvider(LocalSettings provides currentSettings) {
                    Surface(Modifier.requiredSize(widthDp.dp, 891.dp).testTag("chat_stage1")) { fixture.Content() }
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
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        mainChatList().performScrollToIndex(0)
        compose.waitForIdle()
    }

    private fun mainChatList() = compose.onNode(SemanticsMatcher("visible main chat LazyColumn") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "chat_list_timeline" &&
        it.config.contains(SemanticsActions.ScrollToIndex) &&
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
            it.boundsInRoot.left >= 0 && it.boundsInRoot.top >= 0
    })

    private fun assertFlagVisibility(tag: String, visible: Boolean) {
        if (visible) compose.onNodeWithTag(tag).assertIsDisplayed()
        else compose.onNodeWithTag(tag).assertDoesNotExist()
    }

    private fun assertMessageBounds(tag: String) {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("chat_header_title_line").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
        assertTrue("Message must stay inside the chat viewport: $bounds", bounds.left >= 0f && bounds.right <= widthDp.toFloat() &&
            bounds.top >= header.bottom && bounds.bottom <= composer.top && bounds.width > 0f && bounds.height > 0f)
    }

    private fun captureStage2Streaming(settings: MutableStateFlow<Settings>, job: MutableStateFlow<Job?>) {
        val initial = stage2Message(20, MessageRole.ASSISTANT, "Ответ появляется постепенно.").let { node ->
            node.copy(messages = listOf(node.currentMessage.copy(finishedAt = null, usage = null)))
        }
        // Exercise the supported no-jumper preference for precise text-animation
        // frames. Navigation movement/visibility has dedicated enabled cases.
        showStage2(settings, DisplaySetting(showMessageJumper = false), listOf(initial))
        compose.runOnIdle { job.value = Job() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // Drain the real navigation idle timer after changing the list/job,
        // before freezing the precisely timed streaming frames below.
        settleCaptureNavigation()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("chat_message_streaming_text").assertIsDisplayed()
        assertCaptureNavigationHidden()
        capture("streaming_text")
        val appendedText = "Ответ появляется постепенно. Новый фрагмент плавно проявляется."
        compose.runOnIdle {
            fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = listOf(initial.copy(
                messages = listOf(initial.currentMessage.copy(parts = listOf(UIMessagePart.Text(
                    appendedText)))),
            )))
        }
        // Markdown parses on Dispatchers.Default. Start the measured fade only
        // after its actual rendered text has reached this streaming subtree.
        val appendedRenderer = hasText(appendedText) and
            hasAnyAncestor(hasTestTag("chat_message_streaming_text"))
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(appendedRenderer).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertCaptureNavigationHidden()
        capture("streaming_fade")
        val blank = stage2Message(21, MessageRole.ASSISTANT, "").let { node ->
            node.copy(messages = listOf(node.currentMessage.copy(finishedAt = null, usage = null)))
        }
        compose.runOnIdle { fixture.conversationFlow.value = fixture.conversationFlow.value.copy(messageNodes = listOf(blank)) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertMessageBounds("chat_message_waiting_dots")
        assertCaptureNavigationHidden()
        capture("waiting_dots")
        compose.runOnIdle { job.value?.cancel(); job.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
    }

    private fun assertStage2SourcesSheet() {
        val sheet = compose.onNodeWithTag("chat_message_sources_sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Sources must remain inside the real dialog window", sheet.left >= 0f && sheet.right <= widthDp.toFloat() &&
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
        compose.onNodeWithTag("chat_timeline_tool_details:stage1_running_root").assertExists()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_timeline_status_running))
            .assertIsDisplayed()
        capture("running_command")
        compose.runOnIdle {
            job.value?.cancel()
            job.value = null
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("running-tool-bar").assertDoesNotExist()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_timeline_status_unknown))
            .assertIsDisplayed()
        capture("root_checkpoint_persisted")
        compose.runOnIdle { job.value = Job() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("running-tool-bar").assertDoesNotExist()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.chat_timeline_status_unknown))
            .assertIsDisplayed()
        compose.runOnIdle { job.value?.cancel(); job.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
    }

    private fun captureDetails(tool: String, name: String) {
        val details = hasTestTag("chat_timeline_tool_details:call_$tool")
        val target = if (tool in listOf("root_exec", "termux_run_command")) {
            // The following approval message reuses root_exec's fixture call ID.
            val terminalCard = hasTestTag("chat_timeline_card") and
                hasAnyDescendant(hasTestTag("chat_timeline_tool_details:call_termux_run_command"))
            details and hasAnyAncestor(terminalCard)
        } else details
        compose.onNode(target).performClick()
        settleNativeRipple()
        // Material's bottom sheet has its own window and is outside the ChatPage Surface.
        capture(name, includeWindows = true)
        pressBack()
        settleNativeRipple()
    }

    @OptIn(InternalComposeUiApi::class)
    private fun layoutDenyDialog() {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            fun layout(view: View) {
                if (view is ViewRootForTest) view.measureAndLayoutForTest()
                if (view is ViewGroup) for (index in 0 until view.childCount) layout(view.getChildAt(index))
            }
            layout(requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView)
        }
    }

    private fun settleNativeRipple() {
        // SDK 35's patterned RippleDrawable uses Android's animation clock (up to
        // 450ms enter + 375ms exit), independently of Compose test idleness.
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        }
        // Native back dispatch can start the sheet's Compose exit animation.
        // Finish that animation even when streaming fixtures freeze autoAdvance.
        compose.mainClock.advanceTimeBy(1_000)
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
        captureRoboFrame(snapshots.artifact(name), baseline, options,
            afterWarmup = { if (compose.mainClock.autoAdvance) settleNativeRipple() },
            captureUi = ::captureTo)
        assertTrue("Missing committed baseline: $baseline", baseline.isFile && baseline.length() > 0)
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
        @JvmStatic @Parameters(name = "{0}_font{1}_width{2}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme -> listOf(1f, 1.3f).flatMap { font ->
                listOf(411, 320).map { width -> arrayOf(theme, font, width) }
            } }
    }
}

/** Runs OkHttp's async calls inline; the screenshot fixture never touches the network. */
private class DirectExecutorService : java.util.concurrent.AbstractExecutorService() {
    @Volatile private var shutdown = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shutdown = true }
    override fun isShutdown() = shutdown
    override fun isTerminated() = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
