package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class McpToolSecretSanitizerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `persistence keeps Agent argument shape and moves raw headers to encrypted vault`() {
        val directory = temporary.newFolder()
        val vault = McpControlSecretStore(directory) { key }
        val sanitizer = McpToolSecretSanitizer(vault)
        val input = """{"transport":"streamable_http","name":"Remote","url":"https://example.com/mcp","headers":[{"name":"Authorization","value":"Bearer unique-canary-123"},{"name":"Accept","value":"application/json"}]}"""
        val safe = sanitizer.sanitizeForPersistence("mcp_add", input)
        assertFalse(safe.contains("unique-canary-123"))
        val parsed = Json.parseToJsonElement(safe).jsonObject
        assertEquals(Json.parseToJsonElement(input).jsonObject.keys, parsed.keys)
        val headers = parsed["headers"]!!.jsonArray
        assertEquals("Authorization", headers[0].jsonObject["name"]!!.jsonPrimitive.content)
        val reference = headers[0].jsonObject["value"]!!.jsonPrimitive.content
        assertTrue(McpControlSecretStore.isReference(reference))
        assertEquals("Bearer unique-canary-123", vault.resolve(reference))
        assertEquals(safe, sanitizer.sanitizeForPersistence("mcp_add", safe))
        assertEquals(safe, sanitizer.sanitizeForPersistence("mcp_add", input))
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun `export and unconfigured fallback contain neither raw headers nor vault references`() {
        for (input in listOf(
            """{"name":"Remote","headers":[{"name":"Authorization","value":"Bearer unique-canary-123"}]}""",
            """{"name":"Remote","headers":[{"name":"Authorization","value":"rikka-keystore:mcp:01234567-89ab-cdef-0123-456789abcdef"}]}""",
        )) {
            val exported = McpToolSecretSanitizer.sanitizeForExport("mcp_update", input)
            assertEquals("Remote", Json.parseToJsonElement(exported).jsonObject["name"]!!.jsonPrimitive.content)
            assertFalse(exported.contains("headers"))
            assertFalse(exported.contains("unique-canary"))
            assertFalse(exported.contains(McpControlSecretStore.REFERENCE_PREFIX))
        }
    }

    @Test fun `malformed and incomplete streaming inputs fail closed`() {
        val sanitizer = McpToolSecretSanitizer(McpControlSecretStore(temporary.newFolder()) { key })
        for (name in listOf("mcp_add", "mcp_update", "mcp_", "")) {
            val input = """{"headers":[{"name":"Authorization","value":"Bearer unique-canary"""
            val safe = sanitizer.sanitizeForPersistence(name, input)
            assertTrue(Json.parseToJsonElement(safe) is JsonObject)
            assertFalse(safe.contains("unique-canary"))
            assertFalse(McpToolSecretSanitizer.sanitizeForExport(name, input).contains("unique-canary"))
        }
    }

    @Test fun `URL credentials never reach history or export and unrelated tools remain unchanged`() {
        val sanitizer = McpToolSecretSanitizer(McpControlSecretStore(temporary.newFolder()) { key })
        for (url in listOf("https://user:unique-canary@example.com/mcp", "https://example.com/mcp?token=unique-canary", "https://example.com/mcp#unique-canary")) {
            val input = """{"url":"$url","name":"Remote"}"""
            assertFalse(sanitizer.sanitizeForPersistence("mcp_add", input).contains("unique-canary"))
            assertFalse(McpToolSecretSanitizer.sanitizeForExport("mcp_add", input).contains("unique-canary"))
            assertEquals(input, sanitizer.sanitizeForPersistence("unrelated_tool", input))
        }
    }

    @Test fun `unexpected secret fields are masked without losing argument keys`() {
        val sanitizer = McpToolSecretSanitizer(McpControlSecretStore(temporary.newFolder()) { key })
        val input = """{"name":"Remote","extra":{"password":"unique-secret-value"}}"""
        val safe = sanitizer.sanitizeForPersistence("mcp_add", input)
        assertFalse(safe.contains("unique-secret-value"))
        assertTrue(Json.parseToJsonElement(safe).jsonObject.containsKey("extra"))
        assertFalse(McpToolSecretSanitizer.sanitizeForExport("mcp_update", input).contains("unique-secret-value"))
    }
}
