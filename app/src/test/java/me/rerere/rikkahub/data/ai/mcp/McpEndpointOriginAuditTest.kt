package me.rerere.rikkahub.data.ai.mcp

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.takeFrom
import io.mockk.mockk
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** Runs the production requestBuilder callback used by SSE performSend, without a network. */
class McpEndpointOriginAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `server supplied endpoint cannot receive credentials on another host scheme or port`() {
        for (url in listOf("https://other.example.org/post", "http://example.org/post", "https://example.org:444/post")) {
            val (registry, config) = fixture()
            val request = HttpRequestBuilder().apply { this.url.takeFrom(url) }
            val outcome = runCatching { appendHeaders(registry, request, config) }
            assertTrue("Cross-origin endpoint must fail before credentials are attached: $url", outcome.isFailure)
            assertNull(request.headers["X-Auth"])
            assertNull(request.headers["Authorization"])
        }
    }

    @Test fun `incomplete legacy config cannot transmit ownerless migrated credentials`() {
        val (registry, configured) = fixture()
        val incomplete = (configured as McpServerConfig.SseTransportServer).copy(url = "")
        val request = HttpRequestBuilder().apply { url.takeFrom("https://example.org/post") }
        assertTrue(runCatching { appendHeaders(registry, request, incomplete) }.isFailure)
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["X-Auth"])
    }

    @Test fun `same origin SSE post endpoint retains session query and credentials`() {
        val (registry, config) = fixture()
        val request = HttpRequestBuilder().apply { url.takeFrom("https://EXAMPLE.org:443/post?sessionId=abc") }
        appendHeaders(registry, request, config)
        assertEquals("header-canary", request.headers["X-Auth"])
        assertEquals("Bearer oauth-canary", request.headers["Authorization"])
        assertEquals("abc", request.url.parameters["sessionId"])
    }

    private fun fixture(): Pair<McpSessionRegistry, McpServerConfig> {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val config = McpServerConfig.SseTransportServer(url = "https://example.org/sse",
            commonOptions = McpCommonOptions(headers = listOf("X-Auth" to vault.put("header-canary")),
                oauth = McpOAuthState(enabled = true, accessToken = vault.put("oauth-canary"))))
        return McpSessionRegistry(mockk(), mockk(), mockk(), mockk(), vault, mockk(), McpStatusStore()) to config
    }

    private fun appendHeaders(registry: McpSessionRegistry, request: HttpRequestBuilder, config: McpServerConfig) {
        val callback = McpSessionRegistry::class.java.getDeclaredMethod(
            "appendResolvedHeaders", HttpRequestBuilder::class.java, McpServerConfig::class.java,
        ).apply { isAccessible = true }
        callback.invoke(registry, request, config)
    }
}
