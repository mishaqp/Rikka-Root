package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the real loop with token caps disabled and a finite, deterministic provider. */
class GenerationLoopStepLimitTest {
    @get:Rule val folder = TemporaryFolder()
    private val savedSteps = ToolRuntimeLimits.maxToolSteps
    private val savedTime = ToolRuntimeLimits.turnBudgetMs

    @After fun restoreLimits() {
        ToolRuntimeLimits.maxToolSteps = savedSteps
        ToolRuntimeLimits.turnBudgetMs = savedTime
    }

    @Test fun `unlimited steps completes more than 32 tool rounds`() = runBlocking {
        ToolRuntimeLimits.maxToolSteps = 0
        Fixture(folder.newFolder()).use { f ->
            f.collect()
            assertEquals(35, f.effects)
            assertEquals(36, f.requests.get())
            assertNull(f.limit)
            assertEquals("Проверено", f.latest.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
        }
    }

    @Test fun `numeric global cap still stops the response`() = runBlocking {
        ToolRuntimeLimits.maxToolSteps = 3
        Fixture(folder.newFolder()).use { f ->
            f.collect()
            assertEquals(3, f.effects)
            assertEquals(3, f.requests.get())
            assertEquals("step_limit", f.limit)
            assertTrue(f.latest.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("лимит шагов") })
        }
    }

    @Test fun `explicit caller cap survives unlimited global steps`() = runBlocking {
        ToolRuntimeLimits.maxToolSteps = 0
        Fixture(folder.newFolder()).use { f ->
            f.collect(maxSteps = 2)
            assertEquals(2, f.effects)
            assertEquals("step_limit", f.limit)
        }
    }

    @Test fun `stricter numeric global cap also applies to a bounded caller`() = runBlocking {
        ToolRuntimeLimits.maxToolSteps = 2
        Fixture(folder.newFolder()).use { f ->
            f.collect(maxSteps = 5)
            assertEquals(2, f.effects)
            assertEquals("step_limit", f.limit)
        }
    }

    @Test fun `unlimited steps preserves the separate response deadline`() = runBlocking {
        ToolRuntimeLimits.maxToolSteps = 0
        ToolRuntimeLimits.turnBudgetMs = 60_000
        val clock = AtomicLong(0)
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } answers { clock.get() }
            Fixture(folder.newFolder(), afterTool = { clock.set(60_001) }).use { f ->
                f.collect()
                assertEquals(1, f.effects)
                assertEquals(1, f.requests.get())
                assertEquals("time_limit", f.limit)
                assertTrue(f.latest.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("лимит времени") })
            }
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun `background job and subagent keep their explicit step caps`() = runBlocking {
        ToolRuntimeLimits.maxToolSteps = 0
        for (origin in listOf(RunOrigin.CRON, RunOrigin.SUB_AGENT)) {
            Fixture(folder.newFolder()).use { f ->
                val result = f.headless(origin, 2)
                assertEquals(HeadlessTaskStatus.FAILED, result.status)
                assertEquals("step_limit", result.errorCode)
                assertEquals(2, f.effects)
                assertEquals(2, f.requests.get())
            }
        }
    }

    private class Fixture(directory: File, afterTool: () -> Unit = {}) : Closeable {
        val requests = AtomicInteger()
        var effects = 0
        var latest = emptyList<UIMessage>()
        var limit: String? = null
        private val context = object : ContextWrapper(null) {
            override fun getCacheDir() = directory
            override fun getFilesDir() = directory
        }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val step = requests.incrementAndGet()
            val body = buildJsonObject {
                put("id", "reply-$step"); put("model", "step-test")
                putJsonArray("choices") { addJsonObject {
                    put("finish_reason", if (step <= 35) "tool_calls" else "stop")
                    putJsonObject("message") {
                        put("role", "assistant")
                        if (step > 35) put("content", "Проверено") else putJsonArray("tool_calls") { addJsonObject {
                            put("id", "call-$step"); put("type", "function")
                            putJsonObject("function") {
                                put("name", "step_tool"); put("arguments", "{\"step\":$step}")
                            }
                        } }
                    }
                } }
            }.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val model = Model(modelId = "step-test", abilities = listOf(ModelAbility.TOOL))
        private val owner = Assistant(chatModelId = model.id, streamOutput = false)
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        private val settings = Settings(providers = listOf(provider), assistants = listOf(owner))
        private val loop = GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true })
        private val tool = Tool("step_tool", "test", execute = { effects++; afterTool(); listOf(UIMessagePart.Text("ok")) })

        suspend fun collect(maxSteps: Int? = null) {
            val stream = if (maxSteps == null) loop.generateText(settings, model, listOf(UIMessage.user("Проверь")),
                assistant = owner, tools = listOf(tool), onLimitReached = { limit = it })
            else loop.generateText(settings, model, listOf(UIMessage.user("Проверь")),
                assistant = owner, tools = listOf(tool), maxSteps = maxSteps, onLimitReached = { limit = it })
            stream.collect { chunk -> when (chunk) {
                is GenerationChunk.Messages -> latest = chunk.messages
                is GenerationChunk.RootExecutionCheckpoint -> { latest = chunk.messages; chunk.ack.complete(Unit) }
            } }
        }

        suspend fun headless(origin: RunOrigin, steps: Int) = HeadlessTaskRunner(loop,
            { _, _, _, _ -> listOf(tool) }, { settings }, object : HeadlessRunJournal {
                override suspend fun claim(runId: String) = true
                override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) {}
            }, { _, _, _ -> true }, { _, _ -> null }, { true }).run(
                HeadlessTaskRequest.Prompt("Проверь"),
                RunExecutionContext(Uuid.random(), owner.id, origin, modelId = model.id.toString(), maxSteps = steps),
            )

        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
