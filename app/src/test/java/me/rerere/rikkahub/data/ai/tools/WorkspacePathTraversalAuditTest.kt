package me.rerere.rikkahub.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.*
import org.junit.Test

/** Аудит 9b74e541, пункт 8: лексические обходы не доходят до файловой системы. */
class WorkspacePathTraversalAuditTest {
    private val repository = mockk<WorkspaceRepository>(relaxed = true) {
        coEvery { getById("w") } returns null
        coEvery { rootfsFileSize(any(), any()) } returns 0L
        coEvery { executeCommand(any(), any(), any(), any(), any()) } returns
            WorkspaceCommandResult(0, "f\u00001\u00001\u0000/workspace/accepted\u0000", "")
        coEvery { startBackground(any(), any(), any()) } returns
            BackgroundStatus("p", "pwd", "", false, 0, 0, "", "", 0, 0)
    }

    @Test fun traversalCannotBypassWritableRootApproval() {
        for (name in listOf("workspace_write_file", "workspace_edit_file")) {
            for (path in listOf("/workspace/../etc/profile", "/workspace/./file", "/tmp/../etc/profile", "/skills/./file", "/workspace\\..\\etc\\profile")) {
                assertTrue("$name: $path", resolveWorkspaceToolApproval(name, emptyMap(), buildJsonObject { put("path", path) }))
            }
        }
    }

    @Test fun readRejectsDotSegmentsBeforeFilesystemAccess() = runBlocking {
        assertRejected("workspace_read_file", "path", "/workspace/../etc/profile")
        coVerify(exactly = 0) { repository.rootfsFileSize(any(), any()) }
    }

    @Test fun writeRejectsDotSegmentsEvenAfterApproval() = runBlocking {
        assertRejected("workspace_write_file", "path", "/workspace/../etc/profile")
        coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
    }

    @Test fun editRejectsDotSegmentsBeforeReadingOriginal() = runBlocking {
        assertRejected("workspace_edit_file", "path", "/workspace/./file")
        coVerify(exactly = 0) { repository.rootfsFileSize(any(), any()) }
    }

    @Test fun shellRejectsTraversalInExplicitCwd() = runBlocking {
        assertRejected("workspace_shell", "cwd", "/workspace/../etc")
        coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
    }

    @Test fun backgroundRejectsTraversalInExplicitCwd() = runBlocking {
        assertRejected("workspace_background_start", "cwd", "../etc")
        coVerify(exactly = 0) { repository.startBackground(any(), any(), any()) }
    }

    @Test fun inheritedCwdCannotBypassTraversalValidation() = runBlocking {
        for (name in listOf("workspace_shell", "workspace_background_start")) {
            val tool = createWorkspaceTools("w", repository, cwd = "/workspace/../etc").first { it.name == name }
            val parts = tool.execute(buildJsonObject { put("command", "pwd") })
            assertTrue(name, Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject.containsKey("error"))
        }
        coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.startBackground(any(), any(), any()) }
    }

    @Test fun ordinaryWritableRootsKeepTheirApprovalPolicy() {
        for (path in listOf("/workspace/file", "/tmp/file", "/skills/name/file")) {
            assertFalse(path, resolveWorkspaceToolApproval("workspace_write_file", emptyMap(), buildJsonObject { put("path", path) }))
        }
        assertTrue(resolveWorkspaceToolApproval("workspace_write_file", emptyMap(), buildJsonObject { put("path", "/etc/profile") }))
    }

    private suspend fun assertRejected(name: String, parameter: String, path: String) {
        val tool = createWorkspaceTools("w", repository).first { it.name == name }
        val parts = tool.execute(buildJsonObject {
            put(parameter, path); put("text", "replacement"); put("old_text", "before"); put("new_text", "after"); put("command", "pwd")
        })
        val output = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertTrue("$name должен отклонить $path: $output", output.containsKey("error"))
    }
}
