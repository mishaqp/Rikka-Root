package me.rerere.ai.provider.stream

import java.io.Closeable
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProviderBodyCancellationPart3Test(private val operation: String) {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cancellationAfterHeadersCancelsBodyCallBeforeBodyIsReleased() = runBlocking {
        GatedHttpServer(null).use { server ->
            val reading = CountDownLatch(1)
            val activeCall = AtomicReference<Call>()
            val client = client(server, object : EventListener() {
                override fun responseBodyStart(call: Call) { activeCall.set(call); reading.countDown() }
            })
            val operationFailure = AtomicReference<Throwable>()
            val job = launch(Dispatchers.IO) {
                // A blocked read can throw EOF after the cleanup gate is released. Keep that
                // cancelled-child exception from replacing the assertion that proves the bug.
                runCatching { runOperation(client, server.url) }.exceptionOrNull()?.let(operationFailure::set)
            }
            try {
                assertTrue("$operation did not start reading its real HTTP body", reading.await(5, TimeUnit.SECONDS))
                assertNull("$operation failed before cancellation", operationFailure.get())
                job.cancel()
                withTimeoutOrNull(1_500) { while (activeCall.get()?.isCanceled() != true) delay(5) }
                assertTrue("$operation cancellation must own the Call while body.string/bytes is blocked", activeCall.get()?.isCanceled() == true)
            } finally {
                server.release.countDown()
                withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun normalBodyStillReturnsWithoutCancellingSuccessfulCall() = runBlocking {
        GatedHttpServer(successBody()).use { server ->
            val completed = AtomicReference<Call>()
            val client = client(server, object : EventListener() { override fun callEnd(call: Call) { completed.set(call) } })
            try {
                withTimeout(5_000) { withContext(Dispatchers.IO) { runOperation(client, server.url) } }
                assertNotNull("$operation must consume and close the real response", completed.get())
                assertFalse(completed.get().isCanceled())
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    private fun client(server: GatedHttpServer, listener: EventListener): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .eventListener(listener)
        .addInterceptor { chain ->
            if (operation == "imageDownload" && chain.request().url.encodedPath.endsWith("/images/generations")) {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"data":[{"url":"${server.url}/image"}]}""".toResponseBody("application/json".toMediaType())).build()
            } else chain.proceed(chain.request().newBuilder().url(server.url).build())
        }.build()

    private suspend fun runOperation(client: OkHttpClient, baseUrl: String) {
        val model = Model(modelId = "test")
        val params = TextGenerationParams(model)
        val messages = listOf(UIMessage.user("Проверка"))
        val setting = ProviderSetting.OpenAI(baseUrl = baseUrl, apiKey = "fixture")
        val openai = OpenAIProvider(client)
        when (operation) {
            "chat" -> openai.generateText(setting, messages, params)
            "responses" -> openai.generateText(setting.copy(useResponseApi = true), messages, params)
            "claude" -> ClaudeProvider(client).generateText(ProviderSetting.Claude(baseUrl = baseUrl, apiKey = "fixture"), messages, params)
            "gemini", "interactions" -> GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = baseUrl, apiKey = "fixture", useInteractionsApi = operation == "interactions"), messages, params)
            "openaiModels" -> openai.listModels(setting)
            "claudeModels" -> ClaudeProvider(client).listModels(ProviderSetting.Claude(baseUrl = baseUrl, apiKey = "fixture"))
            "geminiModels" -> GoogleProvider(client).listModels(ProviderSetting.Google(baseUrl = baseUrl, apiKey = "fixture"))
            "balance" -> openai.getBalance(setting)
            "embedding" -> openai.generateEmbedding(setting, EmbeddingGenerationParams(model, listOf("Проверка")))
            "imageGeneration", "imageDownload" -> openai.generateImage(setting, ImageGenerationParams(model, "Проверка")).toList()
            "imageEdit", "openrouterEdit" -> {
                val file = folder.newFile("image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val target = if (operation == "openrouterEdit") setting.copy(baseUrl = "https://openrouter.ai/api/v1") else setting
                openai.editImage(target, ImageEditParams(model, "Проверка", listOf(file.absolutePath))).toList()
            }
            "vertexToken" -> {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(pair.private.encoded)}\n-----END PRIVATE KEY-----"
                ServiceAccountTokenProvider(client).fetchAccessToken("fixture@example.test", pem)
            }
            else -> error(operation)
        }
    }

    private fun successBody(): String = when (operation) {
        "chat" -> """{"id":"r","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Готово"}}]}"""
        "responses" -> """{"id":"r","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Готово"}]}]}"""
        "claude" -> """{"id":"r","content":[{"type":"text","text":"Готово"}],"stop_reason":"end_turn"}"""
        "gemini" -> """{"candidates":[{"content":{"parts":[{"text":"Готово"}]},"finishReason":"STOP"}]}"""
        "interactions" -> """{"id":"r","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Готово"}]}]}"""
        "openaiModels", "claudeModels" -> """{"data":[{"id":"test","display_name":"Тест"}]}"""
        "geminiModels" -> """{"models":[{"name":"models/test","displayName":"Тест","supportedGenerationMethods":["generateContent"]}]}"""
        "balance" -> """{"data":{"total_usage":12}}"""
        "embedding" -> """{"model":"test","data":[{"embedding":[1.0,2.0]}]}"""
        "vertexToken" -> """{"access_token":"fixture-token","expires_in":3600}"""
        "imageDownload" -> "image-bytes"
        else -> """{"data":[{"b64_json":"aGVsbG8="}]}"""
    }

    /** Real headers/body lifecycle; the gate is always released by finally on a failed assertion. */
    private class GatedHttpServer(private val body: String?) : Closeable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}"
        val release = CountDownLatch(1)
        private val worker = thread(isDaemon = true, name = "provider-body-fixture") {
            runCatching {
                socket.accept().use { connection ->
                    val input = connection.getInputStream().bufferedReader()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                    val bytes = (body ?: "{").toByteArray()
                    val length = if (body == null) 65_536 else bytes.size
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes); output.flush()
                    if (body == null) release.await(10, TimeUnit.SECONDS)
                }
            }
        }
        override fun close() { release.countDown(); socket.close(); worker.join(2_000) }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun operations(): List<Array<String>> = listOf(
            "chat", "responses", "claude", "gemini", "interactions", "openaiModels", "claudeModels", "geminiModels",
            "balance", "embedding", "imageGeneration", "imageDownload", "imageEdit", "openrouterEdit", "vertexToken",
        ).map { arrayOf(it) }
    }
}
