package me.rerere.rikkahub.ui.pages.setting

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpSettingsSecretsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(): McpControlSecretStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return McpControlSecretStore(temporary.newFolder()) { key }
    }
    @Test fun `settings edit saves draft and legacy headers and OAuth only as vault references`() {
        val vault = store()
        val oldReference = vault.put("unchanged-secret")
        val config = McpServerConfig.StreamableHTTPServer(url = "https://example.org/mcp",
            commonOptions = McpCommonOptions(name = "test", publicAddressOnly = true,
                headers = listOf("Authorization" to oldReference, "X-Api-Key" to "legacy-key-canary", "X-New" to ""),
                oauth = McpOAuthState(accessToken = "oauth-access-canary")))
        val protected = protectMcpSettingsSecrets(config, mapOf(2 to "draft-key-canary"), vault)
        assertEquals(config.id, protected.id)
        assertTrue(protected.commonOptions.publicAddressOnly)
        assertEquals(oldReference, protected.commonOptions.headers[0].second)
        assertEquals("legacy-key-canary", vault.resolve(protected.commonOptions.headers[1].second))
        assertEquals("draft-key-canary", vault.resolve(protected.commonOptions.headers[2].second))
        assertEquals("", config.commonOptions.headers[2].second)
        assertEquals("oauth-access-canary", vault.resolve(protected.commonOptions.oauth!!.accessToken!!))
        val serialized = Json.encodeToString<McpServerConfig>(protected)
        for (secret in listOf("legacy-key-canary", "draft-key-canary", "oauth-access-canary", "unchanged-secret"))
            assertFalse(serialized.contains(secret))
        assertEquals(protected, protectMcpSettingsSecrets(protected, emptyMap(), vault))
    }
    @Test fun `manual configs keep their transport permissions and can clear a stored secret`() {
        val vault = store()
        val config = McpServerConfig.SseTransportServer(url = "http://127.0.0.1:3000/sse",
            commonOptions = McpCommonOptions(headers = listOf("Authorization" to vault.put("previous-token"))))
        val protected = protectMcpSettingsSecrets(config, mapOf(0 to ""), vault)
        assertFalse(protected.commonOptions.publicAddressOnly)
        assertEquals("", protected.commonOptions.headers.single().second)
        assertEquals(config.url, (protected as McpServerConfig.SseTransportServer).url)
    }
}
