package me.rerere.rikkahub.data.ai.tools.local

import java.io.IOException
import java.net.ServerSocket
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HttpToolSecretSanitizer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import io.mockk.every
import io.mockk.mockk
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.AIRequestInterceptor

class HttpRequestToolTest {
    private fun response(chain: Interceptor.Chain, body: ResponseBody, status: Int = 200): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("OK")
            .addHeader("Set-Cookie", "server-private-cookie").addHeader("X-Result", "ok").body(body).build()

    // Loopback test servers stand in for public hosts; the private-address guard has its own test.
    private fun invoke(client: OkHttpClient, arguments: JsonObject): JsonObject = runBlocking {
        val output = httpRequestTool(client, isAllowedAddress = { true }).execute(arguments).filterIsInstance<UIMessagePart.Text>().single().text
        Json.parseToJsonElement(output).jsonObject
    }

    private fun args(method: String = "GET", extra: JsonObject = JsonObject(emptyMap())) =
        JsonObject(mapOf("url" to JsonPrimitive("https://example.test/api"), "method" to JsonPrimitive(method)) + extra)

    @Test fun `read methods need no approval and all mutating methods need approval`() {
        val tool = httpRequestTool(OkHttpClient())
        assertFalse(tool.needsApproval(args()))
        assertFalse(tool.needsApproval(buildJsonObject { put("url", "https://example.test") }))
        assertFalse(tool.needsApproval(args("HEAD")))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) assertTrue(method, tool.needsApproval(args(method)))
    }

    @Test fun `HTTP credentials bypass shared request loggers but preserve safe interceptors`() {
        val logger = mockk<RequestLoggingInterceptor>()
        val ai = mockk<AIRequestInterceptor>()
        every { logger.intercept(any()) } throws IOException("Логирование секретов запрещено")
        every { ai.intercept(any()) } throws IOException("Неверный перехватчик")
        var safeInterceptorRan = false
        val client = OkHttpClient.Builder().addInterceptor(logger).addInterceptor(ai)
            .addNetworkInterceptor(logger).addInterceptor { chain ->
                safeInterceptorRan = true
                response(chain, "ok".toResponseBody("text/plain".toMediaType()))
            }.build()
        assertEquals("200", invoke(client, args())["status"]?.jsonPrimitive?.content)
        assertTrue(safeInterceptorRan)
        assertTrue(client.interceptors.contains(logger))
    }

    @Test fun `all requested methods execute with original headers and JSON or string bodies`() {
        for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            var observedMethod: String? = null
            var observedBody: String? = null
            var observedSecret: String? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                observedMethod = chain.request().method
                observedSecret = chain.request().header("Authorization")
                observedBody = chain.request().body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }
                response(chain, "hello".toResponseBody("text/plain".toMediaType()))
            }.build()
            val extra = buildJsonObject {
                put("headers", buildJsonObject { put("Authorization", "Bearer private-secret") })
                if (method in listOf("POST", "PUT", "PATCH")) put("json", buildJsonObject { put("hello", "world") })
                if (method == "DELETE") put("body", "raw payload")
            }
            val result = invoke(client, args(method, extra))
            assertEquals("200", result["status"]?.jsonPrimitive?.content)
            assertEquals(method, observedMethod)
            assertEquals("Bearer private-secret", observedSecret)
            if (method in listOf("POST", "PUT", "PATCH")) assertEquals("""{"hello":"world"}""", observedBody)
            if (method == "DELETE") assertEquals("raw payload", observedBody)
            assertFalse(result.toString().contains("server-private-cookie"))
            if (method != "HEAD") assertEquals("hello", result["body"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `validation rejects unsupported URLs methods limits and masked restored credentials before network`() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain -> calls.incrementAndGet(); response(chain, "ok".toResponseBody()) }.build()
        val invalid = listOf(
            buildJsonObject { put("url", "ftp://example.test") },
            buildJsonObject { put("url", "https://user:pass@example.test") },
            buildJsonObject { put("method", "TRACE") },
            buildJsonObject { put("timeout_seconds", 0) }, buildJsonObject { put("timeout_seconds", 91) },
            buildJsonObject { put("timeout_seconds", "30") },
            buildJsonObject { put("max_bytes", 0) }, buildJsonObject { put("max_bytes", 8388609) },
            buildJsonObject { put("headers", buildJsonObject { put("Authorization", HttpToolSecretSanitizer.MASK) }) },
            buildJsonObject { put("headers", buildJsonObject { put("X-Token", 123) }) },
            buildJsonObject { put("body", 42) },
            buildJsonObject { put("method", "POST"); put("body", buildJsonObject { put("a", 1) }) },
            buildJsonObject { put("method", "POST"); put("json", "not an object") },
            buildJsonObject { put("method", "POST"); put("body", "raw"); put("json", buildJsonObject { put("a", 1) }) },
            buildJsonObject { put("method", "GET"); put("body", "raw") },
            buildJsonObject { put("method", "HEAD"); put("json", buildJsonObject { put("a", 1) }) },
        )
        for (extra in invalid) assertNotNull(extra.toString(), invoke(client, args(extra = extra))["error"])
        assertEquals(0, calls.get())
    }

    @Test fun `bounded unknown length stream reads only cap plus one and closes`() {
        var read = 0L
        var closed = false
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, 1000000L - read)
                if (count == 0L) return -1
                sink.write(ByteArray(count.toInt()) { 'x'.code.toByte() }); read += count; return count
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain, body) }.build()
        val result = invoke(client, args(extra = buildJsonObject { put("max_bytes", 16) }))
        assertEquals("xxxxxxxxxxxxxxxx", result["body"]?.jsonPrimitive?.content)
        assertEquals("true", result["truncated"]?.jsonPrimitive?.content)
        // BufferedSource can fill one segment but must not consume the unbounded payload.
        assertTrue(read <= 8192)
        assertTrue(closed)
    }

    @Test fun `binary data is base64 and exact cap is not truncated`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, byteArrayOf(0, 1, 2, -1).toResponseBody("application/octet-stream".toMediaType()))
        }.build()
        val result = invoke(client, args(extra = buildJsonObject { put("max_bytes", 4) }))
        assertEquals("base64", result["encoding"]?.jsonPrimitive?.content)
        assertEquals("AAEC/w==", result["body"]?.jsonPrimitive?.content)
        assertEquals("false", result["truncated"]?.jsonPrimitive?.content)
    }

    @Test fun `default response cap is one MiB and call timeout defaults to thirty seconds`() {
        var timeoutSeconds = 0L
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            timeoutSeconds = TimeUnit.NANOSECONDS.toSeconds(chain.call().timeout().timeoutNanos())
            response(chain, ByteArray(1048577) { 'x'.code.toByte() }.toResponseBody("text/plain".toMediaType()))
        }.build()
        val result = invoke(client, args())
        assertEquals(30L, timeoutSeconds)
        assertEquals(1048576, result.getValue("body").jsonPrimitive.content.length)
        assertEquals("true", result.getValue("truncated").jsonPrimitive.content)
    }

    @Test fun `response echoes and exception details never expose supplied secrets`() {
        val extra = buildJsonObject { put("headers", buildJsonObject { put("X-Api-Key", "private-secret") }) }
        val echo = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, "echo private-secret".toResponseBody("text/plain".toMediaType())).newBuilder()
                .addHeader("X-Echo", "private-secret").addHeader("X-Token", "another-secret").build()
        }.build()
        val output = invoke(echo, args(extra = extra)).toString()
        assertFalse(output.contains("private-secret")); assertFalse(output.contains("another-secret"))
        val failure = OkHttpClient.Builder().addInterceptor { throw IOException("Authorization: private-secret") }.build()
        assertFalse(invoke(failure, args(extra = extra)).toString().contains("private-secret"))
        val binary = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, "private-secret".toByteArray().toResponseBody("application/octet-stream".toMediaType()))
        }.build()
        val encoded = invoke(binary, args(extra = extra)).getValue("body").jsonPrimitive.content
        assertFalse(java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8).contains("private-secret"))
    }

    @Test fun `redirects do not forward supplied credentials`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            calls.incrementAndGet(); exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/target") { exchange ->
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        server.start()
        try {
            val result = invoke(OkHttpClient(), args(extra = buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/redirect")
                put("headers", buildJsonObject { put("Authorization", "private-secret") })
            }))
            assertEquals("302", result["status"]?.jsonPrimitive?.content)
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun `POST service unavailable with immediate retry never repeats the mutation`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val first = calls.incrementAndGet() == 1
            if (first) exchange.responseHeaders.add("Retry-After", "0")
            val body = (if (first) "temporary" else "unexpected repeat").toByteArray()
            exchange.sendResponseHeaders(if (first) 503 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val result = invoke(OkHttpClient(), args("POST", buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/")
                put("body", "create once")
            }))
            assertEquals("503", result["status"]?.jsonPrimitive?.content)
            assertEquals(1, calls.get())
            val headers = result.getValue("headers").jsonObject
            assertEquals("[\"0\"]", headers.entries.single { it.key.equals("Retry-After", true) }.value.toString())
            assertEquals("temporary", result["body"]?.jsonPrimitive?.content)
        } finally { server.stop(0) }
    }

    @Test fun `cancelling during body read closes the connection`() = runBlocking {
        ServerSocket(0).use { server ->
            val bodyStarted = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 1000\r\n\r\nx".toByteArray())
                    socket.getOutputStream().flush(); bodyStarted.countDown()
                    try { if (socket.getInputStream().read() == -1) disconnected.countDown() } catch (_: IOException) { disconnected.countDown() }
                }
            }
            val task = launch(Dispatchers.Default) {
                httpRequestTool(OkHttpClient(), isAllowedAddress = { true })
                    .execute(buildJsonObject { put("url", "http://127.0.0.1:${server.localPort}") })
            }
            assertTrue(bodyStarted.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            worker.join(1000)
        }
    }

    @Test fun `phone and home network addresses are refused before any request`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            for (host in listOf("127.0.0.1", "localhost")) {
                val output = runBlocking {
                    httpRequestTool(OkHttpClient()).execute(buildJsonObject { put("url", "http://$host:${server.address.port}/") })
                        .filterIsInstance<UIMessagePart.Text>().single().text
                }
                val result = Json.parseToJsonElement(output).jsonObject
                assertTrue(output, result["error"]?.jsonPrimitive?.content.orEmpty().contains("локальной сети"))
            }
            assertEquals(0, calls.get())
        } finally { server.stop(0) }
    }
}
