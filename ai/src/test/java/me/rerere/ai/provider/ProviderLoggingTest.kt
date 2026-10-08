package me.rerere.ai.provider

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Real provider requests and SSE callbacks, with Android logcat captured by Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProviderLoggingTest {
    private val requestCanary = "WORKSPACE_INSTRUCTIONS_PRIVATE_90210"
    private val responseCanary = "PRIVATE_ASSISTANT_REPLY_73519"
    private val secretCanary = "sk-private-provider-key-abcdefghijklmno"
    private val params = TextGenerationParams(model = Model(modelId = "gpt-4o"))
    private val messages = listOf(UIMessage.system(requestCanary), UIMessage.user("$requestCanary $secretCanary"))
    private val output = ByteArrayOutputStream()
    private lateinit var oldOut: PrintStream
    private lateinit var oldErr: PrintStream
    private var capturedRequest: Request? = null

    @Before
    fun captureLogs() {
        ShadowLog.clear()
        oldOut = System.out
        oldErr = System.err
        System.setOut(PrintStream(output))
        System.setErr(PrintStream(output))
    }

    @After
    fun restoreStreams() {
        System.setOut(oldOut)
        System.setErr(oldErr)
    }

    @Test
    fun `ordinary chat completions request preserves conversation but only logs metadata`() = runBlocking {
        val api = ChatCompletionsAPI(client(chatResponse()), KeyRoulette.default())
        val result = api.generateText(openAI(), messages, params)
        assertEquals(responseCanary, result.message.toText())
        assertPrivateRequestAndLogs("openai")
    }

    @Test
    fun `ordinary responses request never logs request or response bodies`() = runBlocking {
        val api = ResponseAPI(client(responsesResponse()))
        val result = api.generateText(openAI(), messages, params)
        assertEquals(responseCanary, result.message.toText())
        assertPrivateRequestAndLogs("openai")
    }

    @Test
    fun `ordinary google request and grounding never log message or search data`() = runBlocking {
        val response = """{"candidates":[{"content":{"parts":[{"text":"$responseCanary"}]},"groundingMetadata":{"webSearchQueries":["$responseCanary"],"groundingChunks":[]}}]}"""
        val result = GoogleProvider(client(response)).generateText(google(), messages, params)
        assertEquals(responseCanary, result.message.toText())
        assertPrivateRequestAndLogs("google")
    }

    @Test
    fun `ordinary claude request and redacted thinking only log metadata`() = runBlocking {
        val response = """{"id":"reply","model":"gpt-4o","content":[{"type":"text","text":"$responseCanary"},{"type":"redacted_thinking","data":"$secretCanary"}],"stop_reason":"end_turn"}"""
        val result = ClaudeProvider(client(response)).generateText(claude(), messages, params)
        assertEquals(responseCanary, result.message.toText())
        assertPrivateRequestAndLogs("claude")
    }

    @Test
    fun `ordinary interactions request logs only provider metadata`() = runBlocking {
        val response = """{"id":"reply","model":"gpt-4o","steps":[{"type":"model_output","content":[{"type":"text","text":"$responseCanary"}]}]}"""
        val result = GoogleProvider(client(response)).generateText(google().copy(useInteractionsApi = true), messages, params)
        assertEquals(responseCanary, result.message.toText())
        assertPrivateRequestAndLogs("google")
    }

    @Test
    fun `image generation does not log prompt or encoded image`() = runBlocking {
        val api = OpenAIProvider(client("""{"data":[{"b64_json":"$responseCanary"}]}"""))
        val images = api.generateImage(openAI(), ImageGenerationParams(params.model, "$requestCanary $secretCanary")).toList()
        assertEquals(responseCanary, images.single().data)
        assertPrivateRequestAndLogs("openai")
    }

    @Test
    fun `google models response only logs its status and size`() = runBlocking {
        val response = """{"models":[{"name":"models/gpt-4o","displayName":"$responseCanary","supportedGenerationMethods":["generateContent"]}]}"""
        val models = GoogleProvider(client(response)).listModels(google())
        assertEquals(responseCanary, models.single().displayName)
        assertSafeLogs("google", expectsModel = false)
    }

    @Test
    fun `chat completions stream does not log private SSE events`() = runBlocking {
        val data = """{"id":"reply","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"$responseCanary"}}]}"""
        val chunks = withTimeout(5_000) {
            ChatCompletionsAPI(client(sse(data), sse = true), KeyRoulette.default()).streamText(openAI(), messages, params).toList()
        }
        assertTrue(chunks.isNotEmpty())
        assertPrivateRequestAndLogs("openai")
    }

    @Test
    fun `responses stream does not log private SSE events`() = runBlocking {
        val data = """{"type":"response.output_text.delta","item_id":"item","content_index":0,"delta":"$responseCanary"}"""
        val chunks = withTimeout(5_000) { ResponseAPI(client("event: response.output_text.delta\n" + sse(data), sse = true)).streamText(openAI(), messages, params).toList() }
        assertTrue(chunks.isNotEmpty())
        assertPrivateRequestAndLogs("openai")
    }

    @Test
    fun `google stream does not log private SSE events`() = runBlocking {
        val data = """{"candidates":[{"content":{"parts":[{"text":"$responseCanary"}]}}]}"""
        val chunks = withTimeout(5_000) { GoogleProvider(client(sse(data), sse = true)).streamText(google(), messages, params).toList() }
        assertTrue(chunks.isNotEmpty())
        assertPrivateRequestAndLogs("google")
    }

    @Test
    fun `claude stream does not log private SSE events`() = runBlocking {
        val start = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$responseCanary"}}"""
        val events = "event: content_block_start\n" + sse(start) + "event: content_block_delta\n" + sse(data)
        val chunks = withTimeout(5_000) { ClaudeProvider(client(events, sse = true)).streamText(claude(), messages, params).toList() }
        assertTrue(chunks.isNotEmpty())
        assertPrivateRequestAndLogs("claude")
    }

    @Test
    fun `interactions stream does not log private SSE events`() = runBlocking {
        val data = """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"$responseCanary"}}"""
        val chunks = withTimeout(5_000) {
            GoogleProvider(client(sse(data), sse = true)).streamText(google().copy(useInteractionsApi = true), messages, params).toList()
        }
        assertTrue(chunks.isNotEmpty())
        assertPrivateRequestAndLogs("google")
    }

    @Test
    fun `stream failures preserve user error without logging response or exception payload`() = runBlocking {
        for (provider in listOf("openai-chat", "openai-responses", "google", "claude", "google-interactions")) {
            ShadowLog.clear()
            output.reset()
            val error = """{"error":{"message":"$responseCanary $secretCanary"}}"""
            val http = client(error, code = 400)
            val failure = runCatching {
                withTimeout(5_000) {
                    when (provider) {
                        "openai-chat" -> ChatCompletionsAPI(http, KeyRoulette.default()).streamText(openAI(), messages, params)
                        "openai-responses" -> ResponseAPI(http).streamText(openAI(), messages, params)
                        "google" -> GoogleProvider(http).streamText(google(), messages, params)
                        "google-interactions" -> GoogleProvider(http).streamText(google().copy(useInteractionsApi = true), messages, params)
                        else -> ClaudeProvider(http).streamText(claude(), messages, params)
                    }.toList()
                }
            }.exceptionOrNull()
            assertTrue("$provider should report the actual API error", failure?.message?.contains(responseCanary) == true)
            assertSafeLogs(provider.substringBefore('-'))
            assertTrue(logs().contains("http_code=400"))
        }
    }

    @Test
    fun `arbitrary model names cannot inject body keys or URLs into metadata`() = runBlocking {
        val unsafeModel = "gpt-4o\n$requestCanary Bearer $secretCanary https://private.example/path"
        val api = ChatCompletionsAPI(client(chatResponse()), KeyRoulette.default())
        api.generateText(openAI(), messages, params.copy(model = Model(modelId = unsafeModel)))
        assertFalse(logs().contains(unsafeModel))
        assertFalse(logs().contains(requestCanary))
        assertFalse(logs().contains(secretCanary))
        assertFalse(logs().contains("private.example"))
        assertTrue(logs().contains("model=sha256:"))
    }

    private fun client(body: String, code: Int = 200, sse: Boolean = false) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            capturedRequest = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Bad request")
                // Match OkHttp's actual network body: stringSafe() deliberately ignores
                // synthetic response bodies, so an ordinary toResponseBody() hides errors.
                .body(RealResponseBody(
                    if (sse) "text/event-stream" else "application/json",
                    body.toByteArray(Charsets.UTF_8).size.toLong(),
                    Buffer().writeUtf8(body),
                ))
                .build()
        }.build()

    private fun assertPrivateRequestAndLogs(provider: String) {
        val request = capturedRequest!!
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue("logging changes must preserve request contents", body.contains(requestCanary))
        assertTrue(body.contains(secretCanary))
        assertSafeLogs(provider)
    }

    private fun assertSafeLogs(provider: String, expectsModel: Boolean = true) {
        val logs = logs()
        assertFalse("request body leaked through Android logcat or stdout", logs.contains(requestCanary))
        assertFalse("response body leaked through Android logcat or stdout", logs.contains(responseCanary))
        assertFalse("private key leaked through Android logcat or stdout", logs.contains(secretCanary))
        assertTrue("provider metadata should remain", logs.contains("provider=$provider"))
        assertTrue("HTTP metadata should remain", logs.contains("http_code="))
        assertTrue("size metadata should remain", logs.contains("size_bytes="))
        if (expectsModel) assertTrue("ordinary model ID should remain", logs.contains("model=gpt-4o"))
        assertTrue(ShadowLog.getLogs().all { it.throwable == null })
    }

    private fun logs() = ShadowLog.getLogs().joinToString("\n") { it.msg } + output.toString(Charsets.UTF_8)
    private fun openAI() = ProviderSetting.OpenAI(baseUrl = "https://api.example/v1", apiKey = secretCanary)
    private fun google() = ProviderSetting.Google(baseUrl = "https://api.example/v1", apiKey = secretCanary)
    private fun claude() = ProviderSetting.Claude(baseUrl = "https://api.example/v1", apiKey = secretCanary)
    private fun chatResponse() = """{"id":"reply","model":"gpt-4o","choices":[{"message":{"content":"$responseCanary"},"finish_reason":"stop"}]}"""
    private fun responsesResponse() = """{"id":"reply","model":"gpt-4o","output":[{"type":"message","content":[{"type":"output_text","text":"$responseCanary"}]}]}"""
    private fun sse(data: String) = "data: $data\n\n"
}
