package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SshHostFailureMessageTest {
    @Test fun `saved host metadata failures have Russian recovery and keep host name`() = runBlocking {
        val context = mockk<Context>()
        val repo = mockk<SshHostRepository>()
        val secrets = mockk<SshToolSecretSanitizer>()
        every { repo.toolSecrets } returns secrets
        every { secrets.resolveArgs(any(), any()) } answers { secondArg() }
        coEvery { repo.getAll() } throws IOException("Database unavailable")
        coEvery { repo.getByName(any()) } throws IOException("Database unavailable")
        coEvery { repo.upsert(any()) } throws IOException("Database unavailable")
        coEvery { repo.deleteByName(any()) } throws IOException("Database unavailable")
        val input = buildJsonObject {
            put("name", "build")
            put("host", "build.example")
            put("user", "builder")
            put("password", "unused")
            put("command", "pwd")
        }
        for (tool in listOf(saveSshHostTool(repo), listSshHostsTool(repo), deleteSshHostTool(repo), sshExecSavedTool(context, repo))) {
            val parts = tool.execute(input)
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertTrue(tool.name, result["error"]?.jsonPrimitive?.content.orEmpty().contains("SSH"))
            assertTrue(tool.name, result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
            assertEquals("Database unavailable", result["raw"]?.jsonPrimitive?.content)
            if (tool.name != "list_ssh_hosts") assertEquals("build", result["name"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `known hosts IO failure explains action and retains endpoint`() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } throws IOException("Read-only file system")
        val parts = forgetSshHostKeyTool(context).execute(buildJsonObject { put("host", "build.example") })
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result["error"]?.jsonPrimitive?.content.orEmpty().contains("SSH"))
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("build.example", result["host"]?.jsonPrimitive?.content)
        assertEquals("Read-only file system", result["raw"]?.jsonPrimitive?.content)
    }
}
