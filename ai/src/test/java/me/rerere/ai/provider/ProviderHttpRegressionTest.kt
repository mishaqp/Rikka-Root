package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Неуспешные HTTP-ответы потоков и сборка URL с базовым адресом, оканчивающимся на `/`. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProviderHttpRegressionTest {
    private val params = TextGenerationParams(model = Model(modelId = "gpt-4o"))
    private val messages = listOf(UIMessage.user("hello"))
    private val providers = listOf("openai-chat", "openai-responses", "google", "google-interactions", "claude")
    private val requests = mutableListOf<Request>()

    @Test
    fun `stream with non-2xx status and empty body fails with the HTTP code`() = runBlocking {
        for (provider in providers) {
            val failure = runCatching {
                withTimeout(5_000) { stream(provider, client("", code = 500)).toList() }
            }.exceptionOrNull()
            assertTrue("$provider must not finish as success", failure != null)
            assertEquals("$provider message", "HTTP 500", failure!!.message)
        }
    }

    @Test
    fun `stream with non-2xx status and non-JSON body reports code and body, not a parser error`() = runBlocking {
        for (provider in providers) {
            val failure = runCatching {
                withTimeout(5_000) {
                    stream(provider, client("<html>Bad gateway</html>", code = 502, contentType = "text/html")).toList()
                }
            }.exceptionOrNull()
            assertTrue("$provider must not finish as success", failure != null)
            assertFalse("$provider leaked parser error", failure is SerializationException)
            assertEquals("$provider message", "HTTP 502: <html>Bad gateway</html>", failure!!.message)
        }
    }

    @Test
    fun `stream with JSON error body keeps the API message`() = runBlocking {
        for (provider in providers) {
            val failure = runCatching {
                withTimeout(5_000) {
                    stream(provider, client("""{"error":{"message":"quota exceeded"}}""", code = 429)).toList()
                }
            }.exceptionOrNull()
            assertEquals("$provider message", "quota exceeded", failure?.message)
        }
    }

    @Test
    fun `base URL with trailing slash never produces a double slash`() = runBlocking {
        val sse = client("data: [DONE]\n\n", contentType = "text/event-stream")
        for (provider in providers) {
            requests.clear()
            runCatching { withTimeout(5_000) { stream(provider, sse, baseUrl = "https://api.example/v1/").toList() } }
            val url = requests.single().url.toString()
            assertFalse("$provider built $url", url.substringAfter("://").contains("//"))
            assertTrue("$provider built $url", url.startsWith("https://api.example/v1/"))
        }

        requests.clear()
        val models = client("""{"data":[]}""")
        OpenAIProvider(models).listModels(openAI("https://api.example/v1/"))
        ClaudeProvider(models).listModels(claude("https://api.example/v1/"))
        assertEquals(
            listOf("https://api.example/v1/models", "https://api.example/v1/models"),
            requests.map { it.url.toString() },
        )
    }

    private suspend fun stream(
        provider: String,
        http: OkHttpClient,
        baseUrl: String = "https://api.example/v1",
    ): Flow<StreamChunk> = when (provider) {
        "openai-chat" -> ChatCompletionsAPI(http, KeyRoulette.default()).streamText(openAI(baseUrl), messages, params)
        "openai-responses" -> ResponseAPI(http).streamText(openAI(baseUrl), messages, params)
        "google" -> GoogleProvider(http).streamText(google(baseUrl), messages, params)
        "google-interactions" -> GoogleProvider(http)
            .streamText(google(baseUrl).copy(useInteractionsApi = true), messages, params)
        else -> ClaudeProvider(http).streamText(claude(baseUrl), messages, params)
    }

    private fun client(body: String, code: Int = 200, contentType: String = "application/json") = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status")
                // stringSafe() читает только настоящее сетевое тело, как в ProviderLoggingTest.
                .body(RealResponseBody(contentType, body.toByteArray(Charsets.UTF_8).size.toLong(), Buffer().writeUtf8(body)))
                .build()
        }.build()

    private fun openAI(baseUrl: String = "https://api.example/v1") = ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "key")
    private fun google(baseUrl: String) = ProviderSetting.Google(baseUrl = baseUrl, apiKey = "key")
    private fun claude(baseUrl: String) = ProviderSetting.Claude(baseUrl = baseUrl, apiKey = "key")
}
