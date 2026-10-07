package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.insertContextCheckpoint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.root.RootAccessStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class AutoContextCompressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun source() = Conversation(assistantId = Uuid.random(), messageNodes = List(30) {
        UIMessage.user("raw-$it " + "x".repeat(if (it < 22) 4_000 else 100)).toMessageNode()
    })
    private fun compacted(source: Conversation = source()) = insertContextCheckpoint(source,
        source.messageNodes[21].id, "Stable stored summary")!!

    @Test fun `default is off and old 2_5_6 assistant JSON restores with unchanged defaults`() {
        assertFalse(Assistant().autoCompressContext)
        val restored = Json.decodeFromString<Assistant>("""{"name":"RikkaHub 2.5.6","contextMessageLimit":100}""")
        assertFalse(restored.autoCompressContext)
        assertEquals(100, restored.contextMessageLimit)
        assertEquals(32_768, restored.autoCompressionTokenThreshold)
        assertNull(AutoContextCompression.plan(source().currentMessages, restored))
    }

    @Test fun `automatic trigger preserves eight recent messages and leaves prompt cache headroom`() {
        val original = source()
        val plan = AutoContextCompression.plan(original.currentMessages, Assistant(autoCompressContext = true))!!
        assertEquals(original.currentMessages[21].id, plan.sourceEndMessageId)
        assertEquals(22, plan.cutIndex)
        val compressed = insertContextCheckpoint(original, original.messageNodes[21].id, "summary")!!
        val active = AutoContextCompression.activeMessages(compressed.currentMessages)
        assertEquals(original.currentMessages.takeLast(8), active.takeLast(8))
        assertNull(AutoContextCompression.plan(active, Assistant(autoCompressContext = true)))
        assertTrue(AutoContextCompression.estimateTokens(active) < 32_768 / 2)
    }

    @Test fun `0a2220dc tool outputs are counted and included completely in summary input`() {
        val before = List(30) { UIMessage.user("small message") }
        val output = "result-start " + "x".repeat(9_000) + " result-end exitCode=7"
        val tool = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("call", "workspace_shell", "{\"command\":\"test\"}",
            output = listOf(UIMessagePart.Text(output), UIMessagePart.Image("file:///image.png")))))
        val after = before.toMutableList().apply { this[5] = tool }
        val assistant = Assistant(autoCompressContext = true, autoCompressionTokenThreshold = 4_096)
        assertNull(AutoContextCompression.plan(before, assistant))
        assertNotNull(AutoContextCompression.plan(after, assistant))
        val text = AutoContextCompression.summaryText(after)
        assertTrue(text.contains(output))
        assertTrue(text.contains("workspace_shell"))
        assertTrue(text.contains("image.png"))
        assertTrue(AutoContextCompression.estimateTokens(after) > AutoContextCompression.estimateTokens(before) + 4_000)
    }

    @Test fun `0bd5a924 checkpoint identity and prefix stay stable and regeneration excludes raw history`() {
        val conversation = compacted()
        val active = AutoContextCompression.activeMessages(conversation.currentMessages)
        assertEquals(9, active.size)
        assertEquals(active, AutoContextCompression.activeMessages(conversation.currentMessages))
        assertTrue(active.first().isContextCheckpoint)
        assertEquals(conversation.messageNodes[22].currentMessage, active.first())
        assertFalse(active.any { it.toText().startsWith("raw-0 ") })
        val regeneration = AutoContextCompression.activeMessages(conversation.currentMessages.take(26))
        assertEquals(active.first().id, regeneration.first().id)
        assertEquals(active.first().createdAt, regeneration.first().createdAt)
        assertFalse(regeneration.any { it.toText().startsWith("raw-0 ") })
    }

    @Test fun `system messages and assistant instructions remain exact outside the summary`() {
        val system = UIMessage.system("Never replace this instruction")
        val original = source().let { it.copy(messageNodes = listOf(system.toMessageNode()) + it.messageNodes) }
        val compressed = insertContextCheckpoint(original, original.messageNodes[22].id, "summary")!!
        val active = AutoContextCompression.activeMessages(compressed.currentMessages)
        assertEquals(system, active.first())
        assertTrue(active[1].isContextCheckpoint)
        assertEquals(original.currentMessages.takeLast(8), active.takeLast(8))
    }

    @Test fun `stored checkpoint and raw messages survive conversation JSON roundtrip without a schema change`() {
        val original = compacted()
        val serialized = Json.encodeToString(Conversation.serializer(), original)
        val restored = Json.decodeFromString<Conversation>(serialized)
        assertEquals(original.messageNodes, restored.messageNodes)
        assertEquals(AutoContextCompression.activeMessages(original.currentMessages),
            AutoContextCompression.activeMessages(restored.currentMessages))
    }

    @Test fun `legacy call and result cannot straddle the compression boundary`() {
        val old = List(10) { UIMessage.user("old-$it " + "x".repeat(6_000)) }
        val call = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.ToolCall("legacy", "read", "{}")))
        val result = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.ToolResult("legacy", "read", JsonPrimitive("result"), JsonObject(emptyMap()))))
        val messages = old + call + result + List(7) { UIMessage.user("recent-$it") }
        val plan = AutoContextCompression.plan(messages, Assistant(autoCompressContext = true, autoCompressionTokenThreshold = 10_000))!!
        assertEquals(old.last().id, plan.sourceEndMessageId)
        assertEquals(listOf(call, result), messages.drop(plan.cutIndex).take(2))
    }

    @Test fun `pending calls and oversized retained tail skip compression instead of losing recent messages`() {
        val original = source().currentMessages
        val pending = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("pending", "root_exec", "{}", approvalState = ToolApprovalState.Pending)))
        assertNull(AutoContextCompression.plan(original + pending, Assistant(autoCompressContext = true)))
        val oversizedTail = original.dropLast(8) + List(8) { UIMessage.user("x".repeat(16_000)) }
        assertNull(AutoContextCompression.plan(oversizedTail, Assistant(autoCompressContext = true)))
    }

    @Test fun `2b30fb41 tail edits keep checkpoint and compressed source edits invalidate it`() {
        val original = compacted()
        fun edit(index: Int): List<MessageNode> = original.messageNodes.mapIndexed { i, node ->
            if (i == index) node.copy(messages = node.messages + UIMessage.user("edited"), selectIndex = node.messages.size) else node
        }
        val tail = AutoContextCompression.retainCheckpoints(original.messageNodes, edit(28))
        assertEquals(original.messageNodes[22], tail[22])
        assertTrue(AutoContextCompression.activeMessages(tail.map { it.currentMessage }).any { it.toText() == "edited" })
        assertFalse(AutoContextCompression.retainCheckpoints(original.messageNodes, edit(3)).any { it.currentMessage.isContextCheckpoint })
        val deletedTail = original.messageNodes.filterIndexed { index, _ -> index != 27 }
        assertTrue(AutoContextCompression.retainCheckpoints(original.messageNodes, deletedTail).any { it.currentMessage.isContextCheckpoint })
    }

    @Test fun `regenerated response becomes a variant of its target without rewriting compressed history`() {
        val original = compacted()
        val target = original.messageNodes[26]
        val input = AutoContextCompression.activeMessages(original.currentMessages.take(26))
        val response = UIMessage.assistant("regenerated")
        val merged = AutoContextCompression.mergeGenerated(original, input + response, target.id)
        assertEquals(original.messageNodes.take(26), merged.messageNodes.take(26))
        assertEquals(response, merged.messageNodes[26].currentMessage)
        assertEquals(target.messages.size + 1, merged.messageNodes[26].messages.size)
        assertEquals(original.messageNodes.drop(27), merged.messageNodes.drop(27))
    }

    @Test fun `4e18fb20 merging compacted streamed output visits nodes linearly instead of scanning history for each message`() {
        val plain = List(10_000) { UIMessage.user("stored-$it").toMessageNode() }
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true).toMessageNode()
        val nodes = CountingNodes(plain.take(9_950) + checkpoint + plain.drop(9_950))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        val active = AutoContextCompression.activeMessages(conversation.currentMessages)
        assertEquals(51, active.size)
        val response = UIMessage.assistant("stream chunk")
        nodes.visits = 0
        val merged = AutoContextCompression.mergeGenerated(conversation, active + response)
        assertEquals(plain.first(), merged.messageNodes.first())
        assertEquals(response, merged.messageNodes.last().currentMessage)
        assertTrue("node visits=${nodes.visits}", nodes.visits < nodes.size * 5 + active.size * 5)
    }

    private class CountingNodes(private val delegate: List<MessageNode>) : AbstractList<MessageNode>() {
        var visits = 0
        override val size: Int get() = delegate.size
        override fun get(index: Int): MessageNode { visits++; return delegate[index] }
    }

    @Test(timeout = 15_000) fun `stream starts after acknowledged compaction and never recompresses per token`() = runBlocking {
        WireFixture(temporary.newFolder(), streaming = true).use { fixture ->
            val original = source()
            var stored = original
            var acknowledged = false
            var compactions = 0
            var chunks = 0
            fixture.loop.generateText(settings = fixture.settings, model = fixture.model, messages = original.currentMessages,
                assistant = Assistant(autoCompressContext = true, contextMessageLimit = 1, streamOutput = true,
                    systemPrompt = "base-instructions", allowConversationSystemPrompt = true),
                conversationId = original.id, conversationSystemPrompt = "custom-instructions", maxSteps = 1,
                onAutoCompress = { input ->
                    assertTrue("Compression must wait for the durable-write acknowledgment", acknowledged)
                    compactions++
                    val plan = AutoContextCompression.plan(input, Assistant(autoCompressContext = true))!!
                    val anchor = stored.messageNodes.single { it.currentMessage.id == plan.sourceEndMessageId }.id
                    stored = insertContextCheckpoint(stored, anchor, "Stable stored summary")!!
                    AutoContextCompression.activeMessages(stored.currentMessages)
                },
            ).collect { chunk -> when (chunk) {
                is GenerationChunk.RootExecutionCheckpoint -> {
                    stored = AutoContextCompression.mergeGenerated(stored, chunk.messages)
                    acknowledged = true
                    chunk.ack.complete(Unit)
                }
                is GenerationChunk.Messages -> { chunks++; stored = AutoContextCompression.mergeGenerated(stored, chunk.messages) }
            } }
            assertEquals(1, compactions)
            assertEquals(1, fixture.requests.size)
            assertTrue(chunks >= 100)
            assertEquals("x".repeat(100), stored.currentMessages.last().toText())
            assertEquals(original.messageNodes.take(22), stored.messageNodes.take(22))
            val request = fixture.requests.single()
            assertTrue(request.contains("custom-instructions"))
            assertFalse(request.contains("base-instructions"))
            assertTrue(request.contains("Stable stored summary"))
            assertFalse(request.contains("raw-0 "))
            for (index in 22..29) assertTrue("recent $index", request.contains("raw-$index "))
        }
    }

    @Test(timeout = 15_000) fun `0bd5a924 repeated real provider requests keep the stored summary time reminder and cache prefix stable`() = runBlocking {
        WireFixture(temporary.newFolder()).use { fixture ->
            val conversation = compacted()
            repeat(2) {
                fixture.loop.generateText(settings = fixture.settings, model = fixture.model,
                    messages = AutoContextCompression.activeMessages(conversation.currentMessages),
                    assistant = Assistant(streamOutput = false, enableTimeReminder = true),
                    conversationId = conversation.id, maxSteps = 1,
                    inputTransformers = listOf(TimeReminderTransformer),
                ).collect { chunk -> if (chunk is GenerationChunk.RootExecutionCheckpoint) chunk.ack.complete(Unit) }
            }
            assertEquals(2, fixture.requests.size)
            assertEquals(fixture.requests[0], fixture.requests[1])
            assertTrue(fixture.requests[0].contains("time_reminder"))
            assertFalse(fixture.requests[0].contains("raw-0 "))
        }
    }

    @Test(timeout = 15_000) fun `automatic compression disabled never calls the callback or writes a checkpoint`() = runBlocking {
        WireFixture(temporary.newFolder()).use { fixture ->
            fixture.loop.generateText(settings = fixture.settings, model = fixture.model, messages = source().currentMessages,
                assistant = Assistant(streamOutput = false), maxSteps = 1,
                onAutoCompress = { error("disabled mode must never compress") },
            ).collect { assertTrue(it is GenerationChunk.Messages) }
            assertTrue(fixture.requests.single().contains("raw-0 "))
        }
    }

    @Test(timeout = 15_000) fun `compression cannot clear web taint or authorize a following root call`() = runBlocking {
        val response = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"root","type":"function","function":{"name":"root_exec","arguments":"{\"command\":\"id\"}"}}]}}]}"""
        WireFixture(temporary.newFolder(), response = response).use { fixture ->
            val store = RootAccessStore(temporary.newFolder())
            val conversation = source()
            store.setAutoApprove(true)
            store.markWebContent(conversation.id.toString())
            val launches = AtomicInteger()
            val root = ToolPermissionPolicy.apply(Tool("root_exec", "test", execute = {
                launches.incrementAndGet(); listOf(UIMessagePart.Text("unexpected"))
            }), store, conversation.id.toString())
            var latest: List<UIMessage> = emptyList()
            fixture.loop.generateText(settings = fixture.settings, model = fixture.model, messages = conversation.currentMessages,
                assistant = Assistant(autoCompressContext = true, streamOutput = false), tools = listOf(root), maxSteps = 1,
                onAutoCompress = { AutoContextCompression.activeMessages(compacted(conversation).currentMessages) },
            ).collect { chunk -> when (chunk) {
                is GenerationChunk.RootExecutionCheckpoint -> chunk.ack.complete(Unit)
                is GenerationChunk.Messages -> latest = chunk.messages
            } }
            assertEquals(0, launches.get())
            assertTrue(latest.last().getTools().single().isPending)
            assertTrue(store.permissions.value.autoApproveAll)
            assertTrue(store.isWebTainted(conversation.id.toString()))
        }
    }

    private class WireFixture(directory: File, streaming: Boolean = false,
        response: String = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Done"}}]}""") : Closeable {
        val requests = CopyOnWriteArrayList<String>()
        private val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = directory
            override fun getFilesDir(): File = directory
        }
        private val body = if (!streaming) response else buildString {
            repeat(100) { append("data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n") }
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += Buffer().apply { chain.request().body!!.writeTo(this) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        val model = Model(modelId = "test-chat", abilities = listOf(ModelAbility.TOOL))
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        val settings = Settings(providers = listOf(provider))
        val loop = GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true })
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
