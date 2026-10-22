package me.rerere.ai.provider.stream

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.*
import me.rerere.ai.util.json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RemoteImageRequestPart3Test(private val kind: String) {
    @Test fun remoteUserImageIsDownloadedWithoutProviderCredentialsAndEncoded() = runBlocking {
        fixture().use { f ->
            f.generate(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))))
            assertEquals(1, f.downloads.get())
            assertEquals(1, f.modelRequests.get())
            assertImage(f.wire!!)
        }
    }
    @Test fun remoteToolImageUsesSameBoundedTransport() = runBlocking {
        fixture().use { f ->
            f.generate(listOf(UIMessage.user("Проверка"), UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("call", "read_image", "{}", output = listOf(image))))))
            assertEquals(1, f.downloads.get())
            assertImage(f.wire!!)
        }
    }
    @Test fun streamingRequestResolvesRemoteImageBeforeOpeningProviderStream() = runBlocking {
        fixture().use { f ->
            f.generate(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))), stream = true)
            assertEquals(1, f.downloads.get())
            assertEquals(1, f.modelRequests.get())
            assertImage(f.wire!!)
        }
    }
    @Test fun inlineImageKeepsOriginalBytesWithoutNetworkDownload() = runBlocking {
        fixture().use { f ->
            f.generate(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image.copy(url = "data:image/png;base64,$PNG")))))
            assertEquals(0, f.downloads.get())
            assertImage(f.wire!!)
        }
    }
    @Test fun imageDownloadHttpFailureDoesNotSilentlySendCorruptOrMissingImage() = runBlocking {
        fixture(imageStatus = 404).use { f ->
            val failure = runCatching { f.generate(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image)))) }.exceptionOrNull()
            assertNotNull("A missing attachment must be an actionable error", failure)
            assertEquals(0, f.modelRequests.get())
            assertTrue(failure.toString(), failure!!.message.orEmpty().contains("404"))
            assertTrue(failure.toString(), Regex("[А-Яа-я]").containsMatchIn(failure.message.orEmpty()))
        }
    }
    @Test fun oversizedDownloadIsRejectedBeforeProviderRequestAndBodyIsClosed() = runBlocking {
        val body = HugeImageBody()
        fixture(largeBody = body).use { f ->
            val failure = runCatching { f.generate(listOf(UIMessage(role = MessageRole.USER, parts = listOf(image)))) }.exceptionOrNull()
            assertNotNull("40MiB raw image cannot fit either documented inline payload contract", failure)
            assertEquals(0, f.modelRequests.get())
            assertTrue("oversized attachment response must be closed", body.closed)
            assertTrue("a bounded downloader must stop before consuming the entire body", body.read < body.contentLength())
        }
    }

    private val image = UIMessagePart.Image("https://images.example.test/photo.png",
        metadata = GoogleThoughtMetadata(thoughtSignature = "image-signature").toMetadata())

    private fun assertImage(value: JsonObject) {
        val allObjects = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> { allObjects += node; node.values.forEach(::walk) }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(value)
        val encoded = if (kind == "claude") allObjects.single { it["type"]?.jsonPrimitive?.contentOrNull == "base64" }
            else allObjects.single { it["inlineData"] != null }["inlineData"]!!.jsonObject
        val mimeKey = if (kind == "claude") "media_type" else "mimeType"
        assertEquals("image/png", encoded[mimeKey]!!.jsonPrimitive.content)
        assertEquals(PNG, encoded["data"]!!.jsonPrimitive.content)
        if (kind == "gemini") assertEquals("image-signature",
            allObjects.single { it["inlineData"] != null }["thoughtSignature"]!!.jsonPrimitive.content)
    }

    private fun fixture(imageStatus: Int = 200, largeBody: HugeImageBody? = null) = Fixture(imageStatus, largeBody)
    private inner class Fixture(imageStatus: Int, largeBody: HugeImageBody?) : java.io.Closeable {
        val downloads = AtomicInteger()
        val modelRequests = AtomicInteger()
        var wire: JsonObject? = null
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host == "images.example.test") {
                downloads.incrementAndGet()
                assertNull(request.header("Authorization"))
                assertNull(request.header("x-api-key"))
                assertNull(request.header("x-goog-api-key"))
                assertNull(request.header("X-Provider-Secret"))
                assertFalse(request.headers.toString(), request.headers.toString().contains("provider-key"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(imageStatus).message("Image")
                    .body(largeBody ?: Base64.getDecoder().decode(PNG).toResponseBody("image/png".toMediaType())).build()
            } else {
                modelRequests.incrementAndGet()
                wire = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                val stream = wire!!["stream"]?.jsonPrimitive?.booleanOrNull == true || request.url.queryParameter("alt") == "sse"
                val body = when {
                    kind == "claude" && stream -> "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                    stream -> "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n"
                    kind == "claude" -> """{"id":"r","content":[{"type":"text","text":"Готово"}],"stop_reason":"end_turn"}"""
                    else -> """{"candidates":[{"content":{"parts":[{"text":"Готово"}]},"finishReason":"STOP"}]}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
            }
        }.build()
        suspend fun generate(messages: List<UIMessage>, stream: Boolean = false) {
            val params = TextGenerationParams(Model(modelId = "test", inputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
                customHeaders = listOf(CustomHeader("X-Provider-Secret", "provider-key")))
            withTimeout(5_000) {
                if (kind == "claude") {
                    val provider = ClaudeProvider(client)
                    val settings = ProviderSetting.Claude(apiKey = "provider-key")
                    if (stream) provider.streamText(settings, messages, params).toList() else provider.generateText(settings, messages, params)
                } else {
                    val provider = GoogleProvider(client)
                    val settings = ProviderSetting.Google(apiKey = "provider-key")
                    if (stream) provider.streamText(settings, messages, params).toList() else provider.generateText(settings, messages, params)
                }
            }
        }
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    private class HugeImageBody : ResponseBody() {
        var closed = false
        var read = 0L
        override fun contentType() = "image/png".toMediaType()
        override fun contentLength() = 40L * 1024 * 1024
        private val source: BufferedSource = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (read >= contentLength()) return -1
                val count = minOf(byteCount, 8192L, contentLength() - read).toInt()
                sink.write(ByteArray(count)); read += count
                return count.toLong()
            }
            override fun close() { closed = true }
        }.buffer()
        override fun source() = source
    }
    companion object {
        private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/K0AAAAASUVORK5CYII="
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun providers() = listOf(arrayOf("claude"), arrayOf("gemini"))
    }
}
