package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.sun.net.httpserver.HttpServer
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
import java.net.InetSocketAddress

/** Actual tool paths; no dependency on a proposed staging helper or reflection/API presence. */
class TransferStagingIsolationRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `SFTP keeps in progress bytes in app cache rather than the target directory`() = runBlocking { sftp(fail = false) }
    @Test fun `failed SFTP preserves old target and cleans isolated cache bytes`() = runBlocking { sftp(fail = true) }

    private suspend fun sftp(fail: Boolean) {
        val directory = temporary.newFolder("destination")
        val cache = temporary.newFolder("cache")
        val destination = File(directory, "artifact").apply { writeText("ORIGINAL") }
        val userPartial = File(directory, ".rikka-sftp-user.part").apply { writeText("USER FILE") }
        val context = mockk<Context> {
            every { cacheDir } returns cache
            every { externalCacheDirs } returns emptyArray()
        }
        val repo = mockk<SshHostRepository>()
        coEvery { repo.getByName("build") } returns SshHostEntity(name = "build", host = "build.example", user = "builder", createdAtMs = 0).apply { password = "unused" }
        val session = mockk<Session>(relaxed = true)
        val channel = mockk<ChannelSftp>(relaxed = true)
        val jsch = mockk<JSch>()
        every { session.openChannel("sftp") } returns channel
        var observedSiblings: List<String>? = null
        var observedCache: List<File>? = null
        every { channel.get(any<String>(), any<OutputStream>()) } answers {
            secondArg<OutputStream>().write("COMPLETE".toByteArray())
            assertEquals("ORIGINAL", destination.readText())
            observedSiblings = directory.list()!!.sorted()
            observedCache = cache.walkTopDown().filter { it.isFile }.toList()
            if (fail) throw IOException("remote transfer failed")
            Unit
        }
        mockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt")
        try {
            coEvery { probeReachability(context, "build.example", 22) } returns ProbeOutcome(null, "default", emptyList(), "192.0.2.1", 0)
            every { newJSch(context) } returns jsch
            every { openSshSession(jsch, any(), any(), any(), any(), any(), any()) } returns session
            val tool = sshDownloadTool(context, repo, LocalFileAccess(directory, temporary.newFolder("upload")))
            val result = Json.parseToJsonElement((tool.execute(buildJsonObject {
                put("name", "build"); put("remote_path", "/remote/artifact"); put("local_path", destination.absolutePath)
            }).single() as UIMessagePart.Text).text).jsonObject
            verify(exactly = 1) { channel.get(any<String>(), any<OutputStream>()) }
            if (fail) assertNotNull(result["error"]) else assertEquals(JsonPrimitive(true), result["success"])
            assertEquals(if (fail) "ORIGINAL" else "COMPLETE", destination.readText())
            assertEquals("Only the final target and pre-existing user file may be visible during transfer",
                listOf(userPartial.name, destination.name).sorted(), observedSiblings)
            assertEquals("Exactly one app-owned cached transfer must exist during get", 1, observedCache!!.size)
            assertFalse("Owned cache bytes must be cleaned after success or failure", cache.walkTopDown().any { it.isFile })
            assertEquals("USER FILE", userPartial.readText())
        } finally { unmockkStatic("me.rerere.rikkahub.data.ai.tools.local.SshToolKt") }
    }

    @Test fun `HTTP overwrite publishes the original cache stage without a second visible sibling`() = runBlocking {
        val bytes = "COMPLETE".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/artifact") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cache = temporary.newFolder("http-cache")
            val scratch = temporary.newFolder("scratch")
            val original = File(scratch, "artifact").apply { writeText("ORIGINAL") }
            val userPartial = File(scratch, ".download-user.partial").apply { writeText("USER FILE") }
            val context = mockk<Context> { every { cacheDir } returns cache }
            val access = spyk(LocalFileAccess(scratch, temporary.newFolder("http-upload")))
            val siblingSnapshots = mutableListOf<List<String>>()
            var sawCompletedCache = false
            every { access.checkFile(match { it.absolutePath == original.absolutePath }, true) } answers {
                val file = callOriginal()
                val stages = cache.walkTopDown().filter { it.isFile }.toList()
                if (stages.any { it.readText() == "COMPLETE" }) {
                    sawCompletedCache = true
                    assertEquals("ORIGINAL", original.readText())
                    siblingSnapshots += scratch.list()!!.sorted()
                }
                file
            }
            val result = Json.parseToJsonElement((downloadTool(context, access).execute(buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/artifact")
                put("filename", "artifact"); put("directory", "/scratch"); put("overwrite", true)
            }).single() as UIMessagePart.Text).text).jsonObject
            assertEquals(result.toString(), JsonPrimitive(true), result["success"])
            assertTrue("Observe the actual post-network publication checks", sawCompletedCache)
            assertTrue(siblingSnapshots.isNotEmpty())
            for (snapshot in siblingSnapshots) assertEquals("No second .download stage may appear in the target directory",
                listOf(original.name, userPartial.name).sorted(), snapshot)
            assertEquals("COMPLETE", original.readText())
            assertEquals("USER FILE", userPartial.readText())
            assertFalse(cache.walkTopDown().any { it.isFile })
        } finally { server.stop(0) }
    }
}
