package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.applyMcpDraft
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** NEW API: beforeConfig parameter; exclude from baseline production RED compilation. */
class McpOriginChangeSettingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `saving a changed origin clears prior OAuth and secret references and preserves enabled`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val before = protectMcpSettingsSecrets(McpServerConfig.StreamableHTTPServer(url = "https://before.example.org/mcp",
            commonOptions = McpCommonOptions(enable = false, headers = listOf("Authorization" to "old-header"),
                oauth = McpOAuthState(enabled = true, accessToken = "old-oauth"))), emptyMap(), vault) as McpServerConfig.StreamableHTTPServer
        val saved = protectMcpSettingsSecrets(before.copy(url = "https://after.example.org/mcp"), emptyMap(), vault, before)
        assertNull(saved.commonOptions.oauth)
        assertTrue(saved.commonOptions.headers.isEmpty())
        assertFalse(saved.commonOptions.enable)
        assertEquals("https://after.example.org/mcp", (saved as McpServerConfig.StreamableHTTPServer).url)
    }

    @Test fun `fresh secret draft entered for the new origin is saved with the new owner`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val before = protectMcpSettingsSecrets(McpServerConfig.StreamableHTTPServer(url = "https://before.example.org/mcp",
            commonOptions = McpCommonOptions(headers = listOf("Authorization" to "old-header"))), emptyMap(), vault) as McpServerConfig.StreamableHTTPServer
        val saved = protectMcpSettingsSecrets(before.copy(url = "https://after.example.org/mcp"), mapOf(0 to "fresh-header"), vault, before)
        val submitted = protectMcpSettingsSecrets(saved, emptyMap(), vault, before)
        val merged = protectMcpSettingsSecrets(applyMcpDraft(before, before, submitted), emptyMap(), vault, before)
        val reference = merged.commonOptions.headers.single().second
        assertNull(merged.commonOptions.oauth)
        assertEquals("fresh-header", vault.resolve(reference, saved.id, "https://after.example.org/mcp"))
        assertTrue(runCatching { vault.resolve(reference, saved.id, before.url) }.isFailure)
    }
}
