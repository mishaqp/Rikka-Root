package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.HeadlessRunJournal
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessTaskStatus
import kotlin.uuid.Uuid
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual provider decoder, generation and headless runner; only transport/device effects replaced. */
class GenerationLoopAuditRegressionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `1 headless prompt tool then final text succeeds and counts actual steps`() = runBlocking {
        for (origin in listOf(RunOrigin.CRON, RunOrigin.SUB_AGENT, RunOrigin.WORKFLOW)) {
            Fixture(folder.newFolder(), call("read_only_tool")).use { fixture ->
                var effects = 0
                val result = fixture.headless(listOf(Tool("read_only_tool", "test", execute = {
                    effects++; listOf(UIMessagePart.Text("tool result"))
                })), origin = origin)
                assertEquals(HeadlessTaskStatus.SUCCEEDED, result.status)
                assertTrue(result.output.orEmpty().contains("Готово"))
                assertEquals(2, result.steps)
                assertEquals(1, effects)
            }
        }
    }

    @Test fun `1 exhausted headless step limit remains a failure after tool execution`() = runBlocking {
        Fixture(folder.newFolder(), call("read_only_tool")).use { fixture ->
            val result = fixture.headless(listOf(Tool("read_only_tool", "test", execute = {
                listOf(UIMessagePart.Text("result"))
            })), maxSteps = 1)
            assertEquals(HeadlessTaskStatus.FAILED, result.status)
            assertEquals("step_limit", result.errorCode)
            assertEquals(1, result.steps)
        }
    }

    @Test fun `6 hardline blocks MCP and subagent before approval or automatic execution`() = runBlocking {
        for (name in listOf("mcp__fixture__execute", "subagent_dispatch")) for (automatic in listOf(true, false)) {
            Fixture(folder.newFolder(), call(name, """{"task":"rm -rf /"}""")).use { fixture ->
                var effects = 0
                fixture.collect(listOf(Tool(name, "test", needsApproval = { true }, execute = {
                    effects++; listOf(UIMessagePart.Text("unsafe"))
                })), autoApproved = { _, _ -> automatic })
                assertEquals(name, 0, effects)
                val tool = fixture.latest.last().getTools().single()
                assertTrue(name, tool.isExecuted)
                assertFalse(name, tool.isPending)
                assertTrue(name, tool.output.toString().contains("hardline"))
            }
        }
    }

    @Test fun `6 hardline also checks explicitly approved resumed MCP and subagent`() = runBlocking {
        for (name in listOf("mcp__fixture__execute", "subagent_dispatch")) {
            Fixture(folder.newFolder()).use { fixture ->
                var effects = 0
                fixture.collect(listOf(Tool(name, "test", execute = {
                    effects++; listOf(UIMessagePart.Text("unsafe"))
                })), messages = listOf(UIMessage.assistant("").copy(parts = listOf(
                    UIMessagePart.Tool("resumed", name, """{"task":"rm -rf /"}""",
                        approvalState = ToolApprovalState.Approved),
                ))))
                assertEquals(name, 0, effects)
                assertTrue(fixture.latest.last().getTools().single().output.toString().contains("hardline"))
            }
        }
    }

    @Test fun `16 own tool timeout returns model an error and generation continues`() = runBlocking {
        Fixture(folder.newFolder(), call("slow_tool")).use { fixture ->
            val error = runCatching {
                fixture.collect(listOf(Tool("slow_tool", "test", execute = {
                    withTimeout(5) { delay(1000); listOf(UIMessagePart.Text("late")) }
                })))
            }.exceptionOrNull()
            assertNull("An internal tool deadline must not abort the generation", error)
            assertEquals(2, fixture.requests.get())
            val output = fixture.latest.last().getTools().single().output.toString()
            assertTrue(output, output.contains("tool_timeout"))
            assertTrue(fixture.latest.last().toText().contains("Готово"))
        }
    }

    @Test fun `16 own timeout in headless prompt is a tool error rather than caller cancellation`() = runBlocking {
        Fixture(folder.newFolder(), call("slow_tool")).use { fixture ->
            val outcome = runCatching {
                fixture.headless(listOf(Tool("slow_tool", "test", execute = {
                    withTimeout(5) { delay(1000); listOf(UIMessagePart.Text("late")) }
                })))
            }
            assertNull("A local deadline must not escape the headless prompt", outcome.exceptionOrNull())
            assertEquals(HeadlessTaskStatus.SUCCEEDED, outcome.getOrThrow().status)
            assertTrue(outcome.getOrThrow().output.orEmpty().contains("Готово"))
        }
    }

    @Test fun `16 own timeout in direct headless actions returns indeterminate rather than cancellation`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            val outcome = runCatching {
                fixture.direct(Tool("slow_tool", "test", execute = {
                    withTimeout(5) { delay(1000); listOf(UIMessagePart.Text("late")) }
                }))
            }
            assertNull("A direct action deadline must not escape as cancellation", outcome.exceptionOrNull())
            assertEquals(HeadlessTaskStatus.INDETERMINATE, outcome.getOrThrow().status)
            assertEquals("tool_timeout", outcome.getOrThrow().errorCode)
            assertTrue(outcome.getOrThrow().output.orEmpty().contains("Превышено время"))
        }
    }

    @Test(timeout = 15000) fun `16 real parent cancellation propagates and cleans tool`() = runBlocking {
        Fixture(folder.newFolder(), call("slow_tool")).use { fixture ->
            val started = CompletableDeferred<Unit>()
            var cleaned = false
            val job = launch {
                fixture.collect(listOf(Tool("slow_tool", "test", execute = {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { cleaned = true }
                })))
            }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(cleaned)
            assertEquals(1, fixture.requests.get())
        }
    }

    @Test fun `17 output without workspace is bounded with marker and preserves images`() = runBlocking {
        for (name in listOf("large_tool", "search_web")) Fixture(folder.newFolder(), call(name)).use { fixture ->
            val image = UIMessagePart.Image("https://fixture.invalid/image.png")
            fixture.collect(listOf(Tool(name, "test", execute = {
                listOf(UIMessagePart.Text("A".repeat(40000)), image, UIMessagePart.Text("B".repeat(40000)))
            })))
            val output = fixture.latest.last().getTools().single().output
            val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            assertTrue("Output has ${text.length} chars", text.length <= 32 * 1024)
            assertTrue(text, text.contains("сокращ"))
            assertTrue(output.contains(image))
            assertTrue("No workspace file should be created", fixture.directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `17 bounded structured search output preserves citation ids and complete URLs`() = runBlocking {
        val payload = buildJsonObject {
            put("answer", "Краткий ответ ".repeat(5000))
            put("retrievedAt", "2026-10-16T12:00:00Z")
            put("images", buildJsonArray { add(JsonPrimitive("https://example.test/image.png")) })
            put("items", buildJsonArray {
                for (index in 1..3) add(buildJsonObject {
                    put("id", "source$index"); put("index", index)
                    put("url", "https://example.test/article/$index?key=value")
                    put("title", "Источник $index")
                    put("text", "Текст \"источника\" ".repeat(5000))
                    put("highlights", buildJsonArray { add(JsonPrimitive("Цитата ".repeat(3000))) })
                })
            })
        }.toString()
        Fixture(folder.newFolder(), call("search_web")).use { fixture ->
            fixture.collect(listOf(Tool("search_web", "test", execute = { listOf(UIMessagePart.Text(payload)) })))
            val text = fixture.latest.last().getTools().single().output.filterIsInstance<UIMessagePart.Text>().single().text
            assertTrue("Serialized JSON has ${text.length} chars", text.length <= 32 * 1024)
            val value = Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            val items = value["items"] as kotlinx.serialization.json.JsonArray
            assertEquals(3, items.size)
            items.forEachIndexed { offset, item ->
                val fields = item as kotlinx.serialization.json.JsonObject
                val index = offset + 1
                assertEquals(JsonPrimitive("source$index"), fields["id"])
                assertEquals(JsonPrimitive("https://example.test/article/$index?key=value"), fields["url"])
                assertEquals(JsonPrimitive("Источник $index"), fields["title"])
            }
            assertEquals(JsonPrimitive(true), value["truncated"])
            assertTrue(value["truncation_note"].toString().contains("сокращ"))
        }
    }

    @Test fun `21 malformed JSON returns error without showing an approval card`() = runBlocking {
        Fixture(folder.newFolder(), call("approval_tool", "{broken")).use { fixture ->
            var approvals = 0
            var effects = 0
            fixture.collect(listOf(Tool("approval_tool", "test", needsApproval = {
                approvals++; true
            }, execute = { effects++; emptyList() })), autoApproved = { _, _ -> false })
            assertEquals(0, approvals)
            assertEquals(0, effects)
            assertTrue(fixture.snapshots.none { messages -> messages.any { m -> m.getTools().any { it.isPending } } })
            val tool = fixture.latest.last().getTools().single()
            assertTrue(tool.isExecuted)
            assertTrue(tool.output.toString().contains("Некорректные аргументы"))
            assertEquals(2, fixture.requests.get())
        }
    }

    @Test fun `21 step limit is visible after tool result`() = runBlocking {
        Fixture(folder.newFolder(), call("read_only_tool")).use { fixture ->
            fixture.collect(listOf(Tool("read_only_tool", "test", execute = {
                listOf(UIMessagePart.Text("result"))
            })), maxSteps = 1)
            val last = fixture.latest.last()
            val lastTool = last.parts.indexOfLast { it is UIMessagePart.Tool }
            val visible = last.parts.drop(lastTool + 1).filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
            assertTrue(visible, visible.contains("лимит шагов"))
        }
    }

    @Test fun `21 elapsed turn budget is visible even before first provider request`() = runBlocking {
        val original = ToolRuntimeLimits.turnBudgetMs
        try {
            ToolRuntimeLimits.turnBudgetMs = 0
            Fixture(folder.newFolder()).use { fixture ->
                fixture.collect(emptyList())
                assertEquals(0, fixture.requests.get())
                assertEquals(MessageRole.ASSISTANT, fixture.latest.last().role)
                assertTrue(fixture.latest.last().toText().contains("лимит времени"))
            }
        } finally { ToolRuntimeLimits.turnBudgetMs = original }
    }

    private class Fixture(val directory: File, private val first: String = FINAL) : Closeable {
        val requests = AtomicInteger()
        var latest = emptyList<UIMessage>()
        val snapshots = mutableListOf<List<UIMessage>>()
        private val context = object : ContextWrapper(null) {
            override fun getCacheDir() = directory
            override fun getFilesDir() = directory
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = if (requests.incrementAndGet() == 1) first else FINAL
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val model = Model(modelId = "test-chat", abilities = listOf(ModelAbility.TOOL))
        private val owner = Assistant(chatModelId = model.id, streamOutput = false)
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        private val settings = Settings(providers = listOf(provider), assistants = listOf(owner))
        private val loop = GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true })
        suspend fun collect(tools: List<Tool>, messages: List<UIMessage> = listOf(UIMessage.user("Проверь")),
            maxSteps: Int = 3, autoApproved: suspend (String, JsonElement) -> Boolean = { _, _ -> true }) {
            latest = messages
            loop.generateText(settings, model, messages, assistant = owner, tools = tools, maxSteps = maxSteps,
                isToolAutoApproved = autoApproved).collect { chunk -> when (chunk) {
                is GenerationChunk.Messages -> { latest = chunk.messages; snapshots += latest }
                is GenerationChunk.RootExecutionCheckpoint -> { latest = chunk.messages; chunk.ack.complete(Unit) }
            } }
        }
        private fun runner(tools: List<Tool>) =
            HeadlessTaskRunner(loop, { _, _, _, _ -> tools }, { settings }, object : HeadlessRunJournal {
                override suspend fun claim(runId: String) = true
                override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) {}
            }, { _, _, _ -> true }, { _, _ -> null }, { true })
        suspend fun direct(tool: Tool) = runner(listOf(tool)).run(
            HeadlessTaskRequest.DirectActions(listOf(me.rerere.rikkahub.service.HeadlessToolAction(tool.name, buildJsonObject {}))),
            RunExecutionContext(Uuid.random(), owner.id, RunOrigin.WORKFLOW, modelId = model.id.toString()),
        )
        suspend fun headless(tools: List<Tool>, maxSteps: Int = 3, origin: RunOrigin = RunOrigin.SUB_AGENT) =
            runner(tools).run(
                HeadlessTaskRequest.Prompt("Вызови инструмент и ответь текстом"),
                RunExecutionContext(Uuid.random(), owner.id, origin, modelId = model.id.toString(), maxSteps = maxSteps),
            )
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    companion object {
        private const val FINAL = """{"id":"final","model":"test-chat","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Готово"}}]}"""
        private fun call(name: String, arguments: String = "{}") = buildJsonObject {
            put("id", "tool-response"); put("model", "test-chat")
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "tool_calls")
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("tool_calls", buildJsonArray { add(buildJsonObject {
                        put("id", "call"); put("type", "function")
                        put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
                    }) })
                })
            }) })
        }.toString()
    }
}
