package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.persistRootCheckpoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real generation loop and OpenAI wire decoder; only the HTTP boundary and root process are replaced. */
class GenerationLoopRootApprovalTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 15_000)
    fun `interrupted automatic or manually approved side effect is durable and never replayed`() = runBlocking {
        for (state in listOf(ToolApprovalState.Auto, ToolApprovalState.Approved)) {
            Fixture(folder.newFolder()).use { fixture ->
                val runs = AtomicInteger()
                val write = Tool("workspace_write_file", "test", execute = {
                    runs.incrementAndGet()
                    throw CancellationException("Simulated process loss after side effect")
                })
                try {
                    fixture.collect(listOf(UIMessage.assistant("").copy(parts = listOf(
                        UIMessagePart.Tool("write", write.name, "{}", approvalState = state),
                    ))), extraTools = listOf(write))
                    fail("Interrupted execution must propagate cancellation")
                } catch (_: CancellationException) { }
                assertEquals(1, runs.get())
                assertTrue("Start marker must be durable before execute", fixture.checkpointFile.exists())
                val saved = fixture.readCheckpoint()
                assertTrue(saved.last().getTools().single().isExecuted)
                fixture.collect(saved, extraTools = listOf(write), maxSteps = 1)
                assertEquals("Interrupted attempt must not run twice", 1, runs.get())
            }
        }
    }

    @Test(timeout = 15_000)
    fun `resume includes every fresh automatic sibling and excludes completed siblings`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            val runs = mutableListOf<String>()
            val tools = listOf("search_web", "workspace_write_file", "read_only_tool").map { name ->
                Tool(name, "test", execute = { runs += name; listOf(UIMessagePart.Text("done")) })
            }
            val batch = listOf(
                call("ordinary", "id -u").copy(approvalState = ToolApprovalState.Approved),
                UIMessagePart.Tool("search", "search_web", "{}"),
                UIMessagePart.Tool("write", "workspace_write_file", "{}"),
                UIMessagePart.Tool("read", "read_only_tool", "{}"),
                UIMessagePart.Tool("done", "read_only_tool", "{}", output = listOf(UIMessagePart.Text("old"))),
                call("dangerous", "reboot").copy(approvalState = ToolApprovalState.Pending),
            )
            fixture.collect(listOf(UIMessage.assistant("").copy(parts = batch)), extraTools = tools)
            assertEquals(listOf("search_web", "workspace_write_file", "read_only_tool"), runs)
            assertEquals(listOf("id -u"), fixture.commands)
            assertTrue(fixture.latest.last().getTools().single { it.toolCallId == "dangerous" }.isPending)
            assertEquals(0, fixture.requests.get())
        }
    }

    @Test(timeout = 15_000)
    fun `ordinary root executes before dangerous approval and is not replayed when approval arrives`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            fixture.collect(listOf(UIMessage.user("Run the two commands")))

            assertEquals(listOf("id -u"), fixture.commands)
            assertEquals("No continuation request while a dangerous sibling waits", 1, fixture.requests.get())
            val firstBatch = fixture.latest.last().getTools()
            val ordinary = firstBatch.single { it.toolCallId == "ordinary" }
            val dangerous = firstBatch.single { it.toolCallId == "dangerous" }
            assertTrue(ordinary.isExecuted)
            assertEquals(ToolApprovalState.Auto, ordinary.approvalState)
            assertFalse(dangerous.isExecuted)
            assertEquals(ToolApprovalState.Pending, dangerous.approvalState)
            assertEquals(listOf("ordinary"), fixture.persistedCalls)

            val approved = fixture.latest.dropLast(1) + fixture.latest.last().copy(
                parts = fixture.latest.last().parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == "dangerous") {
                        part.copy(approvalState = ToolApprovalState.Approved)
                    } else part
                },
            )
            fixture.collect(approved)

            assertEquals(listOf("id -u", "reboot"), fixture.commands)
            assertEquals(listOf("ordinary", "dangerous"), fixture.persistedCalls)
            assertEquals("Only the final answer needs a second provider request", 2, fixture.requests.get())
            assertEquals("Done", fixture.latest.last().toText().trim())
        }
    }

    @Test(timeout = 15_000)
    fun `revoked automatic root becomes pending while fresh unrelated automatic tools resume`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            fixture.automatic.set(false)
            val unrelatedRuns = AtomicInteger()
            val unrelated = Tool("other_tool", "Unrelated tool", execute = {
                unrelatedRuns.incrementAndGet()
                listOf(UIMessagePart.Text("other result"))
            })
            fixture.collect(
                listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                    call("ordinary", "id -u"),
                    UIMessagePart.Tool("other", "other_tool", "{}"),
                ))),
                extraTools = listOf(unrelated),
            )

            assertTrue(fixture.commands.isEmpty())
            assertEquals(listOf("other"), fixture.persistedCalls)
            assertEquals(0, fixture.requests.get())
            assertEquals(1, unrelatedRuns.get())
            val tools = fixture.latest.last().getTools()
            assertEquals(ToolApprovalState.Pending, tools.single { it.toolName == "root_exec" }.approvalState)
            assertEquals(ToolApprovalState.Auto, tools.single { it.toolName == "other_tool" }.approvalState)
            assertTrue(tools.single { it.toolName == "other_tool" }.isExecuted)
        }
    }

    @Test(timeout = 15_000)
    fun `revocation during checkpoint persistence prevents root launch`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            fixture.collect(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call("ordinary", "id -u")))), maxSteps = 1) {
                assertTrue(fixture.commands.isEmpty())
                fixture.automatic.set(false)
            }

            assertTrue(fixture.commands.isEmpty())
            assertEquals(listOf("ordinary"), fixture.persistedCalls)
            val savedCall = fixture.readCheckpoint().last().getTools().single()
            assertTrue("Persisted marker remains terminal after revocation", savedCall.isExecuted)
            assertFalse(savedCall.canResumeExecution)
        }
    }

    @Test(timeout = 15_000)
    fun `cancellation before durable acknowledgment never launches the root command`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            val checkpointReceived = CompletableDeferred<GenerationChunk.RootExecutionCheckpoint>()
            val releaseWrite = CompletableDeferred<Unit>()
            val job = launch {
                fixture.collect(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call("ordinary", "id -u"))))) { chunk ->
                    checkpointReceived.complete(chunk)
                    releaseWrite.await()
                }
            }
            val chunk = checkpointReceived.await()
            assertTrue(fixture.commands.isEmpty())
            assertFalse(fixture.checkpointFile.exists())
            job.cancelAndJoin()
            chunk.ack.complete(Unit) // Even a late acknowledgment must not revive the cancelled producer.
            assertTrue(fixture.commands.isEmpty())
            assertEquals(0, fixture.requests.get())
        }
    }

    private fun call(id: String, command: String) = UIMessagePart.Tool(
        id, "root_exec", buildJsonObject { put("command", command) }.toString(),
    )

    @Test(timeout = 15_000)
    fun `search result in the same batch restores root and workspace approval immediately`() = runBlocking {
        val response = """{"id":"response-tools","model":"test-chat","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"search","type":"function","function":{"name":"search_web","arguments":"{}"}},{"id":"ordinary","type":"function","function":{"name":"root_exec","arguments":"{\"command\":\"id -u\"}"}},{"id":"write","type":"function","function":{"name":"workspace_write_file","arguments":"{}"}}]}}]}"""
        Fixture(folder.newFolder(), response).use { fixture ->
            val writes = AtomicInteger()
            val search = Tool("search_web", "test", execute = { listOf(UIMessagePart.Text("external content")) })
            val workspace = Tool("workspace_write_file", "test", needsApproval = { !fixture.automatic.get() }, execute = {
                writes.incrementAndGet()
                listOf(UIMessagePart.Text("saved"))
            })
            val tools = listOf(search, workspace)
            val guard: suspend () -> Unit = { fixture.automatic.set(false) }
            fixture.collect(listOf(UIMessage.user("search then run")), extraTools = tools, onWebContentRead = guard)
            assertTrue(fixture.commands.isEmpty())
            assertEquals(0, writes.get())
            assertEquals(1, fixture.requests.get())
            val batch = fixture.latest.last().getTools()
            assertTrue(batch.single { it.toolCallId == "search" }.isExecuted)
            assertTrue(batch.single { it.toolCallId == "ordinary" }.isPending)
            assertTrue(batch.single { it.toolCallId == "write" }.isPending)

            val approved = fixture.latest.dropLast(1) + fixture.latest.last().copy(parts = fixture.latest.last().parts.map {
                if (it is UIMessagePart.Tool && it.toolCallId == "ordinary") it.copy(approvalState = ToolApprovalState.Approved) else it
            })
            fixture.collect(approved, extraTools = tools, onWebContentRead = guard)
            assertEquals(listOf("id -u"), fixture.commands)
            assertEquals(0, writes.get())
            assertEquals(1, fixture.requests.get())
        }
    }

    @Test(timeout = 15_000)
    fun `resumed conversation with web citation asks before fresh automatic root`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            val messages = listOf(UIMessage.assistant("source").copy(
                annotations = listOf(me.rerere.ai.ui.UIMessageAnnotation.UrlCitation("page", "https://example.test")),
            ), UIMessage.assistant("").copy(parts = listOf(call("ordinary", "id -u"))))
            fixture.collect(messages, onWebContentRead = { fixture.automatic.set(false) })
            assertTrue(fixture.commands.isEmpty())
            assertEquals(0, fixture.requests.get())
            assertTrue(fixture.latest.last().getTools().single().isPending)
        }
    }

    @Test(timeout = 15_000)
    fun `automatic workspace sibling executes while revoked root waits without replay`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            fixture.automatic.set(false)
            val runs = AtomicInteger()
            val workspace = Tool("workspace_write_file", "test", execute = {
                runs.incrementAndGet()
                listOf(UIMessagePart.Text("saved"))
            })
            fixture.collect(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                call("ordinary", "id -u"),
                UIMessagePart.Tool("write", "workspace_write_file", "{}"),
            ))), extraTools = listOf(workspace))
            assertEquals(1, runs.get())
            assertTrue(fixture.commands.isEmpty())
            assertEquals(0, fixture.requests.get())
            assertTrue(fixture.latest.last().getTools().single { it.toolCallId == "write" }.isExecuted)
            assertTrue(fixture.latest.last().getTools().single { it.toolCallId == "ordinary" }.isPending)
        }
    }

    private class Fixture(private val directory: File, private val firstResponse: String = TOOL_RESPONSE) : Closeable {
        val automatic = AtomicBoolean(true)
        val commands = CopyOnWriteArrayList<String>()
        val persistedCalls = CopyOnWriteArrayList<String>()
        val requests = AtomicInteger()
        val checkpointFile = File(directory, "conversation.json")
        var latest: List<UIMessage> = emptyList()
        private val json = Json { ignoreUnknownKeys = true }
        private val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = directory
            override fun getFilesDir(): File = directory
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (requests.incrementAndGet() == 1) firstResponse else FINAL_RESPONSE
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val model = Model(modelId = "test-chat", abilities = listOf(ModelAbility.TOOL))
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        private val settings = Settings(providers = listOf(provider))
        private val loop = GenerationLoop(context, ProviderManager(client, context), json)
        private val tool = Tool(
            name = "root_exec",
            description = "Test root command boundary",
            parameters = { InputSchema.Obj(buildJsonObject { put("command", buildJsonObject { put("type", "string") }) }, listOf("command")) },
            needsApproval = { args -> !automatic.get() || args.jsonObject["command"]?.jsonPrimitive?.content == "reboot" },
            execute = { args ->
                val command = args.jsonObject.getValue("command").jsonPrimitive.content
                val saved = readCheckpoint().last().getTools().single { it.inputAsJson().jsonObject["command"]?.jsonPrimitive?.content == command }
                assertTrue("Command cannot execute before its terminal marker is durable", saved.isExecuted)
                assertTrue(saved.output.toString().contains("root_execution_indeterminate"))
                commands += command
                listOf(UIMessagePart.Text("result for $command"))
            },
        )

        suspend fun collect(
            messages: List<UIMessage>,
            extraTools: List<Tool> = emptyList(),
            maxSteps: Int = 3,
            onWebContentRead: suspend () -> Unit = {},
            beforeWrite: suspend (GenerationChunk.RootExecutionCheckpoint) -> Unit = {},
        ) {
            latest = messages
            loop.generateText(settings = settings, model = model, messages = messages,
                assistant = Assistant(streamOutput = false), tools = listOf(tool) + extraTools, maxSteps = maxSteps,
                onWebContentRead = onWebContentRead,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> latest = chunk.messages
                    is GenerationChunk.RootExecutionCheckpoint -> {
                        beforeWrite(chunk)
                        persistRootCheckpoint(chunk.ack) {
                            FileOutputStream(checkpointFile).use {
                                it.write(json.encodeToString(chunk.messages).toByteArray())
                                it.fd.sync()
                            }
                            val newCall = chunk.messages.last().getTools().single {
                                it.isExecuted && it.toolCallId !in persistedCalls && it.output.toString().contains("execution_indeterminate")
                            }
                            persistedCalls += newCall.toolCallId
                            latest = chunk.messages
                        }
                    }
                }
            }
        }

        fun readCheckpoint(): List<UIMessage> = json.decodeFromString(checkpointFile.readText())

        override fun close() {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }

        companion object {
            private val TOOL_RESPONSE = """{"id":"response-tools","model":"test-chat","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"ordinary","type":"function","function":{"name":"root_exec","arguments":"{\"command\":\"id -u\"}"}},{"id":"dangerous","type":"function","function":{"name":"root_exec","arguments":"{\"command\":\"reboot\"}"}}]}}]}"""
            private val FINAL_RESPONSE = """{"id":"response-final","model":"test-chat","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Done"}}]}"""
        }
    }
}
