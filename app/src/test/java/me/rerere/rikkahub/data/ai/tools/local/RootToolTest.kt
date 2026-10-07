package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import org.junit.Assert.*
import org.junit.Test

class RootToolTest {
    @Test fun `requires approval directly for every argument set`() {
        val tool = buildRootTool(RootShellManager(suExecutable = "/nonexistent/root-su"))
        assertEquals("root_exec", tool.name)
        assertTrue(tool.needsApproval(buildJsonObject { put("command", "id") }))
        assertTrue(tool.needsApproval(buildJsonObject { put("command", "rm -rf /") }))
        assertTrue(tool.needsApproval(JsonNull))
    }

    @Test fun `rejects destructive commands before probing root`() = runBlocking {
        val manager = RootShellManager(suExecutable = "/nonexistent/root-su")
        val tool = buildRootTool(manager)
        val output = tool.execute(buildJsonObject { put("command", "rm -rf /system") })
        val response = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("root_command_blocked", response["error"]!!.jsonPrimitive.content)
        assertEquals(RootStatus.UNCHECKED, manager.status.value)
    }

    @Test fun `rejects missing and malformed arguments without probing`() = runBlocking {
        val manager = RootShellManager(suExecutable = "/nonexistent/root-su")
        val tool = buildRootTool(manager)
        listOf(JsonNull, buildJsonObject {}, buildJsonObject { put("command", JsonNull) }, buildJsonObject { put("command", "id"); put("timeout_ms", "2000") }, buildJsonObject { put("command", "id"); put("timeout_ms", true) }).forEach { input ->
            val output = tool.execute(input)
            val response = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("invalid_arguments", response["error"]!!.jsonPrimitive.content)
        }
        assertEquals(RootStatus.UNCHECKED, manager.status.value)
    }
}
