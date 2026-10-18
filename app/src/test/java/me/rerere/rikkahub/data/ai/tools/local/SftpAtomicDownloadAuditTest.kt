package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class SftpAtomicDownloadAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var cache: File

    @Test fun `failed remote get preserves existing destination even after partial bytes`() = runBlocking {
        for (partial in listOf(false, true)) fixture { tool, destination, sftp ->
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                if (partial) secondArg<OutputStream>().write("PARTIAL".toByteArray())
                throw IOException("remote stream disconnected")
            }
            val result = Json.parseToJsonElement((tool.execute(arguments(destination.absolutePath)).single() as UIMessagePart.Text).text).jsonObject
            assertNotNull(result["error"])
            assertTrue("Existing destination must survive a failed get", destination.exists())
            assertEquals("ORIGINAL", destination.readText())
            assertEquals("Failed temporary download must be removed", listOf(destination.name), destination.parentFile!!.list()!!.sorted())
        }
    }

    @Test fun `successful get commits complete bytes only after the transfer finishes`() = runBlocking {
        fixture { tool, destination, sftp ->
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("COMPLETE".toByteArray())
                assertEquals("The final path must remain intact during transfer", "ORIGINAL", destination.readText())
                Unit
            }
            val result = Json.parseToJsonElement((tool.execute(arguments(destination.absolutePath)).single() as UIMessagePart.Text).text).jsonObject
            assertEquals("true", result["success"]?.jsonPrimitive?.content)
            assertEquals("COMPLETE", destination.readText())
            assertEquals(listOf(destination.name), destination.parentFile!!.list()!!.sorted())
        }
    }

    @Test(timeout = 10_000) fun `cancelling partial download preserves destination and cleans temporary file`() = runBlocking {
        fixture { tool, destination, sftp ->
            val started = CompletableDeferred<Unit>()
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("PARTIAL".toByteArray())
                started.complete(Unit)
                Thread.sleep(30_000)
                Unit
            }
            val job = launch(Dispatchers.Default) { tool.execute(arguments(destination.absolutePath)) }
            try {
                withTimeout(5_000) { started.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue("Cancellation must preserve an existing destination", destination.exists())
                assertEquals("ORIGINAL", destination.readText())
                assertEquals(listOf(destination.name), destination.parentFile!!.list()!!.sorted())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test(timeout = 10_000) fun `timeout during partial download preserves destination and removes temporary bytes`() = runBlocking {
        fixture { tool, destination, sftp ->
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("PARTIAL".toByteArray())
                Thread.sleep(30_000)
                Unit
            }
            val input = JsonObject(arguments(destination.absolutePath) + ("timeout_seconds" to kotlinx.serialization.json.JsonPrimitive(1)))
            val result = Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text).jsonObject
            assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
            assertTrue("Timeout must preserve an existing destination", destination.exists())
            assertEquals("ORIGINAL", destination.readText())
            assertEquals(listOf(destination.name), destination.parentFile!!.list()!!.sorted())
        }
    }

    @Test fun `ancestor symlink swapped during get cannot commit over a foreign outside target`() = runBlocking {
        ancestorSwapDuringGet(failTransfer = false)
    }

    @Test fun `ancestor symlink swapped during failed get cannot delete a foreign outside partial`() = runBlocking {
        ancestorSwapDuringGet(failTransfer = true)
    }

    @Test fun `ancestor symlink swapped during handshake cannot upload outside bytes`() = runBlocking {
        val outside = temporary.newFolder()
        File(outside, "artifact").writeText("OUTSIDE SECRET")
        fixture(upload = true, handshake = { destination ->
            val parent = destination.parentFile!!
            Files.move(parent.toPath(), File(parent.parentFile, "parked-${parent.name}").toPath())
            Files.createSymbolicLink(parent.toPath(), outside.toPath())
        }) { tool, destination, sftp ->
            var sent: ByteArray? = null
            every { sftp.put(any<InputStream>(), any<String>()) } answers {
                sent = firstArg<InputStream>().readBytes()
                Unit
            }
            val result = Json.parseToJsonElement((tool.execute(arguments(destination.absolutePath)).single() as UIMessagePart.Text).text).jsonObject
            assertNull("The upload must never pass bytes from outside the granted roots to SFTP", sent)
            verify(exactly = 0) { sftp.put(any<InputStream>(), any<String>()) }
            assertNotNull("A changed symlink ancestor must reject upload: $result", result["error"])
        }
    }

    @Test fun `replacement partial inode inside allowed parent cannot be published`() = runBlocking {
        sameScopePartialSwapDuringGet(failTransfer = false)
    }

    @Test fun `replacement partial inode inside allowed parent cannot be deleted after failure`() = runBlocking {
        sameScopePartialSwapDuringGet(failTransfer = true)
    }

    private suspend fun sameScopePartialSwapDuringGet(failTransfer: Boolean) {
        fixture { tool, destination, sftp ->
            var foreignPartial: File? = null
            var ownedPartial: File? = null
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("COMPLETE".toByteArray())
                val original = cache.walkTopDown().single { it.isFile && it.name.endsWith(".part") }
                val originalKey = Files.readAttributes(original.toPath(), BasicFileAttributes::class.java).fileKey()
                ownedPartial = File(original.parentFile, "owned-${original.name}.kept")
                Files.move(original.toPath(), ownedPartial!!.toPath())
                foreignPartial = File(original.parentFile, original.name).apply { writeText("FOREIGN REPLACEMENT") }
                assertNotEquals("The attacker replaces the inode, not the bytes of our open stream", originalKey,
                    Files.readAttributes(foreignPartial!!.toPath(), BasicFileAttributes::class.java).fileKey())
                if (failTransfer) throw IOException("remote stream disconnected after partial replacement")
                Unit
            }
            val result = Json.parseToJsonElement((tool.execute(arguments(destination.absolutePath)).single() as UIMessagePart.Text).text).jsonObject
            assertNotNull("A foreign temporary inode must reject publication: $result", result["error"])
            assertEquals("ORIGINAL", destination.readText())
            assertEquals("Cleanup must preserve the foreign replacement", "FOREIGN REPLACEMENT", foreignPartial!!.readText())
            assertEquals("COMPLETE", ownedPartial!!.readText())
            assertEquals("true", result["cleanup_failed"]?.jsonPrimitive?.content)
            assertEquals("true", result["partial_path_unverified"]?.jsonPrimitive?.content)
            assertEquals(foreignPartial!!.absolutePath, result["partial_path"]?.jsonPrimitive?.content)
        }
    }

    private suspend fun ancestorSwapDuringGet(failTransfer: Boolean) {
        fixture { tool, destination, sftp ->
            val parent = destination.parentFile!!
            val outside = temporary.newFolder()
            val parked = File(parent.parentFile, "parked-${parent.name}")
            var foreignPartial: File? = null
            var ownedPartial: File? = null
            val foreignTarget = File(outside, destination.name).apply { writeText("FOREIGN TARGET") }
            every { sftp.get(any<String>(), any<OutputStream>()) } answers {
                secondArg<OutputStream>().write("COMPLETE".toByteArray())
                ownedPartial = cache.walkTopDown().single { it.isFile && it.name.endsWith(".part") }
                Files.move(parent.toPath(), parked.toPath())
                Files.createSymbolicLink(parent.toPath(), outside.toPath())
                // A same-named user file is deliberately outside the cache capability.
                foreignPartial = File(outside, ownedPartial!!.name).apply { writeText("FOREIGN PARTIAL") }
                if (failTransfer) throw IOException("remote stream disconnected after ancestor swap")
                Unit
            }
            val result = Json.parseToJsonElement((tool.execute(arguments(destination.absolutePath)).single() as UIMessagePart.Text).text).jsonObject
            assertNotNull("A changed symlink ancestor must reject publication: $result", result["error"])
            assertEquals("Atomic commit must never replace a file outside the granted roots", "FOREIGN TARGET", foreignTarget.readText())
            assertEquals("Cleanup must never delete another file reached through the swapped target ancestor", "FOREIGN PARTIAL", foreignPartial!!.readText())
            assertEquals("ORIGINAL", File(parked, destination.name).readText())
            assertFalse("The independent owned cache inode can still be safely cleaned", ownedPartial!!.exists())
            for (key in listOf("cleanup_failed", "partial_path", "partial_path_unverified", "cleanup_recovery")) assertNull(key, result[key])
        }
    }

    private fun arguments(path: String) = buildJsonObject {
        put("name", "build"); put("remote_path", "/remote/artifact"); put("local_path", path)
        put("timeout_seconds", 5)
    }

    private suspend fun fixture(upload: Boolean = false, handshake: ((File) -> Unit)? = null,
        block: suspend (me.rerere.ai.core.Tool, java.io.File, ChannelSftp) -> Unit) {
        val directory = temporary.newFolder()
        val destination = java.io.File(directory, "artifact").apply { writeText("ORIGINAL") }
        cache = temporary.newFolder()
        val context = mockk<Context> {
            every { cacheDir } returns cache
            every { externalCacheDirs } returns emptyArray()
        }
        val repository = mockk<SshHostRepository>()
        coEvery { repository.getByName("build") } returns SshHostEntity(
            name = "build", host = "build.example", user = "builder", createdAtMs = 0,
        ).apply { password = "unused" }
        val access = LocalFileAccess(directory, temporary.newFolder())
        val jsch = mockk<JSch>()
        val session = mockk<Session>(relaxed = true)
        val sftp = mockk<ChannelSftp>(relaxed = true)
        every { session.openChannel("sftp") } returns sftp
        mockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        try {
            coEvery { probeReachability(context, "build.example", 22) } returns
                ProbeOutcome(null, "default", emptyList(), "192.0.2.1", 0)
            every { newJSch(context) } returns jsch
            every { openSshSession(jsch, any(), any(), any(), any(), any(), any()) } answers {
                handshake?.invoke(destination)
                session
            }
            val tool = if (upload) sshUploadTool(context, repository, access) else sshDownloadTool(context, repository, access)
            block(tool, destination, sftp)
        } finally { unmockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt") }
    }
}
