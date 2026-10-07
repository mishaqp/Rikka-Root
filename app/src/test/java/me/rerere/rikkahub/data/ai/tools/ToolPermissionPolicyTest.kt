package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.root.RootAccessStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolPermissionPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private val empty = Json.parseToJsonElement("{}")

    @Test fun webGuardOverridesEveryGrantButKeepsReadOnlyToolsAndOtherChats() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        store.setAutoApprove(true)
        ToolPermissionPolicy.registry.keys.forEach { store.grantAlways(it) }
        val original = store.permissions.value
        store.markWebContent("web-chat")
        for (name in ToolPermissionPolicy.registry.keys + "mcp__server__write") {
            val args = if (name == "root_exec") buildJsonObject { put("command", "id") } else empty
            val originalTool = Tool(name, "test", needsApproval = { true }, execute = { emptyList() })
            val webTool = ToolPermissionPolicy.apply(originalTool, store, "web-chat")
            val freshTool = ToolPermissionPolicy.apply(originalTool, store, "new-chat")
            assertTrue(name, webTool.needsApproval(args))
            assertFalse(name, freshTool.needsApproval(args))
            assertEquals(original, store.permissions.value)
        }
        val read = ToolPermissionPolicy.apply(Tool("clipboard_tool", "test", execute = { emptyList() }), store, "web-chat")
        assertFalse(read.needsApproval(buildJsonObject { put("action", "read") }))
        val preallowedMcp = ToolPermissionPolicy.apply(Tool("mcp__server__opaque", "test", execute = { emptyList() }), store, "web-chat")
        assertTrue("Opaque MCP preapproval cannot bypass the web guard", preallowedMcp.needsApproval(empty))
    }

    @Test fun defaultRequiresApprovalAndGlobalOrAlwaysAllowsSideEffects() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        ToolPermissionPolicy.registry.keys.forEach { name ->
            val args = if (name == "root_exec") buildJsonObject { put("command", "id -u") } else empty
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }), store)
            assertTrue(name, tool.needsApproval(args))
            store.grantAlways(name)
            assertFalse(name, tool.needsApproval(args))
            store.revoke(name)
            assertTrue(name, tool.needsApproval(args))
            store.setAutoApprove(true)
            assertFalse(name, tool.needsApproval(args))
            store.setAutoApprove(false)
        }
    }

    @Test fun readOnlyAndQuestionsKeepTheirSemantics() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        val read = ToolPermissionPolicy.apply(Tool("clipboard_tool", "test", execute = { emptyList() }), store)
        assertFalse(read.needsApproval(buildJsonObject { put("action", "read") }))
        val ask = ToolPermissionPolicy.apply(Tool("ask_user", "test", execute = { emptyList() }), store)
        store.setAutoApprove(true)
        store.grantAlways("ask_user")
        assertTrue(ask.needsApproval(empty))
        assertFalse(ToolPermissionPolicy.canGrantAlways("ask_user", empty))
    }

    @Test fun everyProtectedCategoryOverridesBothKindsOfGrant() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        val tool = ToolPermissionPolicy.apply(Tool("root_exec", "test", execute = { emptyList() }), store)
        val commands = listOf("rm -rf /", "mkfs.ext4 /data/image", "dd if=x of=/dev/block/sda", "fastboot flashing unlock",
            "recovery --wipe_data", "setenforce 0", "avbctl disable-verity", "mount -o remount,rw /system")
        store.grantAlways("root_exec")
        for (global in listOf(false, true)) {
            store.setAutoApprove(global)
            commands.forEach { command ->
                val args = buildJsonObject { put("command", command) }
                assertTrue(command, tool.needsApproval(args))
                assertFalse(command, ToolPermissionPolicy.canGrantAlways("root_exec", args))
            }
        }
    }

    @Test fun configuredMcpApprovalUsesGlobalGrantAndWarningHasExactlyThreeParagraphs() = runBlocking {
        val store = RootAccessStore(temp.newFolder())
        val tool = ToolPermissionPolicy.apply(Tool("mcp__server__write", "test", needsApproval = { true }, execute = { emptyList() }), store)
        assertTrue(tool.needsApproval(empty))
        store.grantAlways(tool.name)
        assertFalse(tool.needsApproval(empty))
        assertEquals(3, ToolPermissionPolicy.warningText().split("\n\n").size)
        assertTrue(ToolPermissionPolicy.registry.values.all { it in ToolPermissionPolicy.warningText() })
    }
}
