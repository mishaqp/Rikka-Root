package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertTrue
import java.io.OutputStream
import kotlin.uuid.Uuid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceReminderTransformerTest {

    @Test
    fun `os-release should prefer pretty name`() {
        val content = """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.24.1
            PRETTY_NAME="Alpine Linux v3.24"
            HOME_URL="https://alpinelinux.org/"
        """.trimIndent()
        assertEquals("Alpine Linux v3.24", parseOsReleaseName(content))
    }

    @Test
    fun `os-release should fall back to name and version`() {
        val content = """
            # comment
            NAME='Ubuntu'
            VERSION_ID="24.04"
        """.trimIndent()
        assertEquals("Ubuntu 24.04", parseOsReleaseName(content))
    }

    @Test
    fun `os-release without name should return null`() {
        assertNull(parseOsReleaseName("ID=unknown\nnot a key value line\n"))
    }

    @Test
    fun `os-release name should be sanitized and truncated`() {
        val name = parseOsReleaseName("PRETTY_NAME=\"Distro\u0007 ${"x".repeat(200)}\"")!!
        assertEquals(80, name.length)
        assertEquals("Distro x", name.take(8))
    }

    @Test fun realWorkspaceTransformUsesRussianBuiltInFramingForBashAndPosixShell() {
        for (shell in listOf("/bin/bash", "/bin/sh")) {
            val (prompt, repository) = promptProbe(shell)
            val appText = prompt.replace(originalSystem, "").replace(userInstructions, "")
                .replace(workspaceName, "").replace(distribution, "")
            val nonRussian = appText.lineSequence().map(String::trim).filter(String::isNotEmpty)
                .filterNot { it.startsWith("<") && it.endsWith(">") }
                .filterNot { Regex("[А-Яа-яЁё]").containsMatchIn(it) }.toList()
            assertTrue("Built-in workspace instructions for $shell must be Russian: ${nonRussian.joinToString(" | ")}", nonRussian.isEmpty())
            coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
        }
    }

    @Test fun realWorkspaceTransformKeepsUserInstructionsNamesAndProtocolPathsExact() {
        for (shell in listOf("/bin/bash", "/bin/sh")) {
            val (prompt, repository) = promptProbe(shell)
            assertTrue(prompt.startsWith(originalSystem))
            assertTrue(prompt.contains(userInstructions))
            assertTrue(prompt.contains(workspaceName))
            assertTrue(prompt.contains(distribution))
            for (literal in listOf("<workspace>", "</workspace>", "<workspace_instructions>", "</workspace_instructions>",
                "/workspace", "/skills", "/builtin_skills", "/upload", "workspace_read_file", "workspace_write_file",
                "workspace_edit_file", "workspace_shell", "AGENTS.md", "/root/.agents/AGENTS.md", "/workspace/notes", shell)) {
                assertTrue("Workspace marker changed: $literal", prompt.contains(literal))
            }
            coVerify(exactly = 0) { repository.executeCommand(any(), any(), any(), any(), any()) }
        }
    }

    private val originalSystem = "Original user system prompt 中文"
    private val userInstructions = "User-authored AGENTS instruction: keep ExactIdentifier().\n用户提供的指令，保持原样。"
    private val workspaceName = "User workspace name 中文"
    private val distribution = "ExternalDistro 1.2"

    /** All filesystem reads are in-memory repository responses; no Rootfs or command runs. */
    private fun promptProbe(shell: String): Pair<String, WorkspaceRepository> = runBlocking {
        val id = Uuid.parse("12345678-1111-4000-8000-000000000001")
        val files = mapOf("/etc/os-release" to "PRETTY_NAME=\"$distribution\"\n",
            "/root/.agents/AGENTS.md" to userInstructions)
        val repository = mockk<WorkspaceRepository> {
            coEvery { getById(id.toString()) } returns WorkspaceEntity(id.toString(), workspaceName, "/not-used",
                WorkspaceShellStatus.READY.name, 0L, 0L)
            coEvery { rootfsShell(id.toString()) } returns shell
            coEvery { rootfsFileSize(id.toString(), any()) } coAnswers {
                files[secondArg<String>()].orEmpty().toByteArray(Charsets.UTF_8).size.toLong()
            }
            coEvery { exportRootfsFile(id.toString(), any(), any()) } coAnswers {
                thirdArg<OutputStream>().write(files[secondArg<String>()].orEmpty().toByteArray(Charsets.UTF_8))
            }
        }
        val context = TransformerContext(mockk<Context>(), Model(), Assistant(workspaceId = id), Settings(), workspaceCwd = "/workspace/notes")
        val original = listOf(UIMessage.system(originalSystem), UIMessage.user("Exact user message 中文"))
        val transformed = WorkspaceReminderTransformer(repository).transform(context, original)
        assertEquals(original.drop(1), transformed.drop(1))
        val system = transformed.first { it.role == MessageRole.SYSTEM }
        assertTrue(system.isSynthetic)
        system.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } to repository
    }

}
