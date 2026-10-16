package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ApprovalAuditPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    private val args = buildJsonObject { put("enabled", true) }

    @Test fun `audit 3 MCP configuration mutations require individual foreground approval`() {
        for (name in listOf("mcp_add", "mcp_update")) {
            assertTrue(name, ToolPermissionPolicy.mandatoryConfirmation(name, args))
            assertFalse(name, ToolPermissionPolicy.canGrantAlways(name, args))
            assertFalse(name, ToolPermissionPolicy.canGrantForChat(name, args))
            assertTrue(name, ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() })).needsApproval(args))
            assertEquals(name, "mandatory_confirmation", HeadlessToolPolicy.blockReason(name, args))
        }
    }

    @Test fun `audit 4 SMS sending requires individual foreground approval`() {
        val sms = buildJsonObject { put("recipient", "+70000000000"); put("body", "Проверка") }
        assertTrue(ToolPermissionPolicy.mandatoryConfirmation("send_sms", sms))
        assertFalse(ToolPermissionPolicy.canGrantAlways("send_sms", sms))
        assertFalse(ToolPermissionPolicy.canGrantForChat("send_sms", sms))
        assertEquals("interactive_tool", HeadlessToolPolicy.blockReason("send_sms", sms))
    }

    @Test fun `audit 3 and 4 headless denies restored Always grants and YOLO`() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { store ->
            val run = RunExecutionContext(Uuid.random(), Uuid.random(), RunOrigin.WORKFLOW)
            for (yolo in listOf(false, true)) {
                store.preferences.setYolo(yolo)
                for (name in listOf("mcp_add", "mcp_update", "send_sms")) {
                    // Persist directly to reproduce grants restored from an older backup.
                    store.preferences.grantAlways(name)
                    ToolApprovalAllowList.grantForChat(run.runId, name)
                    try {
                        assertFalse("$name with YOLO=$yolo", resolveHeadlessAutoApproval(store.preferences, run, name, args, false))
                    } finally { ToolApprovalAllowList.clearChat(run.runId) }
                }
            }
        }
    }

    @Test fun `audit 6 headless applies hardline to nested direct MCP arguments`() {
        val name = "mcp__server__execute"
        val malicious = Json.parseToJsonElement("""{"payload":{"steps":[{"script":"rm -rf /etc/passwd"}]}}""")
        assertTrue(HeadlessToolPolicy.blockReason(name, malicious)?.startsWith("hardline:") == true)
        assertNull(HeadlessToolPolicy.blockReason(name, Json.parseToJsonElement("""{"payload":{"script":"echo ready"}}""")))
    }

    @Test fun `audit 13 calendar and usage readers appear in tool permission registry`() {
        for (name in listOf("calendar_query", "get_screen_time")) {
            assertFalse("$name is missing from permission settings", ToolPermissionPolicy.registry[name].isNullOrBlank())
        }
    }
}
