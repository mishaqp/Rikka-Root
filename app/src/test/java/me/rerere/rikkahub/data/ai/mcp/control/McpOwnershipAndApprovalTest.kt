package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid

/** New scoped API checks: exclude this class when replaying the baseline production RED. */
class McpOwnershipAndApprovalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `owner binding survives reload and rejects different id host scheme and port`() {
        val directory = temporary.newFolder()
        val owner = Uuid.random()
        val reference = McpControlSecretStore(directory) { key }.put("owned-secret", owner, "https://example.org/mcp")
        val reloaded = McpControlSecretStore(directory) { key }
        assertEquals("owned-secret", reloaded.resolve(reference, owner, "https://EXAMPLE.org:443/another-path"))
        for ((id, url) in listOf(
            Uuid.random() to "https://example.org/mcp",
            owner to "https://other.example.org/mcp",
            owner to "http://example.org/mcp",
            owner to "https://example.org:444/mcp",
        )) assertTrue(runCatching { reloaded.resolve(reference, id, url) }.isFailure)
        assertFalse(directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1).contains("owned-secret"))
    }

    @Test fun `model scoped resolution rejects unclaimed legacy references`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        val reference = vault.put("legacy-secret")
        val id = Uuid.random()
        assertTrue(runCatching { vault.resolve(reference, id, "https://example.org/mcp") }.isFailure)
        assertEquals(reference, vault.bind(reference, id, "https://example.org/mcp"))
        assertEquals("legacy-secret", vault.resolve(reference, id, "https://example.org/mcp"))
        assertTrue(runCatching { vault.bind(reference, Uuid.random(), "https://example.org/mcp") }.isFailure)
    }

    @Test fun `transport diagnostics redact embedded URL credentials and path tokens`() {
        val message = redactedMcpText("Failure for https://login:private-password@example.org/mcp/sk-proj-FAKE_private_path_1234567890?token=private-query")
        assertTrue(message, message.contains("https://example.org/mcp/"))
        for (secret in listOf("private-password", "sk-proj-FAKE_private_path_1234567890", "private-query")) assertFalse(message, message.contains(secret))
    }

    @Test fun `update approval shows redacted old to new URL and warns about credential reset`() {
        val old = McpServerConfig.StreamableHTTPServer(
            url = "https://login:old-password@example.org/mcp/sk-proj-FAKE_old_path_canary_1234567890?api_key=old-query-canary",
            commonOptions = McpCommonOptions(name = "Remote", enable = false),
        )
        val args = buildJsonObject {
            put("id", old.id.toString())
            put("name", "Remote")
            put("transport", "streamable_http")
            put("url", "https://next.example.org/mcp/sk-proj-FAKE_new_path_canary_1234567890?token=new-query-canary")
        }
        val preview = McpApprovalRenderer.render("mcp_update", args, old)!!
        assertTrue(preview, preview.contains("URL: https://example.org/mcp/"))
        assertTrue(preview, preview.contains(" → https://next.example.org/mcp/"))
        assertTrue(preview, preview.contains("OAuth") && preview.contains("сброшены"))
        assertTrue(preview, preview.contains("Включён: нет"))
        for (secret in listOf("old-password", "sk-proj-FAKE_old_path_canary_1234567890", "old-query-canary", "sk-proj-FAKE_new_path_canary_1234567890", "new-query-canary"))
            assertFalse(preview, preview.contains(secret))
    }
}
