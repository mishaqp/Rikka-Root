package me.rerere.rikkahub.data.ai.mcp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses the actual private manual clients built by McpManager, including Ktor's redirect plugin. */
class McpManualRedirectAuditTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `manual OkHttp client does not forward custom credentials through a redirect`() = runBlocking {
        assertRedirectBlocked(useKtor = false)
    }

    @Test fun `manual Ktor client does not forward custom credentials through a redirect`() = runBlocking {
        assertRedirectBlocked(useKtor = true)
    }

    private suspend fun assertRedirectBlocked(useKtor: Boolean) {
        val first = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val second = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sourceCredential = AtomicReference<String>()
        val redirectedRequests = AtomicInteger()
        second.createContext("/") { exchange ->
            redirectedRequests.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        first.createContext("/") { exchange ->
            sourceCredential.set(exchange.requestHeaders.getFirst("X-Auth"))
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${second.address.port}/foreign")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        first.start()
        second.start()
        val cancelledJob = SupervisorJob().apply { cancel() }
        val appScope = mockk<AppScope> { every { coroutineContext } returns (cancelledJob + Dispatchers.Unconfined) }
        var createdManager: McpManager? = null
        val url = "http://127.0.0.1:${first.address.port}/mcp"
        try {
            val manager = McpManager(mockk(), appScope, mockk(), McpControlSecretStore(temporary.newFolder()) {
                error("Redirect test must not access Keystore")
            }).also { createdManager = it }
            val status = if (useKtor) {
                field<HttpClient>(manager, "httpClient").get(url) { headers.append("X-Auth", "origin-only-canary") }.status.value
            } else {
                field<OkHttpClient>(manager, "okHttpClient").newCall(Request.Builder().url(url)
                    .header("X-Auth", "origin-only-canary").build()).execute().use { it.code }
            }
            assertEquals("Manual private hosts must remain reachable", "origin-only-canary", sourceCredential.get())
            assertEquals("A redirect must remain a response requiring a separate explicit endpoint change", 302, status)
            assertEquals("The second origin must never receive the redirected request or its headers", 0, redirectedRequests.get())
        } finally {
            try {
                createdManager?.let { manager ->
                    field<HttpClient>(manager, "httpClient").close()
                    field<HttpClient>(manager, "publicHttpClient").close()
                    for (name in listOf("okHttpClient", "publicOkHttpClient")) {
                        field<OkHttpClient>(manager, name).apply { connectionPool.evictAll(); dispatcher.executorService.shutdownNow() }
                    }
                }
            } finally {
                first.stop(0)
                second.stop(0)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(manager: McpManager, name: String): T = McpManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(manager) as T
}
