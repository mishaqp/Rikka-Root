package me.rerere.rikkahub.data.ai.mcp.control

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpApprovalRendererTest {
    @Test fun `approval retains server details and masks secret headers`() {
        val args = buildJsonObject {
            put("name", "Remote"); put("transport", "sse"); put("url", "https://example.com/mcp")
            putJsonArray("headers") {
                addJsonObject { put("name", "Authorization"); put("value", "Bearer unique-secret-value") }
                addJsonObject { put("name", "Accept"); put("value", "application/json") }
            }
        }
        val rendered = McpApprovalRenderer.render("mcp_add", args)!!
        assertTrue(rendered.contains("Добавить сервер MCP"))
        assertTrue(rendered.contains("https://example.com/mcp"))
        assertTrue(rendered.contains("application/json"))
        assertFalse(rendered.contains("unique-secret-value"))
        assertNull(McpApprovalRenderer.render("another_tool", args))
    }
}
