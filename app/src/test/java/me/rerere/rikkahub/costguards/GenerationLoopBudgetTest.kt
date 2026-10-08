package me.rerere.rikkahub.costguards

import android.content.ContextWrapper
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real GenerationLoop and supported OpenAI wire format; only HTTP transport is replaced. */
class GenerationLoopBudgetTest {
    @get:Rule val folder = TemporaryFolder()
    private fun assistant(cap: Int, enabled: Boolean = true, stream: Boolean = false) = Assistant(
        streamOutput = stream, tokenBudgetHardCap = cap,
        localTools = if (enabled) listOf(LocalToolOption.CostGuards) else emptyList())

    @Test fun `disabled caps remain inert and historic hard cap stops before provider`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            fixture.collect(assistant(1, enabled = false))
            assertEquals(1, fixture.requests.size)
        }
        Fixture(folder.newFolder()).use { fixture ->
            try {
                fixture.collect(assistant(100), listOf(UIMessage.assistant("history").copy(usage = TokenUsage(50, 50)), UIMessage.user("next")))
                fail("must stop")
            } catch (_: TokenBudgetExceededException) {}
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `wire max_tokens is bounded after custom body merges`() = runBlocking {
        Fixture(folder.newFolder()).use { fixture ->
            fixture.collect(assistant(100).copy(maxTokens = 1000,
                customBodies = listOf(CustomBody("max_tokens", JsonPrimitive(99999)))))
            val limit = Json.parseToJsonElement(fixture.requests.single()).jsonObject.getValue("max_tokens").jsonPrimitive.int
            assertTrue(limit in 1..99)
        }
    }

    @Test fun `observed hard crossing persists response and blocks tool and next request`() = runBlocking {
        Fixture(folder.newFolder(), toolResponse(100)).use { fixture ->
            var executed = 0
            val tool = Tool("side_effect", "test", execute = { executed++; listOf(UIMessagePart.Text("done")) })
            try { fixture.collect(assistant(100), tools = listOf(tool)); fail("must stop") }
            catch (_: TokenBudgetExceededException) {}
            assertEquals(0, executed)
            assertEquals(1, fixture.requests.size)
            assertTrue(fixture.latest.last().getTools().isNotEmpty())
        }
    }

    @Test fun `stream crossing stops consumption and tools while preserving observed usage`() = runBlocking {
        val response = "data: " + toolResponse(100) + "\n\ndata: [DONE]\n\n"
        Fixture(folder.newFolder(), response, streaming = true).use { fixture ->
            val ledger = TokenBudgetLedger(true, hardCap = 100)
            var executed = 0
            try {
                fixture.collect(assistant(100, stream = true), budget = ledger,
                    tools = listOf(Tool("side_effect", "test", execute = { executed++; emptyList() })))
                fail("must stop")
            } catch (_: TokenBudgetExceededException) {}
            assertEquals(100L, ledger.snapshot().spentTokens)
            assertEquals(0L, ledger.snapshot().reservedTokens)
            assertEquals(0, executed)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun `unknown usage network error cannot retry against refunded parent capacity`() = runBlocking {
        Fixture(folder.newFolder(), failure = IOException("network unavailable")).use { fixture ->
            val ledger = TokenBudgetLedger(true, hardCap = 100)
            try { fixture.collect(assistant(100), budget = ledger); fail("must stop") }
            catch (_: TokenBudgetExceededException) {}
            assertEquals(100L, ledger.snapshot().spentTokens)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test(timeout = 15000) fun `cancellation remains cancellation and closes submitted reservation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Fixture(folder.newFolder(), onRequest = { started.complete(Unit); release.await() }).use { fixture ->
            val ledger = TokenBudgetLedger(true, hardCap = 100)
            val generation = launch { fixture.collect(assistant(100), budget = ledger) }
            try {
                started.await()
                generation.cancelAndJoin()
                assertTrue(generation.isCancelled)
                assertEquals(0L, ledger.snapshot().reservedTokens)
                assertEquals(1, fixture.requests.size)
            } finally {
                release.countDown()
                generation.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 15000) fun `durable cleanup failure preserves genuine cancellation and blocks next bill`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val caller = assistant(100)
        var writes = 0
        val store = TokenBudgetStore(folder.newFolder()) { file, record ->
            writes++
            if (writes > 1) throw IOException("disk full during cancellation")
            file.writeText(record)
        }
        val ledger = store.getLedger(caller, Uuid.random(), emptyList())
        var observed: CancellationException? = null
        Fixture(folder.newFolder(), onRequest = { started.complete(Unit); release.await() }).use { fixture ->
            val generation = launch {
                try { fixture.collect(caller, budget = ledger) }
                catch (cancelled: CancellationException) { observed = cancelled; throw cancelled }
            }
            try {
                started.await()
                generation.cancelAndJoin()
                assertTrue(generation.isCancelled)
                assertNotNull(observed)
                assertThrows(TokenBudgetPersistenceException::class.java) { ledger.ensureCanContinue() }
                assertTrue(writes >= 2)
                assertEquals(0L, ledger.snapshot().reservedTokens)
                assertEquals(1, fixture.requests.size)
            } finally {
                release.countDown()
                generation.cancelAndJoin()
            }
        }
    }

    @Test fun `explicit disabled ledger observes enabling between tool and next provider`() = runBlocking {
        val caller = assistant(1, enabled = false)
        var latest: Assistant? = caller
        val ledger = TokenBudgetStore(folder.newFolder(), assistantSource = { latest })
            .getLedger(caller, Uuid.random(), emptyList())
        Fixture(folder.newFolder(), toolResponse(15)).use { fixture ->
            val enable = Tool("side_effect", "test", execute = {
                latest = caller.copy(localTools = listOf(LocalToolOption.CostGuards))
                listOf(UIMessagePart.Text("enabled"))
            })
            try { fixture.collect(caller, budget = ledger, tools = listOf(enable)); fail("must stop") }
            catch (_: TokenBudgetExceededException) {}
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun `trusted before-request policy blocks billing and refunds unsubmitted reservation`() = runBlocking {
        val ledger = TokenBudgetLedger(true, hardCap = 100)
        Fixture(folder.newFolder()).use { fixture ->
            var preflights = 0
            try {
                fixture.collect(assistant(100), budget = ledger, beforeModelRequest = {
                    preflights++
                    throw IllegalStateException("background_run_blocked")
                })
                fail("must block")
            } catch (blocked: IllegalStateException) { assertEquals("background_run_blocked", blocked.message) }
            assertEquals(1, preflights)
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0L, ledger.snapshot().spentTokens)
            assertEquals(0L, ledger.snapshot().reservedTokens)
        }
    }

    private class Fixture(directory: File, private val response: String = FINAL,
                          streaming: Boolean = false, private val failure: Throwable? = null,
                          private val onRequest: () -> Unit = {}) : Closeable {
        val requests = mutableListOf<String>()
        var latest = emptyList<UIMessage>()
        private val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = directory
            override fun getFilesDir(): File = directory
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += buffer.readUtf8()
            onRequest()
            failure?.let { throw it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        private val model = Model(modelId = "test-chat", abilities = listOf(ModelAbility.TOOL))
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        private val settings = Settings(providers = listOf(provider))
        private val loop = GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true })
        suspend fun collect(assistant: Assistant, messages: List<UIMessage> = listOf(UIMessage.user("test")),
                            tools: List<Tool> = emptyList(), budget: TokenBudgetLedger? = null,
                            beforeModelRequest: suspend () -> Unit = {}) {
            latest = messages
            loop.generateText(settings, model, messages, assistant = assistant, tools = tools, maxSteps = 3,
                tokenBudget = budget, beforeModelRequest = beforeModelRequest).collect { chunk -> when (chunk) {
                is GenerationChunk.Messages -> latest = chunk.messages
                is GenerationChunk.RootExecutionCheckpoint -> { latest = chunk.messages; chunk.ack.complete(Unit) }
            } }
        }
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        companion object {
            private const val FINAL = """{"id":"final","model":"test-chat","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Done"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        }
    }
    companion object {
        private fun toolResponse(total: Int) = """{"id":"tool","model":"test-chat","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call","type":"function","function":{"name":"side_effect","arguments":"{}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":${total - 10},"total_tokens":$total}}"""
    }
}
