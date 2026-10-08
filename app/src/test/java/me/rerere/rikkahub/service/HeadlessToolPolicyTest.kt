package me.rerere.rikkahub.service
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.ai.tools.RunWebTaint
import org.junit.Assert.*
import org.junit.Test
class HeadlessToolPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun interactiveToolsRemainBlockedEvenWhenAutoApprovalWouldAllow() {
        for (name in listOf("ask_user", "grant_directory_access", "verify_fingerprint", "take_photo", "share", "open_url", "get_screen_time", "keystore_encrypt", "keystore_decrypt")) {
            assertEquals("interactive_tool", HeadlessToolPolicy.blockReason(name, buildJsonObject {}))
        }
        assertNull(HeadlessToolPolicy.blockReason("read_file", buildJsonObject {}))
    }
    @Test fun mandatoryRootConfirmationCannotBeSkipped() {
        assertEquals("mandatory_confirmation", HeadlessToolPolicy.blockReason("root_exec", buildJsonObject { put("command", "setenforce 0") }))
        assertNull(HeadlessToolPolicy.blockReason("root_exec", buildJsonObject { put("command", "id") }))
    }
    @Test fun webOriginPropagatesBothWaysAcrossConcurrentChildren() {
        val parent = RunWebTaint()
        val one = RunWebTaint(parent = parent)
        val two = RunWebTaint(parent = parent)
        assertFalse(one.isTainted())
        two.mark()
        assertTrue(parent.isTainted())
        assertTrue(one.isTainted())
    }
    @Test fun headlessUsesFreshRunScopeAndRechecksAlwaysGrants() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { store ->
            val creator = Uuid.random()
            val run = RunExecutionContext(Uuid.random(), Uuid.random(), RunOrigin.CRON, callerConversationId = creator)
            val args = buildJsonObject {}
            ToolApprovalAllowList.grantForChat(creator, "write_text_file")
            try {
                assertFalse(resolveHeadlessAutoApproval(store.preferences, run, "write_text_file", args, false))
                store.preferences.grantAlways("write_text_file")
                assertTrue(resolveHeadlessAutoApproval(store.preferences, run, "write_text_file", args, false))
                store.preferences.revoke("write_text_file")
                assertFalse(resolveHeadlessAutoApproval(store.preferences, run, "write_text_file", args, false))
            } finally { ToolApprovalAllowList.clearChat(creator) }
        }
    }
    @Test fun yoloCannotBypassForbiddenRootInteractiveUiOrWebGuard() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { store ->
            val run = RunExecutionContext(Uuid.random(), Uuid.random(), RunOrigin.SUB_AGENT)
            store.preferences.setYolo(true)
            val args = buildJsonObject {}
            assertTrue(resolveHeadlessAutoApproval(store.preferences, run, "write_text_file", args, false))
            assertFalse(resolveHeadlessAutoApproval(store.preferences, run, "root_exec", buildJsonObject { put("command", "rm -rf /system") }, false))
            assertFalse(resolveHeadlessAutoApproval(store.preferences, run, "keystore_encrypt", args, false))
            store.preferences.setAskAfterWebContent(true)
            run.webTaint.mark()
            assertFalse(resolveHeadlessAutoApproval(store.preferences, run, "write_text_file", args, false))
        }
    }
    @Test fun workspaceUsesFreshWorkspaceOverrides() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { store ->
            val run = RunExecutionContext(Uuid.random(), Uuid.random(), RunOrigin.CRON)
            val args = buildJsonObject {}
            var needsApproval = false
            assertTrue(resolveHeadlessAutoApproval(store.preferences, run, "workspace_shell", args, false) { needsApproval })
            needsApproval = true
            assertFalse(resolveHeadlessAutoApproval(store.preferences, run, "workspace_shell", args, false) { needsApproval })
        }
    }

    @Test fun headlessWorkspaceShellCannotBypassVisibleCommandGuard() {
        for (name in listOf("workspace_shell", "workspace_background_start")) {
            assertEquals("root_command_blocked", HeadlessToolPolicy.blockReason(name, buildJsonObject { put("command", "rm -rf /system") }))
            assertEquals("mandatory_confirmation", HeadlessToolPolicy.blockReason(name, buildJsonObject { put("command", "setenforce 0") }))
            assertNull(HeadlessToolPolicy.blockReason(name, buildJsonObject { put("command", "pwd") }))
        }
        assertNull(HeadlessToolPolicy.blockReason("write_text_file", buildJsonObject { put("command", "rm -rf /system") }))
    }

}
