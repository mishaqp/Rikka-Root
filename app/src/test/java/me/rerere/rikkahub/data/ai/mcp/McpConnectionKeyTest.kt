package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import kotlin.uuid.Uuid

class McpConnectionKeyTest {
    private val base = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo"),
        url = "https://example.com/mcp",
    )

    @Test
    fun `tool metadata does not affect connection key`() {
        val withTools = base.copy(
            commonOptions = base.commonOptions.copy(
                tools = listOf(McpTool(name = "search", enable = false))
            )
        )

        assertEquals(base.connectionKey(), withTools.connectionKey())
    }

    @Test
    fun `url transport and headers affect connection key`() {
        assertNotEquals(base.connectionKey(), base.copy(url = "https://example.com/other").connectionKey())
        assertNotEquals(
            base.connectionKey(),
            McpServerConfig.SseTransportServer(
                id = base.id,
                commonOptions = base.commonOptions,
                url = base.url,
            ).connectionKey()
        )
        assertNotEquals(
            base.connectionKey(),
            base.copy(
                commonOptions = base.commonOptions.copy(headers = listOf("X-API-Key" to "secret"))
            ).connectionKey()
        )
    }

    @Test
    fun `oauth token affects connection key unless manual authorization header wins`() {
        val oauth = McpOAuthState(enabled = true, accessToken = "oauth-token")
        val withOAuth = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))
        assertNotEquals(base.connectionKey(), withOAuth.connectionKey())

        val manualAuth = base.copy(
            commonOptions = base.commonOptions.copy(
                headers = listOf("Authorization" to "Bearer manual"),
                oauth = oauth,
            )
        )
        val manualAuthWithoutOAuth = manualAuth.copy(
            commonOptions = manualAuth.commonOptions.copy(oauth = null)
        )
        assertEquals(manualAuthWithoutOAuth.connectionKey(), manualAuth.connectionKey())
    }

    @Test fun `guard changes require reconnect and connection metadata keeps references`() {
        val reference = McpControlSecretStore.REFERENCE_PREFIX + Uuid.random().toString()
        val configured = base.copy(commonOptions = base.commonOptions.copy(
            publicAddressOnly = true,
            headers = listOf("X-Api-Key" to reference),
            oauth = McpOAuthState(enabled = true, accessToken = reference),
        ))
        assertTrue(configured.connectionKey().publicAddressOnly)
        assertNotEquals(configured.connectionKey(), configured.copy(commonOptions = configured.commonOptions.copy(publicAddressOnly = false)).connectionKey())
        val realHeaders = configured.resolvedHeaders { assertEquals(reference, it); "secret-canary" }
        assertEquals("secret-canary", realHeaders.first { it.first == "X-Api-Key" }.second)
        assertEquals("Bearer secret-canary", realHeaders.first { it.first == "Authorization" }.second)
        assertFalse(configured.connectionKey().toString().contains("secret-canary"))
        assertTrue(configured.connectionKey().toString().contains(reference))
    }
}
