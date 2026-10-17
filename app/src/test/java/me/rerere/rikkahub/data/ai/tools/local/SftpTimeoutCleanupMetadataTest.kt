package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class SftpTimeoutCleanupMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 15_000) fun `own timeout retains cleanup warning after ancestor swap without touching foreign files`() = runBlocking {
        fixture(shared = false) { tool, destination, sftp, _ ->
            val parent = destination.parentFile!!
            val outside = temporary.newFolder()
            val parked = File(parent.parentFile, "parked-${parent.name}")
            val foreignTarget = File(outside, destination.name).apply { writeText("FOREIGN TARGET") }
            var partialPath: String? = null
            var partialName: String? = null
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("PARTIAL".toByteArray())
                val partial = parent.listFiles()!!.single { it.name.endsWith(".part") }
                partialPath = partial.absolutePath
                partialName = partial.name
                Files.move(parent.toPath(), parked.toPath())
                Files.createSymbolicLink(parent.toPath(), outside.toPath())
                File(outside, partial.name).writeText("FOREIGN PARTIAL")
                waitUntilInterrupted()
            }
            try {
                val result = execute(tool, destination, timeoutSeconds = 1)
                assertEquals("FOREIGN TARGET", foreignTarget.readText())
                assertEquals("FOREIGN PARTIAL", File(outside, partialName!!).readText())
                assertEquals("ORIGINAL", File(parked, destination.name).readText())
                assertEquals("PARTIAL", File(parked, partialName!!).readText())
                assertTimeoutCleanup(result, destination, partialPath!!)
            } finally {
                if (Files.isSymbolicLink(parent.toPath())) Files.delete(parent.toPath())
            }
        }
    }

    @Test(timeout = 15_000) fun `own timeout retains cleanup warning after shared storage grant revocation`() = runBlocking {
        fixture(shared = true) { tool, destination, sftp, granted ->
            var partial: File? = null
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("PARTIAL".toByteArray())
                partial = destination.parentFile!!.listFiles()!!.single { it.name.endsWith(".part") }
                granted.set(false)
                waitUntilInterrupted()
            }
            val result = execute(tool, destination, timeoutSeconds = 1)
            assertEquals("ORIGINAL", destination.readText())
            assertEquals("Cleanup cannot remove bytes after the live capability is revoked", "PARTIAL", partial!!.readText())
            assertTimeoutCleanup(result, destination, partial!!.absolutePath)

            // Reuse the same tool after restoring the grant: diagnostics belong to the
            // completed invocation, not to a tool instance or a later download.
            granted.set(true)
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("COMPLETE".toByteArray())
                Unit
            }
            val next = execute(tool, destination, timeoutSeconds = 5)
            assertEquals("true", next["success"]?.jsonPrimitive?.content)
            assertEquals("COMPLETE", destination.readText())
            for (key in listOf("cleanup_failed", "partial_path", "partial_path_unverified", "cleanup_recovery")) {
                assertNull("Cleanup diagnostics cannot leak into a later invocation: $key", next[key])
            }
        }
    }

    @Test(timeout = 15_000) fun `parent cancellation remains cancellation when revoked access prevents partial cleanup`() = runBlocking {
        fixture(shared = true) { tool, destination, sftp, granted ->
            val started = CompletableDeferred<Unit>()
            var partial: File? = null
            var returned = false
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("PARTIAL".toByteArray())
                partial = destination.parentFile!!.listFiles()!!.single { it.name.endsWith(".part") }
                granted.set(false)
                started.complete(Unit)
                waitUntilInterrupted()
            }
            val job = launch(Dispatchers.Default) {
                execute(tool, destination, timeoutSeconds = 30)
                returned = true
            }
            try {
                withTimeout(5_000) { started.await() }
                job.cancelAndJoin()
                assertTrue("Caller cancellation must propagate", job.isCancelled)
                assertFalse("A cancellation must not become a timeout or normal tool result", returned)
                assertEquals("ORIGINAL", destination.readText())
                assertEquals("PARTIAL", partial!!.readText())
            } finally { job.cancelAndJoin() }
        }
    }

    private fun assertTimeoutCleanup(result: JsonObject, destination: File, partialPath: String) {
        assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("true", result["cleanup_failed"]?.jsonPrimitive?.content)
        assertEquals(partialPath, result["partial_path"]?.jsonPrimitive?.content)
        assertEquals("true", result["partial_path_unverified"]?.jsonPrimitive?.content)
        val warning = result["cleanup_recovery"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(warning, warning.contains("не подтверждено"))
        assertTrue(warning, warning.contains("Проверьте"))
        assertEquals(destination.absolutePath, result["local_path"]?.jsonPrimitive?.content)
        assertEquals("/remote/artifact", result["remote_path"]?.jsonPrimitive?.content)
        assertEquals("build", result["name"]?.jsonPrimitive?.content)
        assertFalse("Only safe cleanup metadata may survive the timeout", result.toString().contains("PRIVATE_TRANSFER_DIAGNOSTIC"))
        assertNull(result["raw"])
        assertNull(result["stacktrace"])
    }

    private suspend fun execute(tool: Tool, destination: File, timeoutSeconds: Int): JsonObject {
        val parts = tool.execute(buildJsonObject {
            put("name", "build"); put("remote_path", "/remote/artifact")
            put("local_path", destination.absolutePath); put("timeout_seconds", timeoutSeconds)
        })
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    private fun waitUntilInterrupted() {
        try { Thread.sleep(30_000) }
        catch (error: InterruptedException) { throw IOException("PRIVATE_TRANSFER_DIAGNOSTIC", error) }
        error("The fixture must be interrupted by timeout or parent cancellation")
    }

    private suspend fun fixture(shared: Boolean, block: suspend (Tool, File, ChannelSftp, AtomicBoolean) -> Unit) {
        val scratch = temporary.newFolder()
        val primary = temporary.newFolder()
        val granted = AtomicBoolean(true)
        val policy = SharedStorageAccess({ true }, { granted.get() }, { mapOf("primary" to primary) })
        val access = LocalFileAccess(scratch, temporary.newFolder(), sharedStorageAccess = policy)
        val directory = if (shared) File(primary, "Download").apply { mkdirs() } else scratch
        val destination = File(directory, "artifact").apply { writeText("ORIGINAL") }
        val context = mockk<Context>()
        val repository = mockk<SshHostRepository>()
        coEvery { repository.getByName("build") } returns SshHostEntity(
            name = "build", host = "build.example", user = "builder", createdAtMs = 0,
        ).apply { password = "unused" }
        val jsch = mockk<JSch>()
        val session = mockk<Session>(relaxed = true)
        val sftp = mockk<ChannelSftp>(relaxed = true)
        every { session.openChannel("sftp") } returns sftp
        mockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        try {
            coEvery { probeReachability(context, "build.example", 22) } returns
                ProbeOutcome(null, "default", emptyList(), "192.0.2.1", 0)
            every { newJSch(context) } returns jsch
            every { openSshSession(jsch, any(), any(), any(), any(), any(), any()) } returns session
            block(sshDownloadTool(context, repository, access), destination, sftp, granted)
        } finally { unmockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt") }
    }
}
