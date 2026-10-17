package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** A repeated module-update check must not fail with "file already exists". */
class DownloadNamingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `url names are decoded and bounded`() {
        assertEquals("my file.zip", downloadNameFromUrl("https://example.com/a/my%20file.zip"))
        assertEquals("latest", downloadNameFromUrl("https://api.github.com/repos/o/r/releases/latest"))
        assertNull(downloadNameFromUrl("https://example.com/"))
        val long = downloadNameFromUrl("https://example.com/" + "a".repeat(300) + ".json")!!
        assertTrue(long.length <= 100)
        assertTrue(long.endsWith(".json"))
    }

    @Test fun `numbered names keep the extension`() {
        assertEquals("module-update.json", numberedDownloadName("module-update.json", 1))
        assertEquals("module-update (2).json", numberedDownloadName("module-update.json", 2))
        assertEquals("latest (3)", numberedDownloadName("latest", 3))
    }

    @Test fun `repeated download gets a free name and overwrite replaces only in scratch`() = runBlocking {
        val version = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repos/o/r/releases/latest") { exchange ->
            val bytes = """{"version":${version.incrementAndGet()}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val context = mockk<Context> { every { cacheDir } returns temp.newFolder("cache") }
            val scratch = temp.newFolder("scratch")
            val tool = downloadTool(context, LocalFileAccess(scratch, temp.newFolder("upload")))
            suspend fun download(overwrite: Boolean? = null): JsonObject {
                val parts = tool.execute(buildJsonObject {
                    put("url", "http://127.0.0.1:${server.address.port}/repos/o/r/releases/latest")
                    put("directory", "/scratch")
                    overwrite?.let { put("overwrite", it) }
                })
                return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            }
            assertEquals("latest.json", download()["filename"]?.jsonPrimitive?.content)
            val second = download()
            assertEquals(true, second["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("latest (2).json", second["filename"]?.jsonPrimitive?.content)
            assertEquals("""{"version":1}""", File(scratch, "latest.json").readText())
            val replaced = download(overwrite = true)
            assertEquals("latest.json", replaced["filename"]?.jsonPrimitive?.content)
            assertEquals("""{"version":3}""", File(scratch, "latest.json").readText())
        } finally { server.stop(0) }
    }

    @Test fun `failed overwrite preserves the original file when destination write fails`() = runBlocking {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/module-update.json") { exchange ->
            hits.incrementAndGet()
            val bytes = """{"version":2}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cache = temp.newFolder("failure-cache")
            val context = mockk<Context> { every { cacheDir } returns cache }
            val scratch = temp.newFolder("failure-scratch")
            val original = File(scratch, "module-update.json").apply { writeText("""{"version":1}""") }
            val access = spyk(LocalFileAccess(scratch, temp.newFolder("failure-upload")))
            coEvery { access.openOutput(any(), any()) } throws IOException("injected destination write failure")
            val tool = downloadTool(context, access)
            val parts = tool.execute(buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/module-update.json")
                put("directory", "/scratch")
                put("overwrite", true)
            })
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(1, hits.get())
            coVerify(exactly = 1) { access.openOutput(any(), any()) }
            assertTrue("The injected write failure must reach the tool result: $result", result.containsKey("error"))
            assertTrue("An unsuccessful replacement must retain the old file", original.exists())
            assertEquals("""{"version":1}""", original.readText())
            assertEquals(listOf("module-update.json"), scratch.listFiles()!!.map { it.name })
            assertTrue("Download staging must be cleaned", cache.listFiles()!!.isEmpty())
        } finally { server.stop(0) }
    }
}
