package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ToolApprovalAllowListTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun workspaceDefaultsRetainWritePathConfirmationUntilExplicitChatOrYoloGrant() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            val id = Uuid.random()
            val safe = buildJsonObject { put("path", "/workspace/project/file.txt") }
            val system = buildJsonObject { put("path", "/etc/profile") }
            try {
                for (name in listOf("workspace_write_file", "workspace_edit_file")) {
                    assertTrue(resolveToolAutoApproval(fixture.preferences, id, name, false) {
                        resolveWorkspaceToolApproval(name, emptyMap(), safe)
                    })
                    assertFalse(resolveToolAutoApproval(fixture.preferences, id, name, false) {
                        resolveWorkspaceToolApproval(name, mapOf(name to false), system)
                    })
                    ToolApprovalAllowList.grantForChat(id, name)
                    assertTrue(resolveToolAutoApproval(fixture.preferences, id, name, false) {
                        resolveWorkspaceToolApproval(name, emptyMap(), system)
                    })
                }
                ToolApprovalAllowList.clearChat(id)
                fixture.preferences.setYolo(true)
                assertTrue(resolveToolAutoApproval(fixture.preferences, id, "workspace_write_file", false) {
                    resolveWorkspaceToolApproval("workspace_write_file", emptyMap(), system)
                })
            } finally { ToolApprovalAllowList.clearChat(id) }
        }
    }

    @Test fun chatGrantsAreIsolatedAndNeverPersist() = runBlocking {
        val first = Uuid.random()
        val second = Uuid.random()
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            try {
                ToolApprovalAllowList.grantForChat(first, "workspace_shell")
                assertTrue(resolveToolAutoApproval(fixture.preferences, first, "workspace_shell", false))
                assertFalse(resolveToolAutoApproval(fixture.preferences, second, "workspace_shell", false))
                assertTrue(fixture.preferences.current().isEmpty())
                ToolApprovalAllowList.clearChat(first)
                assertFalse(resolveToolAutoApproval(fixture.preferences, first, "workspace_shell", false))
            } finally { ToolApprovalAllowList.clearChat(first) }
        }
    }

    @Test fun yoloThenChatThenAlwaysAndWorkspaceOverridesAreReadFresh() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            val id = Uuid.random()
            var workspaceReads = 0
            suspend fun allowed() = resolveToolAutoApproval(fixture.preferences, id, "workspace_shell", true) {
                workspaceReads++
                true
            }
            try {
                assertFalse(allowed())
                fixture.preferences.setYolo(true)
                workspaceReads = 0
                assertTrue(allowed())
                assertEquals(0, workspaceReads)
                fixture.preferences.setYolo(false)
                ToolApprovalAllowList.grantForChat(id, "workspace_shell")
                assertTrue(allowed())
                assertEquals(0, workspaceReads)
                ToolApprovalAllowList.clearChat(id)
                assertFalse(allowed())
                assertTrue(resolveToolAutoApproval(fixture.preferences, id, "workspace_shell", true) { false })
                fixture.preferences.grantAlways("root_exec")
                assertTrue(resolveToolAutoApproval(fixture.preferences, id, "root_exec", true))
                fixture.preferences.revoke("root_exec")
                assertFalse(resolveToolAutoApproval(fixture.preferences, id, "root_exec", true))
                fixture.preferences.setYolo(true)
                ToolApprovalAllowList.grantForChat(id, "ask_user")
                assertFalse(resolveToolAutoApproval(fixture.preferences, id, "ask_user", false))
            } finally { ToolApprovalAllowList.clearChat(id) }
        }
    }

    @Test fun optionalWebProtectionOverridesAllGrantsOnlyInAffectedChat() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            val id = Uuid.random()
            fixture.preferences.setYolo(true)
            fixture.preferences.grantAlways("mcp__test__write")
            fixture.preferences.setAskAfterWebContent(true)
            try {
                ToolApprovalAllowList.grantForChat(id, "mcp__test__write")
                assertFalse(resolveToolAutoApproval(fixture.preferences, id, "mcp__test__write", true))
                assertTrue(resolveToolAutoApproval(fixture.preferences, id, "mcp__test__write", false))
                fixture.preferences.setAskAfterWebContent(false)
                assertTrue(resolveToolAutoApproval(fixture.preferences, id, "mcp__test__write", true))
            } finally { ToolApprovalAllowList.clearChat(id) }
        }
    }
}
