package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** Actual HTTP/file execution: a malformed optional flag must not authorize a download. */
class DownloadContractsPart2Test {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun withServer(test: suspend (String, AtomicInteger, Context, File, File) -> Unit) {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/artifact.json") { exchange ->
            hits.incrementAndGet()
            val bytes = "NEW".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cache = temporary.newFolder()
            val scratch = temporary.newFolder()
            val context = mockk<Context> { every { cacheDir } returns cache }
            test("http://127.0.0.1:${server.address.port}/artifact.json", hits, context, scratch, cache)
        } finally { server.stop(0) }
    }

    private suspend fun execute(context: Context, scratch: File, input: JsonObject): JsonObject {
        val tool = downloadTool(context, LocalFileAccess(scratch, temporary.newFolder()))
        val text = (tool.execute(input).single() as UIMessagePart.Text).text
        assertFalse(text, text.contains('\n'))
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test fun invalidOverwriteCannotSilentlyDownloadUnderANewName() = runBlocking {
        withServer { url, hits, context, scratch, cache ->
            val target = File(scratch, "artifact.json").apply { writeText("OLD") }
            val body = execute(context, scratch, buildJsonObject {
                put("url", url); put("directory", "/scratch"); put("filename", "artifact.json"); put("overwrite", "maybe")
            })
            assertTrue(body.toString(), body["error"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
            assertEquals("Invalid arguments must fail before HTTP", 0, hits.get())
            assertEquals("OLD", target.readText())
            assertEquals(setOf("artifact.json"), scratch.list()!!.toSet())
            assertTrue(cache.list()!!.isEmpty())
        }
    }

    @Test fun numericOverwriteCannotSilentlyAuthorizeAnyNetworkOrFileSideEffect() = runBlocking {
        withServer { url, hits, context, scratch, cache ->
            val body = execute(context, scratch, buildJsonObject {
                put("url", url); put("directory", "/scratch"); put("overwrite", 1)
            })
            assertTrue(body.toString(), body["error"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
            assertEquals(0, hits.get()); assertTrue(scratch.list()!!.isEmpty()); assertTrue(cache.list()!!.isEmpty())
        }
    }

    @Test fun nativeOverwritePublishesOnlyTheSelectedFileAndReportsActualDestination() = runBlocking {
        withServer { url, hits, context, scratch, _ ->
            val target = File(scratch, "artifact.json").apply { writeText("OLD") }
            val neighbor = File(scratch, "neighbor.json").apply { writeText("KEEP") }
            val body = execute(context, scratch, buildJsonObject {
                put("url", url); put("directory", "/scratch"); put("filename", "artifact.json"); put("overwrite", true); put("max_bytes", 3)
            })
            assertEquals(body.toString(), JsonPrimitive(true), body["success"])
            assertEquals(JsonPrimitive(3), body["bytes"]); assertEquals("artifact.json", body["filename"]?.jsonPrimitive?.content)
            assertNotNull(body["destination"]); assertEquals(1, hits.get())
            assertEquals("NEW", target.readText()); assertEquals("KEEP", neighbor.readText())
            assertEquals(setOf("artifact.json", "neighbor.json"), scratch.list()!!.toSet())
        }
    }

    @Test fun invalidNativeByteLimitKeepsRussianErrorAndFailsBeforeHttp() = runBlocking {
        withServer { url, hits, context, scratch, cache ->
            val body = execute(context, scratch, buildJsonObject { put("url", url); put("directory", "/scratch"); put("max_bytes", 0) })
            assertTrue(body.toString(), body["error"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
            assertEquals(0, hits.get()); assertTrue(scratch.list()!!.isEmpty()); assertTrue(cache.list()!!.isEmpty())
        }
    }
}
