package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ToolPermissionPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private val empty = Json.parseToJsonElement("{}")

    @Test fun mcpToolsRequireApprovalEvenWhenServerDoesNotRequestIt() {
        val tool = ToolPermissionPolicy.apply(Tool("mcp__server__opaque", "test", execute = { emptyList() }))
        assertTrue("MCP must use the same default approval gate as Agent", tool.needsApproval(empty))
    }

    @Test fun webProtectionIsOffByDefault() = runBlocking {
        ToolApprovalTestStore(temp.newFolder()).use { fixture ->
            fixture.preferences.setYolo(true)
            assertFalse(fixture.preferences.currentAskAfterWebContent())
            assertTrue("Reading a page must not override YOLO with the default settings",
                resolveToolAutoApproval(fixture.preferences, Uuid.random(), "mcp__server__write", webContentSeen = true))
        }
    }

    @Test fun eachRegisteredCapabilityAndJavascriptUsesDefaultApprovalGate() {
        for (name in ToolApprovalDefaults.ALWAYS_ASK) {
            val args = if (name == "root_exec") buildJsonObject { put("command", "id -u") } else empty
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }))
            assertTrue(name, tool.needsApproval(args))
        }
    }

    @Test fun readOnlyClipboardAndQuestionsKeepTheirSemantics() {
        val read = ToolPermissionPolicy.apply(Tool("clipboard_tool", "test", execute = { emptyList() }))
        assertFalse(read.needsApproval(buildJsonObject { put("action", "read") }))
        val ask = ToolPermissionPolicy.apply(Tool("ask_user", "test", execute = { emptyList() }))
        assertTrue(ask.needsApproval(empty))
        assertFalse(ToolPermissionPolicy.canGrantAlways("ask_user", empty))
    }

    @Test fun everyProtectedRootCategoryRequiresExplicitConfirmation() {
        val tool = ToolPermissionPolicy.apply(Tool("root_exec", "test", execute = { emptyList() }))
        val commands = listOf("rm -rf /", "mkfs.ext4 /data/image", "dd if=x of=/dev/block/sda", "fastboot flashing unlock",
            "recovery --wipe_data", "setenforce 0", "avbctl disable-verity", "mount -o remount,rw /system")
        commands.forEach { command ->
            val args = buildJsonObject { put("command", command) }
            assertTrue(command, tool.needsApproval(args))
            assertTrue(command, ToolPermissionPolicy.mandatoryConfirmation("root_exec", args))
            assertFalse(command, ToolPermissionPolicy.canGrantAlways("root_exec", args))
        }
    }
}
