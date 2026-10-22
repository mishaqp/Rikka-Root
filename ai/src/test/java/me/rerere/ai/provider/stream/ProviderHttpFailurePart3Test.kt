package me.rerere.ai.provider.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProviderHttpFailurePart3Test {
    @Test fun responsesEmptyHttpErrorIsNotSuccessful() = failure("responses", 401, "")
    @Test fun responsesHtmlHttpErrorRetainsStatus() = failure("responses", 502, "<html>gateway unavailable</html>")
    @Test fun claudeEmptyHttpErrorIsNotSuccessful() = failure("claude", 401, "")
    @Test fun claudeHtmlHttpErrorRetainsStatus() = failure("claude", 502, "<html>gateway unavailable</html>")
    @Test fun responsesJsonHttpErrorStillFails() = failure("responses", 429, """{"error":{"message":"Превышена квота"}}""", "Превышена квота")
    @Test fun claudeJsonHttpErrorStillFails() = failure("claude", 429, """{"error":{"message":"Превышена квота"}}""", "Превышена квота")

    private fun failure(kind: String, status: Int, body: String, expected: String = status.toString()) = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Failure")
                .body(RealResponseBody("text/html", body.toByteArray(Charsets.UTF_8).size.toLong(), Buffer().writeUtf8(body))).build()
        }.build()
        try {
            val messages = listOf(UIMessage.user("Проверка"))
            val params = TextGenerationParams(model = Model(modelId = "test"))
            val flow: Flow<StreamChunk> = when (kind) {
                "responses" -> OpenAIProvider(client).streamText(ProviderSetting.OpenAI(apiKey = "test", useResponseApi = true), messages, params)
                else -> ClaudeProvider(client).streamText(ProviderSetting.Claude(apiKey = "test"), messages, params)
            }
            val failure = runCatching { withTimeout(5_000) { flow.toList() } }.exceptionOrNull()
            assertNotNull("HTTP $status must fail, not close the response successfully", failure)
            assertTrue(failure.toString(), failure!!.message.orEmpty().contains(expected))
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
