package me.rerere.rikkahub.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso.pressBack
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.BitmapFactoryDecoder
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
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
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
import java.io.File
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.R
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * Captures the production ChatPage after stage 1. The audit-era class name remains so
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
    private var previousStage1FontScale: Float? = null
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
        imageLoader = ImageLoader.Builder(context).components {
            // ImageDecoder is not implemented for this Robolectric SDK; decode the real PNG.
            add(BitmapFactoryDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { offlineClient }))
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        val decoded = runBlocking { imageLoader.execute(ImageRequest.Builder(context).data(image.toURI().toString()).build()) }
        assertTrue("The fixture attachment must decode through real Coil: ${(decoded as? ErrorResult)?.throwable}", decoded is SuccessResult)
        fixture = ChatBeforeFixture(image)
        fixture.install()
        snapshots.publishBeforeReferences()
    }
    @OptIn(DelicateCoilApi::class)
    @After fun uninstallBoundaries() {
        try {
            if (::fixture.isInitialized) fixture.close()
            if (::imageLoader.isInitialized) imageLoader.shutdown()
            SingletonImageLoader.reset()
        } finally {
            previousStage1FontScale?.let(RuntimeEnvironment::setFontScale)
            previousStage1FontScale = null
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
        previousStage1FontScale = RuntimeEnvironment.getFontScale()
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
        compose.waitForIdle()

        captureRunningCommand(job)
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

    companion object {
        @JvmStatic @Parameters(name = "{0}_font{1}") fun cases(): List<Array<Any>> =
            ScreenshotThemeMode.entries.flatMap { theme -> listOf(1f, 1.3f).map { arrayOf(theme, it) } }
    }
}
