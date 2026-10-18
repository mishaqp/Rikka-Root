package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.HeadlessRunJournal
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.service.HeadlessToolAction
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

/** Real transport decoder and runtime; no permission or execution boundary is replaced. */
class GenerationLoopArgumentContractTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `canonical arguments precede foreground approval and are stored in pending card`() = runBlocking {
        val raw = """{"count":"12","ratio":"1.25","enabled":"false","label":"007"}"""
        val expected = parsed("""{"count":12,"ratio":1.25,"enabled":false,"label":"007"}""")
        Fixture(folder.newFolder(), listOf(response(Call("one", "contract_tool", raw)))).use { f ->
            val approvals = mutableListOf<JsonElement>()
            val grants = mutableListOf<JsonElement>()
            var effects = 0
            val tool = contractTool(needsApproval = { approvals += it; true }) { effects++; emptyList() }
            f.collect(listOf(tool), autoApproved = { _, args -> grants += args; false })
            assertEquals(0, effects)
            assertEquals(listOf(expected), approvals)
            assertEquals(listOf(expected), grants)
            val card = f.tools().single()
            assertTrue(card.isPending)
            assertEquals(expected, parsed(card.input))
        }
    }

    @Test fun `nested schemas normalize only declared numeric and boolean values`() = runBlocking {
        val properties = parsed("""{"payload":{"type":"object","properties":{"items":{"type":"array","items":{"type":"integer"}},"flag":{"type":"boolean"},"optional":{"type":"integer","default":9}},"required":["items"]},"typed":{"type":"object","additionalProperties":{"type":"number"}},"free":{"type":"object","additionalProperties":true},"nullable":{"type":["null","integer"]}}""").jsonObject
        val raw = """{"payload":{"items":["1","-2"],"flag":"true"},"typed":{"a":"2.5"},"free":{"zero":"007","flag":"false"},"nullable":null,"_expected_before":{"id":"keep"},"_approval_preview":"keep"}"""
        val expected = parsed("""{"payload":{"items":[1,-2],"flag":true},"typed":{"a":2.5},"free":{"zero":"007","flag":"false"},"nullable":null,"_expected_before":{"id":"keep"},"_approval_preview":"keep"}""")
        Fixture(folder.newFolder(), listOf(response(Call("one", "nested_tool", raw)))).use { f ->
            val received = mutableListOf<JsonElement>()
            f.collect(listOf(Tool("nested_tool", "test", parameters = { InputSchema.Obj(properties) }, execute = {
                received += it; listOf(UIMessagePart.Text("ok"))
            })))
            assertEquals(listOf(expected), received)
            assertEquals(expected, parsed(f.tools().single().input))
        }
    }

    @Test fun `invalid declared values and missing required fields fail before permission callbacks`() = runBlocking {
        val cases = listOf(
            """{"count":1,"enabled":"yes","label":"ok"}""",
            """{"count":1,"enabled":"1","label":"ok"}""",
            """{"count":"1.5","label":"ok"}""",
            """{"count":"007","label":"ok"}""",
            """{"count":1,"label":7}""",
            """{"count":1,"label":false}""",
            """{"label":"ok"}""",
        )
        for (raw in cases) Fixture(folder.newFolder(), listOf(response(Call("one", "contract_tool", raw)))).use { f ->
            var callbacks = 0
            var effects = 0
            val tool = contractTool(needsApproval = { callbacks++; true }) { effects++; emptyList() }
            f.collect(listOf(tool), autoApproved = { _, _ -> callbacks++; true })
            assertEquals("Permission callbacks for $raw", 0, callbacks)
            assertEquals("Effects for $raw", 0, effects)
            assertInvalid(f.tools().single())
        }
    }

    @Test fun `enum constraint rejects unsupported value before approval`() = runBlocking {
        Fixture(folder.newFolder(), listOf(response(Call("one", "enum_tool", """{"mode":"destroy"}""")))).use { f ->
            var callbacks = 0
            val tool = Tool("enum_tool", "test", parameters = {
                InputSchema.Obj(parsed("""{"mode":{"type":"string","enum":["read","list"]}}""").jsonObject, listOf("mode"))
            }, needsApproval = { callbacks++; true }, execute = { fail("Invalid enum executed"); emptyList() })
            f.collect(listOf(tool), autoApproved = { _, _ -> callbacks++; true })
            assertEquals(0, callbacks)
            assertInvalid(f.tools().single())
        }
    }

    @Test fun `malformed and nonobject arguments remain correlated errors without permission callbacks`() = runBlocking {
        for (raw in listOf("{broken", "[]", "null", "7")) Fixture(folder.newFolder(), listOf(response(Call("one", "contract_tool", raw)))).use { f ->
            var callbacks = 0
            f.collect(listOf(contractTool(needsApproval = { callbacks++; true }) {
                fail("Invalid JSON executed"); emptyList()
            }), autoApproved = { _, _ -> callbacks++; true })
            assertEquals(0, callbacks)
            assertInvalid(f.tools().single())
            assertTranscript(f, listOf("contract_tool"))
        }
    }

    @Test fun `missing empty noarg input and unknown tool receive usable correlated results`() = runBlocking {
        for (raw in listOf<String?>(null, "", "{}")) Fixture(folder.newFolder(), listOf(response(Call("one", "noargs", raw)))).use { f ->
            var received: JsonElement? = null
            f.collect(listOf(Tool("noargs", "test", execute = { received = it; listOf(UIMessagePart.Text("ok")) })))
            assertEquals(parsed("{}"), received)
            assertTranscript(f, listOf("noargs"))
        }
        Fixture(folder.newFolder(), listOf(response(Call("one", "unknown", "{}")))).use { f ->
            f.collect(emptyList())
            assertTrue(f.tools().single().output.toString().contains("tool_not_found"))
            assertTranscript(f, listOf("unknown"))
        }
    }

    @Test fun `headless direct actions normalize before needsApproval and authorization`() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            val expected = parsed("""{"count":4,"enabled":true,"label":"007"}""")
            val approvals = mutableListOf<JsonElement>()
            val grants = mutableListOf<JsonElement>()
            val received = mutableListOf<JsonElement>()
            val tool = contractTool(needsApproval = { approvals += it; true }) { received += it; listOf(UIMessagePart.Text("ok")) }
            val result = f.direct(tool, parsed("""{"count":"4","enabled":"true","label":"007"}""")) { _, _, args -> grants += args; true }
            assertEquals(HeadlessTaskStatus.SUCCEEDED, result.status)
            assertTrue(approvals.isNotEmpty())
            assertTrue(grants.isNotEmpty())
            assertTrue(approvals.all { it == expected })
            assertTrue(grants.all { it == expected })
            assertEquals(listOf(expected), received)
        }
    }

    @Test fun `headless invalid direct argument fails before approval authorization and execution`() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            var callbacks = 0
            var effects = 0
            val result = f.direct(contractTool(needsApproval = { callbacks++; true }) { effects++; emptyList() },
                parsed("""{"count":"not-a-number","label":"ok"}""")) { _, _, _ -> callbacks++; true }
            assertEquals(0, callbacks)
            assertEquals(0, effects)
            assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals("invalid_tool_arguments", result.errorCode)
        }
    }

    @Test fun `nonstream duplicate ids retain separate calls approvals outputs and provider transcript`() = runBlocking {
        duplicateIds(stream = false)
    }

    @Test fun `stream duplicate ids and blank later fragments retain separate calls approvals outputs and transcript`() = runBlocking {
        duplicateIds(stream = true)
    }

    private suspend fun duplicateIds(stream: Boolean) {
        Fixture(folder.newFolder(), listOf(response(
            Call("same", "first_tool", """{"value":"first"}"""), Call("same", "second_tool", """{"value":"second"}""")
        )), stream = stream).use { f ->
            val effects = mutableListOf<String>()
            val grants = mutableListOf<String>()
            val tools = listOf("first", "second").map { label -> Tool("${label}_tool", "test", needsApproval = { true }, execute = {
                assertEquals(parsed("""{"value":"$label"}"""), it)
                effects += label; listOf(UIMessagePart.Text("result-$label"))
            }) }
            f.collect(tools, autoApproved = { name, _ -> grants += name; true })
            assertEquals("stream=$stream", listOf("first", "second"), effects)
            assertEquals(setOf("first_tool", "second_tool"), grants.toSet())
            val calls = f.tools()
            assertEquals(2, calls.size)
            assertEquals(2, calls.map { it.toolCallId }.toSet().size)
            assertTrue(calls.all { it.toolCallId.isNotBlank() })
            assertEquals(listOf("result-first", "result-second"), calls.map { it.output.filterIsInstance<UIMessagePart.Text>().single().text })
            assertTranscript(f, listOf("first_tool", "second_tool"))
        }
    }

    @Test fun `parallel unique ids allow automatic sibling while other approval waits`() = runBlocking {
        Fixture(folder.newFolder(), listOf(response(Call("a", "first_tool"), Call("b", "second_tool")))).use { f ->
            val effects = mutableListOf<String>()
            val tools = listOf("first", "second").map { label -> Tool("${label}_tool", "test", needsApproval = { true }, execute = {
                effects += label; listOf(UIMessagePart.Text("result-$label"))
            }) }
            f.collect(tools, autoApproved = { name, _ -> name == "first_tool" })
            assertEquals(listOf("first"), effects)
            assertTrue(f.tools().single { it.toolName == "first_tool" }.isExecuted)
            assertTrue(f.tools().single { it.toolName == "second_tool" }.isPending)
            assertEquals(setOf("a", "b"), f.tools().map { it.toolCallId }.toSet())
        }
    }

    @Test fun `repeated canonical call warns only provider copy and does not block effects or alter stored output`() = runBlocking {
        val secretLikeArgument = "do-not-echo-raw-argument"
        Fixture(folder.newFolder(), listOf(
            response(Call("first", "repeat_tool", """{"count":"2","label":"$secretLikeArgument"}""")),
            response(Call("second", "repeat_tool", """{"label":"$secretLikeArgument","count":2}""")),
        )).use { f ->
            var effects = 0
            val tool = Tool("repeat_tool", "test", parameters = { schema() }, execute = {
                effects++; listOf(UIMessagePart.Text("stable-result"))
            })
            f.collect(listOf(tool), maxSteps = 4)
            assertEquals(2, effects)
            assertEquals(2, f.tools().size)
            assertTrue(f.tools().all { it.output == listOf(UIMessagePart.Text("stable-result")) })
            val results = parsed(f.bodies.last()).jsonObject.getValue("messages").jsonArray
                .filter { it.jsonObject["role"] == JsonPrimitive("tool") }.map { it.jsonObject.getValue("content").jsonPrimitive.content }
            assertEquals(2, results.size)
            assertEquals("stable-result", results.first())
            assertTrue(results.last(), results.last().contains("повтор", ignoreCase = true))
            assertFalse(results.last(), results.last().contains(secretLikeArgument))
        }
    }

    @Test fun `restored duplicate approved ids never transfer approval or start either effect`() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            var effects = 0
            var approvals = 0
            val tools = listOf("first_tool", "second_tool").map { name -> Tool(name, "test", needsApproval = {
                approvals++; true
            }, execute = { effects++; listOf(UIMessagePart.Text("should-not-run")) }) }
            val restored = listOf(UIMessage.user("Проверь"), UIMessage.assistant("").copy(parts = tools.map { tool ->
                UIMessagePart.Tool(toolCallId = "duplicate", toolName = tool.name, input = "{}", output = emptyList(),
                    approvalState = ToolApprovalState.Approved)
            }))
            f.collect(tools, messages = restored)
            assertEquals(0, effects)
            assertEquals(0, approvals)
            assertEquals(2, f.tools().size)
            assertEquals(2, f.tools().map { it.toolCallId }.toSet().size)
            assertTrue(f.tools().all { it.isExecuted && it.output.toString().contains("duplicate_tool_call_id") })
            assertTrue(f.tools().all { Regex("[А-Яа-я]").containsMatchIn(it.output.toString()) })
            assertTranscript(f, listOf("first_tool", "second_tool"))
        }
    }

    @Test fun `restored duplicate id preserves completed output without assigning it to approved sibling`() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            var effects = 0
            val tools = listOf("first_tool", "second_tool").map { name -> Tool(name, "test", execute = {
                effects++; listOf(UIMessagePart.Text("should-not-run"))
            }) }
            val completed = UIMessagePart.Tool(toolCallId = "duplicate", toolName = "first_tool", input = "{}",
                output = listOf(UIMessagePart.Text("already-completed")), approvalState = ToolApprovalState.Approved)
            val pending = UIMessagePart.Tool(toolCallId = "duplicate", toolName = "second_tool", input = "{}",
                output = emptyList(), approvalState = ToolApprovalState.Approved)
            f.collect(tools, messages = listOf(UIMessage.user("Проверь"), UIMessage.assistant("").copy(parts = listOf(completed, pending))))
            assertEquals(0, effects)
            assertEquals(listOf(UIMessagePart.Text("already-completed")), f.tools().single { it.toolName == "first_tool" }.output)
            val sibling = f.tools().single { it.toolName == "second_tool" }
            assertTrue(sibling.output.toString(), sibling.output.toString().contains("duplicate_tool_call_id"))
            assertFalse(sibling.output.toString().contains("already-completed"))
            assertEquals(2, f.tools().map { it.toolCallId }.toSet().size)
            assertTranscript(f, listOf("first_tool", "second_tool"))
        }
    }

    @Test fun `native union branch wins while nullable and homogeneous numeric boolean items normalize`() = runBlocking {
        val properties = parsed("""{"native":{"anyOf":[{"type":"integer"},{"type":"string"}]},"nullable":{"type":["null","integer"]},"empty":{"type":["integer","null"]},"items":{"type":"array","items":{"anyOf":[{"type":"boolean"},{"type":"integer"}]}},"same":{"anyOf":[{"type":"integer"},{"type":"number"}]}}""").jsonObject
        val raw = """{"native":"007","nullable":"7","empty":null,"items":["true","2"],"same":"1"}"""
        val expected = parsed("""{"native":"007","nullable":7,"empty":null,"items":[true,2],"same":1}""")
        Fixture(folder.newFolder(), listOf(response(Call("one", "union_tool", raw)))).use { f ->
            val received = mutableListOf<JsonElement>()
            f.collect(listOf(Tool("union_tool", "test", parameters = { InputSchema.Obj(properties) }, execute = {
                received += it; listOf(UIMessagePart.Text("ok"))
            })))
            assertEquals(listOf(expected), received)
        }
    }

    @Test fun `ambiguous union coercion and overlapping oneOf fail before permissions`() = runBlocking {
        val schemas = listOf(
            """{"value":{"anyOf":[{"type":"object","properties":{"a":{"type":"integer"}}},{"type":"object","properties":{"b":{"type":"integer"}}}]}}""" to """{"value":{"a":"1","b":"2"}}""",
            """{"value":{"oneOf":[{"type":"integer"},{"type":"number"}]}}""" to """{"value":1}""",
        )
        for ((schema, raw) in schemas) Fixture(folder.newFolder(), listOf(response(Call("one", "union_tool", raw)))).use { f ->
            var callbacks = 0
            var effects = 0
            val tool = Tool("union_tool", "test", parameters = { InputSchema.Obj(parsed(schema).jsonObject) },
                needsApproval = { callbacks++; true }, execute = { effects++; emptyList() })
            f.collect(listOf(tool), autoApproved = { _, _ -> callbacks++; true })
            assertEquals(0, callbacks)
            assertEquals(0, effects)
            assertInvalid(f.tools().single())
        }
    }

    @Test fun `two unexecuted same ids across current path messages fail closed before effects`() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            var effects = 0
            val tools = listOf("first_tool", "second_tool").map { name -> Tool(name, "test", execute = {
                effects++; listOf(UIMessagePart.Text("should-not-run"))
            }) }
            fun call(name: String) = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
                toolCallId = "call_0", toolName = name, input = "{}", approvalState = ToolApprovalState.Approved)))
            f.collect(tools, messages = listOf(UIMessage.user("Первый ход"), call("first_tool"),
                UIMessage.user("Текущий ход"), call("second_tool")))
            assertEquals(0, effects)
            assertEquals(2, f.tools().size)
            assertTrue(f.tools().all { it.output.toString().contains("duplicate_tool_call_id") })
            assertEquals(2, f.tools().map { it.toolCallId }.toSet().size)
            assertTranscript(f, listOf("first_tool", "second_tool"))
        }
    }

    @Test fun `completed prior turn same id does not block single approved latest call`() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            var effects = 0
            val previous = UIMessagePart.Tool(toolCallId = "call_0", toolName = "first_tool", input = "{}",
                output = listOf(UIMessagePart.Text("completed-prior")), approvalState = ToolApprovalState.Approved)
            val current = UIMessagePart.Tool(toolCallId = "call_0", toolName = "second_tool", input = "{}",
                approvalState = ToolApprovalState.Approved)
            f.collect(listOf(Tool("first_tool", "test", execute = { fail("Completed call replayed"); emptyList() }),
                Tool("second_tool", "test", execute = { effects++; listOf(UIMessagePart.Text("completed-latest")) })),
                messages = listOf(UIMessage.user("Первый ход"), UIMessage.assistant("").copy(parts = listOf(previous)),
                    UIMessage.user("Текущий ход"), UIMessage.assistant("").copy(parts = listOf(current))))
            assertEquals(1, effects)
            assertEquals(listOf(UIMessagePart.Text("completed-prior")), f.tools().single { it.toolName == "first_tool" }.output)
            assertEquals(listOf(UIMessagePart.Text("completed-latest")), f.tools().single { it.toolName == "second_tool" }.output)
            val history = parsed(f.bodies.last()).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
            val ids = history.flatMap { (it["tool_calls"] as? JsonArray).orEmpty() }.map { it.jsonObject.getValue("id") }
            assertEquals(ids, history.filter { it["role"] == JsonPrimitive("tool") }.map { it.getValue("tool_call_id") })
        }
    }

    @Test fun `large repeated result keeps provider warning within cap and full stored JSON unchanged`() = runBlocking {
        val cap = 32 * 1024
        val raw = buildJsonObject { put("payload", "x".repeat(cap + 1000)) }.toString()
        Fixture(folder.newFolder(), listOf(response(Call("first", "large_repeat_tool")), response(Call("second", "large_repeat_tool")))).use { f ->
            var effects = 0
            f.collect(listOf(Tool("large_repeat_tool", "test", execute = {
                effects++; listOf(UIMessagePart.Text(raw))
            })), maxSteps = 4)
            assertEquals(2, effects)
            assertEquals(2, f.tools().size)
            assertTrue(f.tools().all { it.output == listOf(UIMessagePart.Text(raw)) })
            val results = parsed(f.bodies.last()).jsonObject.getValue("messages").jsonArray
                .filter { it.jsonObject["role"] == JsonPrimitive("tool") }
                .map { it.jsonObject.getValue("content").jsonPrimitive.content }
            assertEquals(2, results.size)
            assertTrue(results.all { it.length <= cap })
            assertFalse(results.first().contains("повтор", ignoreCase = true))
            assertTrue(results.last().takeLast(400), results.last().contains("повтор", ignoreCase = true))
        }
    }

    // Append inside GenerationLoopArgumentContractTest; uses its existing real provider/runtime Fixture.
    // Controls pass on pre-normalizer b892; frozen central01 introduces the target assertion failures.
    @Test fun `native integer disjoint oneOf ranges remain executable without guessed ambiguity`() = runBlocking {
        assertNativeConstrainedUnion(
            """{"oneOf":[{"type":"integer","maximum":5},{"type":"integer","minimum":6}]}""",
            """{"value":3}""",
        )
    }

    @Test fun `native string disjoint oneOf patterns preserve numeric looking text`() = runBlocking {
        assertNativeConstrainedUnion(
            """{"oneOf":[{"type":"string","pattern":"^[0-9]+$"},{"type":"string","pattern":"^[A-Za-z]+$"}]}""",
            """{"value":"007"}""",
        )
    }

    @Test fun `native string disjoint oneOf lengths stay strings and reach execution`() = runBlocking {
        assertNativeConstrainedUnion(
            """{"oneOf":[{"type":"string","maxLength":3},{"type":"string","minLength":4}]}""",
            """{"value":"012"}""",
        )
    }

    private suspend fun assertNativeConstrainedUnion(valueSchema: String, raw: String) {
        val expected = parsed(raw)
        val properties = buildJsonObject { put("value", parsed(valueSchema)) }
        Fixture(folder.newFolder(), listOf(response(Call("one", "constrained_union_tool", raw)))).use { f ->
            val approvals = mutableListOf<JsonElement>()
            val executed = mutableListOf<JsonElement>()
            val tool = Tool("constrained_union_tool", "test", parameters = { InputSchema.Obj(properties, listOf("value")) },
                needsApproval = { approvals += it; true }, execute = {
                    executed += it; listOf(UIMessagePart.Text("completed"))
                })
            f.collect(listOf(tool), autoApproved = { _, arguments ->
                assertEquals(expected, arguments)
                true
            })
            assertEquals("A valid unchanged native value must reach the real execution boundary", listOf(expected), executed)
            assertTrue(approvals.isNotEmpty())
            assertTrue(approvals.all { it == expected })
            assertEquals(expected, parsed(f.tools().single().input))
            assertEquals(listOf(UIMessagePart.Text("completed")), f.tools().single().output)
        }
    }

    @Test fun `quoted numeric input cannot guess oneOf through an unknown constrained branch`() = runBlocking {
        val properties = parsed("""{"value":{"oneOf":[{"type":"integer","maximum":5},{"type":"number"}]}}""").jsonObject
        Fixture(folder.newFolder(), listOf(response(Call("one", "mixed_union_tool", """{"value":"3"}""")))).use { f ->
            var callbacks = 0
            var effects = 0
            val tool = Tool("mixed_union_tool", "test", parameters = { InputSchema.Obj(properties) },
                needsApproval = { callbacks++; true }, execute = { effects++; listOf(UIMessagePart.Text("should-not-run")) })
            f.collect(listOf(tool), autoApproved = { _, _ -> callbacks++; true })
            assertEquals(0, callbacks)
            assertEquals(0, effects)
            assertInvalid(f.tools().single())
        }
    }

    private fun assertInvalid(tool: UIMessagePart.Tool) {
        assertTrue(tool.output.toString(), tool.output.toString().contains("invalid_tool_arguments"))
        assertTrue(tool.output.toString(), Regex("[А-Яа-я]").containsMatchIn(tool.output.toString()))
    }

    private fun assertTranscript(f: Fixture, names: List<String>) {
        val messages = parsed(f.bodies.last()).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
        val calls = messages.flatMap { (it["tool_calls"] as? JsonArray).orEmpty() }.map { it.jsonObject }
        val results = messages.filter { it["role"] == JsonPrimitive("tool") }
        assertEquals(names, calls.map { it.getValue("function").jsonObject.getValue("name").jsonPrimitive.content })
        val ids = calls.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids, results.map { it.getValue("tool_call_id").jsonPrimitive.content })
    }

    private fun contractTool(needsApproval: (JsonElement) -> Boolean = { false }, execute: suspend (JsonElement) -> List<UIMessagePart>) =
        Tool("contract_tool", "test", parameters = { schema() }, needsApproval = needsApproval, execute = execute)

    private class Fixture(directory: File, private val responses: List<String> = emptyList(), stream: Boolean = false) : Closeable {
        val bodies = CopyOnWriteArrayList<String>()
        private val requests = AtomicInteger()
        var latest = emptyList<UIMessage>()
        private val context = object : ContextWrapper(null) {
            override fun getCacheDir() = directory
            override fun getFilesDir() = directory
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            bodies += okio.Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            val raw = responses.getOrNull(requests.getAndIncrement()) ?: FINAL
            val content = if (stream) sse(raw) else raw
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        private val model = Model(modelId = "runtime-contract", abilities = listOf(ModelAbility.TOOL))
        private val owner = Assistant(chatModelId = model.id, streamOutput = stream)
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        private val settings = Settings(providers = listOf(provider), assistants = listOf(owner))
        private val loop = GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true })
        suspend fun collect(tools: List<Tool>, maxSteps: Int = 3, autoApproved: suspend (String, JsonElement) -> Boolean = { _, _ -> true },
            messages: List<UIMessage> = listOf(UIMessage.user("Проверь"))) {
            loop.generateText(settings, model, messages, assistant = owner,
                tools = tools, maxSteps = maxSteps, isToolAutoApproved = autoApproved).collect { chunk -> when (chunk) {
                is GenerationChunk.Messages -> latest = chunk.messages
                is GenerationChunk.RootExecutionCheckpoint -> { latest = chunk.messages; chunk.ack.complete(Unit) }
            } }
        }
        fun tools() = latest.flatMap { it.getTools() }
        suspend fun direct(tool: Tool, args: JsonElement, authorize: suspend (RunExecutionContext, String, JsonElement) -> Boolean) =
            HeadlessTaskRunner(loop, { _, _, _, _ -> listOf(tool) }, { settings }, object : HeadlessRunJournal {
                override suspend fun claim(runId: String) = true
                override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) {}
            }, authorize, { _, _ -> null }, { true }).run(
                HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction(tool.name, args))),
                RunExecutionContext(Uuid.random(), owner.id, RunOrigin.WORKFLOW, modelId = model.id.toString()),
            )
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    private data class Call(val id: String, val name: String, val arguments: String? = "{}")
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private fun parsed(value: String) = json.parseToJsonElement(value)
        private fun schema() = InputSchema.Obj(parsed("""{"count":{"type":"integer"},"ratio":{"type":"number"},"enabled":{"type":"boolean"},"label":{"type":"string"}}""").jsonObject, listOf("count", "label"))
        private const val FINAL = """{"id":"final","model":"runtime-contract","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Готово"}}]}"""
        private fun response(vararg calls: Call) = buildJsonObject {
            put("id", "reply"); put("model", "runtime-contract")
            putJsonArray("choices") { addJsonObject {
                put("finish_reason", "tool_calls")
                putJsonObject("message") {
                    put("role", "assistant")
                    putJsonArray("tool_calls") { calls.forEach { call -> addJsonObject {
                        put("id", call.id); put("type", "function")
                        putJsonObject("function") { put("name", call.name); call.arguments?.let { put("arguments", it) } }
                    } } }
                }
            } }
        }.toString()
        private fun sse(raw: String): String {
            val root = parsed(raw).jsonObject
            val choice = root.getValue("choices").jsonArray.single().jsonObject
            val message = choice.getValue("message").jsonObject
            val tools = message["tool_calls"] as? JsonArray
            val delta = JsonObject(message + if (tools == null) emptyMap() else mapOf("tool_calls" to JsonArray(tools.mapIndexed { index, item -> JsonObject(item.jsonObject + ("index" to JsonPrimitive(index))) })))
            fun event(payload: JsonObject, finish: JsonElement = JsonNull) = JsonObject(root + ("choices" to buildJsonArray { addJsonObject {
                put("delta", payload); put("finish_reason", finish)
            } }))
            if (tools == null) return "data: ${event(delta, choice.getValue("finish_reason"))}\n\ndata: [DONE]\n\n"
            fun fragment(first: Boolean) = buildJsonObject {
                putJsonArray("tool_calls") { tools.forEachIndexed { index, item -> addJsonObject {
                    val call = item.jsonObject
                    val function = call.getValue("function").jsonObject
                    val args = function["arguments"]?.jsonPrimitive?.content.orEmpty()
                    val split = args.length / 2
                    put("index", index)
                    put("id", if (first) call.getValue("id") else JsonPrimitive(""))
                    putJsonObject("function") {
                        if (first) put("name", function.getValue("name"))
                        put("arguments", if (first) args.take(split) else args.drop(split))
                    }
                } } }
            }
            return "data: ${event(fragment(true))}\n\ndata: ${event(fragment(false), choice.getValue("finish_reason"))}\n\ndata: [DONE]\n\n"
        }
    }
}
