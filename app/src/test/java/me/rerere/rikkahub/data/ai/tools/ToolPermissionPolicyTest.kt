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

    @Test fun managementChangesAlwaysNeedOneExplicitApprovalAndCannotRunHeadless() {
        val actions = mapOf(
            "manage_assistants" to listOf("create", "update", "duplicate", "switch", "delete"),
            "manage_app_settings" to listOf("set"),
        )
        actions.forEach { (name, mutations) -> mutations.forEach { action ->
            val args = buildJsonObject { put("action", action) }
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }))
            assertTrue("$name/$action", tool.needsApproval(args))
            assertTrue("$name/$action", ToolPermissionPolicy.mandatoryConfirmation(name, args))
            assertFalse(ToolPermissionPolicy.canGrantForChat(name, args))
            assertFalse(ToolPermissionPolicy.canGrantAlways(name, args))
            assertEquals("mandatory_confirmation", HeadlessToolPolicy.blockReason(name, args))
        } }
    }

    @Test fun managementReadsRemainAvailableWithoutApprovalOrHeadlessBlock() {
        val actions = mapOf(
            "manage_assistants" to listOf("list", "get", "list_options"),
            "manage_app_settings" to listOf("get", "list_sections"),
        )
        actions.forEach { (name, reads) -> reads.forEach { action ->
            val args = buildJsonObject { put("action", action) }
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }))
            assertFalse("$name/$action", tool.needsApproval(args))
            assertFalse(ToolPermissionPolicy.mandatoryConfirmation(name, args))
            assertNull(HeadlessToolPolicy.blockReason(name, args))
        } }
    }

    @Test fun managementToolsCannotUseGlobalOrSavedAutomaticGrants() = runBlocking {
        ToolApprovalTestStore(temp.newFolder()).use { fixture ->
            val chat = Uuid.random()
            try {
                fixture.preferences.setYolo(true)
                for (name in listOf("manage_assistants", "manage_app_settings")) {
                    fixture.preferences.grantAlways(name)
                    ToolApprovalAllowList.grantForChat(chat, name)
                    assertFalse(name, resolveToolAutoApproval(fixture.preferences, chat, name, false))
                }
            } finally { ToolApprovalAllowList.clearChat(chat) }
        }
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

    @Test fun sshAndTermuxCannotBypassHardlineOrResetHostTrustAutomatically() {
        for (name in listOf("ssh_exec", "ssh_exec_saved", "termux_run_command", "termux_session_start")) {
            val dangerous = buildJsonObject { put("command", "setenforce 0") }
            assertTrue(name, ToolPermissionPolicy.mandatoryConfirmation(name, dangerous))
            assertFalse(name, ToolPermissionPolicy.canGrantAlways(name, dangerous))
            assertFalse(name, ToolPermissionPolicy.mandatoryConfirmation(name, buildJsonObject { put("command", "echo hello") }))
        }
        assertTrue(ToolPermissionPolicy.mandatoryConfirmation("ssh_forget_host_key", empty))
        assertFalse(ToolPermissionPolicy.canGrantAlways("ssh_forget_host_key", empty))
        assertNotNull(HeadlessToolPolicy.blockReason("ssh_forget_host_key", empty))
        assertTrue(ToolPermissionPolicy.mandatoryConfirmation("ssh_exec",
            buildJsonObject { put("command", "sh"); put("stdin", "setenforce 0") }))
        for (name in listOf("ssh_exec", "ssh_exec_saved")) {
            for (command in listOf("env sh", "/bin/busybox ash", "sudo -u root bash")) {
                val protected = buildJsonObject { put("command", command); put("stdin", "setenforce 0") }
                assertTrue(ToolPermissionPolicy.mandatoryConfirmation(name, protected))
                assertEquals("mandatory_confirmation", HeadlessToolPolicy.blockReason(name, protected))
                assertEquals("root_command_blocked", HeadlessToolPolicy.blockReason(name,
                    buildJsonObject { put("command", command); put("stdin", "reboot") }))
            }
        }
        assertTrue(ToolPermissionPolicy.mandatoryConfirmation("termux_run_command",
            Json.parseToJsonElement("""{"executable":"/data/data/com.termux/files/usr/bin/bash","arguments":["-c","setenforce 0"]}""")))
    }
}
