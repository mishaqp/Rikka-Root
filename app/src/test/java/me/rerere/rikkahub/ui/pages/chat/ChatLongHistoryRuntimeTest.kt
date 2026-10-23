package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.rememberHazeState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.MessageNavButtonsMode
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.screenshot.ChatBeforeFixture
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Counts actual history reads, rather than presenting Robolectric wall-clock time as phone FPS. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class ChatLongHistoryRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: ChatBeforeFixture

    @Before fun install() {
        val image = File("/tmp/rikka-chat-audit/audit-image.png")
        if (!image.isFile) {
            requireNotNull(image.parentFile).mkdirs()
            val bitmap = android.graphics.Bitmap.createBitmap(120, 72, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(android.graphics.Color.rgb(58, 97, 149))
                drawCircle(60f, 36f, 24f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(255, 207, 76) })
            }
            image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        fixture = ChatBeforeFixture(image)
        fixture.install()
    }

    @After fun close() = fixture.close()

    @Test fun navigationAndScrollingDoNotRescanAll512MixedMessages() {
        // Includes real image, Markdown/code and tool-card renderers. Reused contents have unique identities.
        val source = listOf(0, 1, 2, 3, 5, 14).map { fixture.conversation.messageNodes[it].currentMessage }
        val nodes = HistoryReads(List(512) { index -> MessageNode(
            id = historyId(index), messages = listOf(source[index % source.size].copy(id = historyId(index + 1_000)))) })
        val conversation = fixture.conversation.copy(messageNodes = nodes, chatSuggestions = emptyList())
        val settings = ScreenshotFixtures.settings.copy(displaySetting = ScreenshotFixtures.settings.displaySetting.copy(
            showMessageJumper = true, messageNavButtonsMode = MessageNavButtonsMode.SCROLL,
            enableAutoScroll = false, showModelName = false, showModelIcon = false, showTokenUsage = false,
        ))
        val tts = mockk<CustomTtsState>(relaxed = true)
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        every { tts.speakingMessageId } returns MutableStateFlow<kotlin.uuid.Uuid?>(null)
        lateinit var state: LazyListState
        compose.setContent {
            ScreenshotTheme {
                val toaster = rememberToasterState()
                val haze = rememberHazeState()
                state = rememberLazyListState()
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSettings provides settings, LocalToaster provides toaster,
                        LocalTTSState provides tts, LocalSharedTransitionScope provides this) {
                        Box(Modifier.requiredSize(411.dp, 700.dp)) {
                            ChatList(PaddingValues(bottom = 100.dp), conversation, state, false,
                                previewMode = false, settings = settings, hazeState = haze)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        nodes.reads.set(0)
        val checkpoints = listOf(48, 96, 192, 320, 448, 504)
        val process = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val cpuDurations = mutableListOf<Long>()
        val durations = List(3) {
            val started = System.nanoTime()
            val cpuStarted = process.processCpuTime
            checkpoints.forEach { index ->
                compose.runOnIdle { state.requestScrollToItem(index) }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(1_600)
                compose.waitForIdle()
            }
            cpuDurations += (process.processCpuTime - cpuStarted) / 1_000_000
            (System.nanoTime() - started) / 1_000_000
        }
        val historyReads = nodes.reads.get()
        println("CHAT_512_BENCHMARK batches=${durations.size} checkpoints=${checkpoints.size} history_reads=$historyReads batches_ms=$durations median_ms=${durations.sorted()[1]} cpu_batches_ms=$cpuDurations median_cpu_ms=${cpuDurations.sorted()[1]}")
        compose.runOnIdle { assertTrue("The benchmark really reaches the end of the 512-message history", state.firstVisibleItemIndex >= 500) }
        assertTrue("Scroll controls must not scan the entire closed export/history on each visibility change: $historyReads reads",
            historyReads < nodes.size * 4)
        // Also exercise real pointer scrolling on the same mixed history, outside the measured workload.
        val beforeGesture = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        compose.onNodeWithTag("chat_list_timeline").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("A real drag moves through the mixed history",
            beforeGesture != (state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)) }
        compose.onNodeWithTag("chat_list_timeline").performTouchInput { swipeUp() }
    }

    @Test fun tokenWarningReadsOnlyTheNewestAssistantInsteadOfMappingAll512Messages() {
        val nodes = HistoryReads(List(512) { index -> MessageNode.of(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Ответ $index")),
            usage = TokenUsage(promptTokens = 123, completionTokens = 1, totalTokens = 124),
        )) })
        val conversation = fixture.conversation.copy(messageNodes = nodes)
        compose.setContent {
            val info = rememberConversationSizeInfo(conversation)
            assertEquals(512, info.nodeCount)
            assertEquals(123, info.lastAssistantInputTokens)
        }
        compose.waitForIdle()
        println("CHAT_512_TOKEN_WARNING history_reads=${nodes.reads.get()}")
        assertEquals("The newest assistant already contains the required usage", 1, nodes.reads.get())
    }

    @Test fun tokenWarningSkipsTrailingUserMessagesWithoutLosingTheAssistantUsage() {
        val nodes = HistoryReads(List(512) { index -> MessageNode.of(UIMessage(
            role = if (index >= 510) MessageRole.USER else MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Сообщение $index")),
            usage = if (index == 509) TokenUsage(promptTokens = 456, completionTokens = 1, totalTokens = 457) else null,
        )) })
        compose.setContent {
            val info = rememberConversationSizeInfo(fixture.conversation.copy(messageNodes = nodes))
            assertEquals(456, info.lastAssistantInputTokens)
        }
        compose.waitForIdle()
        assertEquals("Only the two trailing user messages and the last assistant are relevant", 3, nodes.reads.get())
    }

    private class HistoryReads(private val nodes: List<MessageNode>) : AbstractList<MessageNode>() {
        val reads = AtomicInteger()
        override val size get() = nodes.size
        override fun get(index: Int): MessageNode {
            reads.incrementAndGet()
            return nodes[index]
        }
    }

    private fun historyId(index: Int) = kotlin.uuid.Uuid.parse("60000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
}
