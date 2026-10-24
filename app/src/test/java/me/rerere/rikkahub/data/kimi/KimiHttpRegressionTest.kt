package me.rerere.rikkahub.data.kimi

import android.app.Application
import java.io.Closeable
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual provider, native SSE decoder and generation loop; only the HTTP boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KimiHttpRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val pro = KimiModelInfo("k3.1-pro", reasoning = true, thinkingType = "only",
        efforts = listOf("low", "high", "max"), defaultEffort = "max")

    @Test fun `HTTP 400 is reported once by the generation loop rather than retried as no network`() = runBlocking {
        for (stream in listOf(true, false)) {
            Fixture(pro) { 400 to """{"error":{"type":"invalid_request_error","message":"Unsupported parameter: synthetic_parameter"}}""" }.use { fixture ->
                val context = RuntimeEnvironment.getApplication()
                val manager = ProviderManager(fixture.client, context).apply { registerProvider("kimi_code", fixture.provider) }
                val owner = Assistant(chatModelId = fixture.model.id, streamOutput = stream)
                val settings = Settings(providers = listOf(fixture.setting), assistants = listOf(owner))
                val status = MutableStateFlow<String?>(null)
                val statuses = CopyOnWriteArrayList<String?>()
                val observer = launch(start = CoroutineStart.UNDISPATCHED) { status.collect { statuses += it } }
                val error = try {
                    runCatching {
                        withTimeout(15_000) {
                            GenerationLoop(context, manager, json).generateText(settings, fixture.model,
                                listOf(UIMessage.user("Привет")), assistant = owner, processingStatus = status).collect()
                        }
                    }.exceptionOrNull()
                } finally { observer.cancelAndJoin() }
                assertNotNull(error)
                assertEquals("A received HTTP response must not be resent as a disconnected network", 1, fixture.requests.size)
                assertFalse(error is IOException)
                assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("HTTP 400"))
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("synthetic_parameter"))
                assertFalse(statuses.toString(), statuses.any { it?.contains("соединение", ignoreCase = true) == true })
            }
        }
    }

    @Test fun `real DNS failure keeps the existing network retry and then completes`() = runBlocking {
        val attempts = AtomicInteger()
        Fixture(pro) {
            if (attempts.incrementAndGet() == 1) throw UnknownHostException("synthetic.invalid")
            200 to success
        }.use { fixture ->
            val context = RuntimeEnvironment.getApplication()
            val manager = ProviderManager(fixture.client, context).apply { registerProvider("kimi_code", fixture.provider) }
            val owner = Assistant(chatModelId = fixture.model.id, streamOutput = true)
            val settings = Settings(providers = listOf(fixture.setting), assistants = listOf(owner))
            var output = ""
            withTimeout(10_000) {
                GenerationLoop(context, manager, json).generateText(settings, fixture.model,
                    listOf(UIMessage.user("Привет")), assistant = owner).collect { chunk ->
                    if (chunk is me.rerere.rikkahub.data.ai.GenerationChunk.Messages) {
                        output = chunk.messages.last().parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                    }
                }
            }
            assertEquals(2, attempts.get())
            assertEquals("OK", output)
        }
    }

    @Test fun `generation failure preserves the server reason but redacts both account tokens`() = runBlocking {
        val body = buildJsonObject { putJsonObject("error") {
            put("type", "invalid_request_error")
            put("message", "K3.1 Pro does not support disabled thinking mode. access-secret-rikka refresh-secret-rikka Authorization: Bearer other-secret")
        } }.toString()
        Fixture(pro) { 400 to body }.use { fixture ->
            val error = runCatching { fixture.ask() }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("does not support disabled thinking"))
            for (secret in listOf("access-secret-rikka", "refresh-secret-rikka", "other-secret")) {
                assertFalse(error.stackTraceToString(), error.stackTraceToString().contains(secret))
            }
        }
    }

    @Test fun `malformed and oversized error bodies remain failed responses without raw content`() = runBlocking {
        for (body in listOf("<html>access-secret-rikka</html>",
            """{"error":{"message":"${"x".repeat(70_000)}access-secret-rikka"}}""")) {
            Fixture(pro) { 400 to body }.use { fixture ->
                val error = runCatching { fixture.ask() }.exceptionOrNull()
                assertNotNull(error)
                assertTrue(error!!.message.orEmpty().contains("HTTP 400"))
                assertTrue(error.message.orEmpty().length < 4_096)
                assertFalse(error.stackTraceToString().contains("access-secret-rikka"))
            }
        }
    }

    @Test fun `Pro requires thinking even with legacy catalog metadata and an off chat snapshot`() = runBlocking {
        for (id in listOf("k3.1-pro", "k3.1-pro-highspeed")) {
            val info = parseKimiModels(json.parseToJsonElement("""{"data":[{"id":"$id","supports_reasoning":true}]}""").jsonObject).single()
            Fixture(info) { request ->
                if (payload(request)["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "disabled") {
                    400 to """{"error":{"message":"K3.1 Pro does not support disabled thinking mode. Please enable thinking and try again, or choose another model."}}"""
                } else 200 to success
            }.use { fixture ->
                val result = fixture.ask(level = ReasoningLevel.OFF)
                assertEquals("OK", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
                val body = payload(fixture.requests.single())
                assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                val effort = body["thinking"]!!.jsonObject["effort"] ?: body["reasoning_effort"]
                assertEquals("max", effort?.jsonPrimitive?.content)
            }
        }
    }

    @Test fun `empty streamed reasoning survives a tool continuation as the required empty field`() = runBlocking {
        for (reasoning in listOf("", "Existing reasoning")) {
            val arguments = """{"command":"printf '[%s]\\n' 'a b'; status 2>/dev/null"}"""
            val first = buildJsonObject { putJsonArray("choices") { addJsonObject {
                put("index", 0)
                putJsonObject("delta") {
                    put("reasoning_content", reasoning)
                    putJsonArray("tool_calls") { addJsonObject {
                        put("index", 0); put("id", "call-1"); put("type", "function")
                        putJsonObject("function") { put("name", "root_exec"); put("arguments", arguments) }
                    } }
                }
                put("finish_reason", "tool_calls")
            } } }.let { "data: $it\n\ndata: [DONE]\n\n" }
            val calls = AtomicInteger()
            Fixture(pro) { request ->
                if (calls.incrementAndGet() == 1) 200 to first else {
                    val assistant = payload(request)["messages"]!!.jsonArray.map { it.jsonObject }
                        .first { it["role"]?.jsonPrimitive?.content == "assistant" }
                    if (assistant["reasoning_content"] !is JsonPrimitive) {
                        400 to """{"error":{"message":"thinking is enabled but reasoning_content is missing in assistant tool call message at index 1"}}"""
                    } else 200 to success
                }
            }.use { fixture ->
                val user = UIMessage.user("Проверь")
                val tools = listOf(Tool("root_exec", "test", execute = { listOf(UIMessagePart.Text("OK")) }))
                val response = fixture.ask(listOf(user), tools = tools).message
                val completed = response.copy(parts = response.parts.map {
                    if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("OK"))) else it
                })
                val final = fixture.ask(listOf(user, completed), tools = tools)
                assertEquals("OK", final.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
                val assistant = payload(fixture.requests.last())["messages"]!!.jsonArray.map { it.jsonObject }
                    .first { it["role"]?.jsonPrimitive?.content == "assistant" }
                assertEquals(reasoning, assistant["reasoning_content"]!!.jsonPrimitive.content)
                assertEquals(arguments, assistant["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun `missing Pro default uses an advertised effort rather than an unsupported fallback`() = runBlocking {
        val info = pro.copy(efforts = listOf("low", "high"), defaultEffort = null)
        Fixture(info) { request ->
            val body = payload(request)
            val effort = body["thinking"]?.jsonObject?.get("effort") ?: body["reasoning_effort"]
            if (effort?.jsonPrimitive?.content !in listOf("low", "high")) {
                400 to """{"error":{"message":"Unsupported reasoning effort"}}"""
            } else 200 to success
        }.use { fixture ->
            val result = fixture.ask()
            assertEquals("OK", result.message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
            val body = payload(fixture.requests.single())
            val effort = body["thinking"]?.jsonObject?.get("effort") ?: body["reasoning_effort"]
            assertEquals("low", effort!!.jsonPrimitive.content)
        }
    }

    private fun payload(request: Request) = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    private inner class Fixture(info: KimiModelInfo, respond: (Request) -> Pair<Int, String>) : Closeable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (status, body) = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body(body.toResponseBody((if (body.startsWith("data:")) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        val model = info.toModel()
        val setting = ProviderSetting.KimiCode(enabled = true, models = listOf(model))
        private val store = KimiCredentialStore(folder.newFolder().resolve("session.enc"), json) {
            SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        }.apply { write(KimiAccount(KimiRegion.CN,
            KimiTokens("access-secret-rikka", "refresh-secret-rikka", Long.MAX_VALUE), models = listOf(info))) }
        private val api = KimiApi(client, json, { mapOf("User-Agent" to "Rikka-Root/test") })
        val provider = KimiProvider(client, KimiAccountRepository(store, api, scope), api, json)

        suspend fun ask(messages: List<UIMessage> = listOf(UIMessage.user("Привет")),
            level: ReasoningLevel = ReasoningLevel.AUTO, tools: List<Tool> = emptyList()) =
            provider.generateText(setting, messages, TextGenerationParams(model, reasoningLevel = level, tools = tools))

        override fun close() {
            scope.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private val success = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
}
