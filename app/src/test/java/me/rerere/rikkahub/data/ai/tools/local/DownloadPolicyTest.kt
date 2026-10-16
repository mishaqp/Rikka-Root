package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import io.mockk.mockk
import io.mockk.every
import android.content.Context
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class DownloadPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun moduleUpdateHintsUseJsonInsteadOfGithubHtml() {
        val description = downloadTool(mockk(), mockk()).description
        assertTrue(description, description.contains("updateJson"))
        assertTrue(description, description.contains("api.github.com/repos/"))
        assertTrue(description, description.contains("releases/latest"))
        assertTrue(description, description.contains("read_file"))
    }

    @Test fun githubHttpFailuresExplainCauseAndNextStepWithoutReturningRemoteBody() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val code = exchange.requestURI.path.removePrefix("/").toInt()
            exchange.sendResponseHeaders(code, 0)
            exchange.responseBody.use { it.write("private server error token=secret".toByteArray()) }
        }
        server.start()
        try {
            val cache = temp.newFolder("cache")
            val access = LocalFileAccess(temp.newFolder("scratch"), temp.newFolder("upload"))
            val context = mockk<Context> { every { cacheDir } returns cache }
            val tool = downloadTool(context, access)
            for ((code, nextStep) in listOf(401 to "авторизац", 403 to "ограничение", 404 to "адрес", 429 to "Подождите", 503 to "Повторите")) {
                val input = buildJsonObject {
                    put("url", "http://127.0.0.1:${server.address.port}/$code")
                    put("filename", "module-update.json"); put("directory", "/scratch")
                }
                val parts = tool.execute(input)
                val error = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
                    .getValue("error").jsonPrimitive.content
                assertTrue(error, error.contains("HTTP $code"))
                assertTrue(error, error.contains(nextStep, ignoreCase = true))
                assertFalse(error, error.contains("private"))
                assertFalse(error, error.contains("secret"))
                assertTrue(cache.listFiles().orEmpty().isEmpty())
            }
        } finally { server.stop(0) }
    }

    @Test fun githubApiJsonAndRedirectedUpdateJsonCanBeDownloadedAndReadWithExistingTools() = runBlocking {
        val payload = """{"version":"1.2","versionCode":12,"zipUrl":"https://example.com/module.zip"}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/repos/owner/repo/releases/latest") { exchange ->
            exchange.responseHeaders.add("Location", "/module-update.json")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/module-update.json") { exchange ->
            val bytes = payload.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val context = mockk<Context> { every { cacheDir } returns temp.newFolder("download-cache") }
            val access = LocalFileAccess(temp.newFolder("download-scratch"), temp.newFolder("download-upload"))
            val output = downloadTool(context, access).execute(buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/api/repos/owner/repo/releases/latest")
                put("filename", "module-update.json"); put("directory", "/scratch")
            })
            val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("true", result["success"]?.jsonPrimitive?.content)
            val read = fileManagerTools(access).first { it.name == "read_file" }.execute(buildJsonObject {
                put("path", "/scratch/module-update.json")
            })
            val body = Json.parseToJsonElement((read.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(Json.parseToJsonElement(payload), Json.parseToJsonElement(body.getValue("content").jsonPrimitive.content))
        } finally { server.stop(0) }
    }

    @Test fun downloadConnectionFailureExplainsNetworkRecoveryRatherThanOnlyGenericFailure() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.close() }
        server.start()
        try {
            val cache = temp.newFolder("broken-cache")
            val context = mockk<Context> { every { cacheDir } returns cache }
            val access = LocalFileAccess(temp.newFolder("broken-scratch"), temp.newFolder("broken-upload"))
            val result = downloadTool(context, access).execute(buildJsonObject {
                put("url", "http://127.0.0.1:${server.address.port}/broken")
                put("filename", "module-update.json"); put("directory", "/scratch")
            })
            val error = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
                .getValue("error").jsonPrimitive.content
            assertTrue(error, error.contains("Проверьте"))
            assertTrue(error, error.contains("интернет"))
            assertFalse(error, error.contains("Exception"))
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally { server.stop(0) }
    }
    @Test fun missingOrInvalidDownloadParametersExplainRecovery() = runBlocking {
        val tool = downloadTool(mockk(), mockk())
        listOf("{}" to "Укажите адрес файла в url.",
            """{"url":"https://example.com/file","max_bytes":"invalid"}""" to "Укажите max_bytes целым числом.")
            .forEach { (input, expected) ->
                val output = (tool.execute(Json.parseToJsonElement(input)).single() as UIMessagePart.Text).text
                assertEquals(expected, Json.parseToJsonElement(output).jsonObject["error"]!!.jsonPrimitive.content)
            }
    }

    @Test fun rejectsLocalSourcesCredentialsAndMissingHosts() {
        listOf("file:///etc/passwd", "content://docs/file", "https://user:password@example.com/x", "https:///x").forEach {
            try { validatedDownloadUrl(it); fail("accepted forbidden URL") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://example.com/track.mp3", validatedDownloadUrl("https://example.com/track.mp3"))
        assertEquals("https://api.github.com/repos/owner/repo/releases/latest",
            validatedDownloadUrl("https://api.github.com/repos/owner/repo/releases/latest"))
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/update.json",
            validatedDownloadUrl("https://raw.githubusercontent.com/owner/repo/main/update.json"))
    }

    @Test fun filenamesCannotEscapeOrHideTheDestination() {
        listOf("../secret", "..", "a/b", "a\\b", "\u0000bad", ".hidden").forEach {
            try { validatedDownloadFilename(it); fail("accepted unsafe filename") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("песня.mp3", validatedDownloadFilename("песня.mp3"))
    }

    @Test fun unknownLengthStillHonorsTheStreamingLimit() {
        val output = ByteArrayOutputStream()
        try {
            copyDownloadBounded(ByteArrayInputStream(ByteArray(9)), output, maxBytes = 8) {}
            fail("oversized stream succeeded")
        } catch (_: IOException) { }
        assertTrue(output.size() <= 8)
    }

    @Test fun exactLimitAndZeroByteFilesAreAccepted() {
        val output = ByteArrayOutputStream()
        assertEquals(8L, copyDownloadBounded(ByteArrayInputStream(ByteArray(8)), output, 8) {})
        assertEquals(0L, copyDownloadBounded(ByteArrayInputStream(byteArrayOf()), output, 8) {})
    }

    @Test fun cancellationIsCheckedBeforeAnyWrite() {
        val output = ByteArrayOutputStream()
        try {
            copyDownloadBounded(ByteArrayInputStream(ByteArray(8)), output, 8) { throw CancellationException() }
            fail("cancellation was ignored")
        } catch (_: CancellationException) { }
        assertEquals(0, output.size())
    }

    @Test fun negativeOrExcessiveByteLimitsAreRejected() {
        listOf(0L, -1L, MAX_DOWNLOAD_BYTES + 1).forEach {
            try { validatedDownloadLimit(it); fail("accepted unsafe limit") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
