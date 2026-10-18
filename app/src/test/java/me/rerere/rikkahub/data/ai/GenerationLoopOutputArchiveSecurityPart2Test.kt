package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import io.mockk.mockk
import java.io.File
import java.io.Writer
import java.nio.file.DirectoryStream
import java.nio.file.SecureDirectoryStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real archive boundary; only the unused provider dependency is mocked. No shell is executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class GenerationLoopOutputArchiveSecurityPart2Test {
    @get:Rule val folder = TemporaryFolder()

    private class Fixture(val files: File) {
        val archiveDir = File(files, FileFolders.TOOL_OUTPUTS)
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files
        }
        private val loop = GenerationLoop(context, mockk<ProviderManager>(relaxed = true), Json)
        private val archive = GenerationLoop::class.java.getDeclaredMethod(
            "maybeTruncateToolOutput", UIMessagePart.Tool::class.java, List::class.java, Boolean::class.javaPrimitiveType,
            kotlin.Function1::class.java, kotlin.Function1::class.java,
        ).apply { isAccessible = true }

        fun invoke(
            tool: UIMessagePart.Tool,
            output: List<UIMessagePart>,
            hasShell: Boolean = true,
            openDirectory: ((Path) -> DirectoryStream<Path>)? = null,
            openWriter: ((Path) -> Writer)? = null,
        ): List<UIMessagePart> =
            archive.invoke(loop, tool, output, hasShell, openDirectory, openWriter) as List<UIMessagePart>

        fun files(): List<File> = archiveDir.walkTopDown().filter { it.isFile }.toList()
        fun envelope(output: List<UIMessagePart>): JsonObject {
            val text = output.filterIsInstance<UIMessagePart.Text>().single().text
            assertTrue("Archive reply must be compact JSON, not a shell command transcript: $text", text.trimStart().startsWith("{"))
            return Json.parseToJsonElement(text).jsonObject
        }
        fun archiveFile(envelope: JsonObject): File {
            val path = envelope.getValue("path").jsonPrimitive.content
            assertTrue(path, path.startsWith("/tool_outputs/"))
            val filename = path.removePrefix("/tool_outputs/")
            assertTrue("Only opaque safe filename is allowed: $filename", Regex("""[A-Za-z0-9_-]+\.txt""").matches(filename))
            val file = File(archiveDir, filename)
            assertEquals(archiveDir.canonicalFile, file.canonicalFile.parentFile)
            assertTrue("Referenced archive must exist: $file", file.isFile)
            return file
        }
    }

    private fun tool(id: String, name: String = "contract_archive") = UIMessagePart.Tool(
        toolCallId = id, toolName = name, input = "{\"exact\":\"007\"}", approvalState = ToolApprovalState.Approved,
    )
    private fun large(marker: String = "RAW"): String = marker + "я".repeat(40_000)

    @Test fun traversalCallIdCannotOverwriteSiblingSentinelOrEscapeArchiveDirectory() {
        val f = Fixture(folder.newFolder())
        f.archiveDir.mkdirs()
        val sentinel = File(f.files, "sentinel.txt").apply { writeText("KEEP EXACT") }
        f.invoke(tool("../sentinel"), listOf(UIMessagePart.Text(large())))
        assertEquals("toolCallId must never become a relative archive path", "KEEP EXACT", sentinel.readText())
        assertEquals(1, f.files().size)
        f.files().forEach { assertEquals(f.archiveDir.canonicalFile, it.canonicalFile.parentFile) }
    }

    @Test fun shellMetacharactersNeverReachArchiveFilenameOrSuggestedCommands() {
        val f = Fixture(folder.newFolder())
        val hostileId = "call;touch INJECTED\$(id)'`"
        val result = f.invoke(tool(hostileId), listOf(UIMessagePart.Text(large())))
        val stored = f.files().single()
        assertTrue("Filename must not contain shell payload: ${stored.name}", Regex("""[A-Za-z0-9_-]+\.txt""").matches(stored.name))
        val body = f.envelope(result)
        f.archiveFile(body)
        assertFalse(body.toString(), body.toString().contains(hostileId))
        assertFalse(body.toString(), body.toString().contains("INJECTED"))
        assertFalse("An archive description must not interpolate raw IDs into shell commands", body.toString().contains("\$(id)"))
    }

    @Test fun distinctAdversarialCallIdsNeverOverwriteEachOthersFullArchives() {
        val f = Fixture(folder.newFolder())
        val first = large("FIRST")
        val second = large("SECOND")
        f.invoke(tool("same?identity"), listOf(UIMessagePart.Text(first)))
        f.invoke(tool("same*identity"), listOf(UIMessagePart.Text(second)))
        val files = f.files()
        assertEquals("Unsafe-character replacement alone must not collapse distinct IDs", 2, files.size)
        assertEquals(setOf(first, second), files.map { it.readText() }.toSet())
        files.forEach { assertTrue(it.name, Regex("""[A-Za-z0-9_-]+\.txt""").matches(it.name)) }
    }

    @Test fun reusedCompletedCallIdCreatesFreshArchiveAndPreservesBothFullOutputs() {
        val f = Fixture(folder.newFolder())
        val call = tool("same-completed-call")
        val first = large("FIRST COMPLETED RESULT")
        val second = large("SECOND COMPLETED RESULT")
        f.invoke(call, listOf(UIMessagePart.Text(first)))
        val firstFile = f.files().single()
        f.invoke(call, listOf(UIMessagePart.Text(second)))
        assertEquals("A later call with the same provider ID must not overwrite an earlier archive", first, firstFile.readText())
        assertEquals(2, f.files().size)
        assertEquals(setOf(first, second), f.files().map { it.readText() }.toSet())
    }

    @Test fun preexistingRawIdSymlinkCannotRedirectArchiveWriteToSiblingFile() {
        val f = Fixture(folder.newFolder())
        f.archiveDir.mkdirs()
        val sentinel = File(f.files, "private-sentinel.txt").apply { writeText("PRIVATE KEEP EXACT") }
        val rawIdLink = File(f.archiveDir, "preexisting-call.txt")
        Files.createSymbolicLink(rawIdLink.toPath(), sentinel.toPath())
        val result = f.invoke(tool("preexisting-call"), listOf(UIMessagePart.Text(large("ARCHIVED FULL RESULT"))))
        assertEquals("Existing output symlink must never be followed for archive writes", "PRIVATE KEEP EXACT", sentinel.readText())
        assertTrue("The existing symlink belongs to somebody else and must remain untouched", Files.isSymbolicLink(rawIdLink.toPath()))
        val archived = f.archiveFile(f.envelope(result))
        assertNotEquals(rawIdLink.absolutePath, archived.absolutePath)
        assertEquals(large("ARCHIVED FULL RESULT"), archived.readText())
    }

    @Test fun archiveEnvelopeIsRussianAndAccurateWhileRawArchiveAndToolIdentityStayExact() {
        val f = Fixture(folder.newFolder())
        val call = tool("provider-call-007")
        val before = call.copy()
        val rawParts = listOf(UIMessagePart.Text(large("FIRST USER RAW")), UIMessagePart.Text("SECOND USER RAW\n"))
        val fullRaw = rawParts.joinToString("\n") { it.text }
        val body = f.envelope(f.invoke(call, rawParts))
        assertEquals(true, body["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(fullRaw.length, body["total_chars"]?.jsonPrimitive?.intOrNull)
        val preview = body.getValue("preview").jsonPrimitive.content
        assertTrue(preview.length <= 4096)
        assertTrue(fullRaw.startsWith(preview))
        assertTrue("Owned explanation must be Russian", Regex("[А-Яа-яЁё]").containsMatchIn(body["message"]?.jsonPrimitive?.contentOrNull.orEmpty()))
        assertEquals("Archive is the exact full raw joined tool text", fullRaw, f.archiveFile(body).readText())
        assertEquals("Archiving must not rename the provider/model tool call or change approval/input", before, call)
        assertEquals("provider-call-007", call.toolCallId)
        assertEquals("contract_archive", call.toolName)
    }

    @Test fun previewNeverCutsASupplementaryCharacterAtTheUtf16Limit() {
        val f = Fixture(folder.newFolder())
        val prefix = "a".repeat(4095)
        val raw = prefix + "\uD83D\uDE42" + large()
        val body = f.envelope(f.invoke(tool("unicode-call"), listOf(UIMessagePart.Text(raw))))
        val preview = body.getValue("preview").jsonPrimitive.content
        assertEquals(prefix, preview)
        assertFalse(preview.last().isHighSurrogate())
        assertEquals(raw.length, body["total_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals(raw, f.archiveFile(body).readText())
    }

    @Test fun imageAudioDocumentAndMetadataRemainUnchangedAlongsideArchivedText() {
        val f = Fixture(folder.newFolder())
        val image = UIMessagePart.Image("file:///original/image.png")
        val audio = UIMessagePart.Audio("file:///original/audio.wav")
        val document = UIMessagePart.Document("file:///original/report.pdf", "Отчёт.pdf", "application/pdf")
        val nonText = listOf(image, audio, document)
        val output = f.invoke(tool("media-call"), listOf(UIMessagePart.Text(large())) + nonText)
        assertEquals(nonText, output.filter { it !is UIMessagePart.Text })
        nonText.forEachIndexed { index, item -> assertSame(item, output[index + 1]) }
        assertEquals(large(), f.archiveFile(f.envelope(output)).readText())
    }

    @Test fun withoutWorkspaceShellFullOutputAndMediaStayUntouchedAndNoArchiveIsCreated() {
        val f = Fixture(folder.newFolder())
        val output = listOf(UIMessagePart.Text(large()), UIMessagePart.Image("file:///image.png"))
        assertSame(output, f.invoke(tool("../no-shell"), output, hasShell = false))
        assertFalse(f.archiveDir.exists())
    }

    @Test fun archiveWriteFailureAfterCompletedToolPreservesFullOriginalResultWithoutRetrySignal() {
        val f = Fixture(folder.newFolder())
        f.archiveDir.writeText("EXISTING REGULAR FILE KEEP EXACT")
        val output = listOf(
            UIMessagePart.Text(large("COMPLETED ACTION FULL USER RESULT")),
            UIMessagePart.Image("file:///completed-action/image.png"),
        )
        val actual = f.invoke(tool("completed-action-with-unavailable-archive"), output)
        assertSame("Archive I/O failure must preserve the completed tool result for UI instead of throwing", output, actual)
        assertEquals(output, actual)
        assertEquals("EXISTING REGULAR FILE KEEP EXACT", f.archiveDir.readText())
    }

    @Test fun symlinkArchiveDirectoryCannotWriteOutsideAndPreservesFullOriginalResult() {
        val f = Fixture(folder.newFolder())
        val outside = folder.newFolder("outside-archive-target")
        Files.createSymbolicLink(f.archiveDir.toPath(), outside.toPath())
        val output = listOf(
            UIMessagePart.Text(large("COMPLETED PRIVATE ORIGINAL RESULT")),
            UIMessagePart.Image("file:///completed-action/image.png"),
        )
        val actual = f.invoke(tool("archive-dir-symlink"), output)
        assertEquals("Archive directory symlink must not receive any output file", emptyList<String>(), outside.listFiles().orEmpty().map { it.name })
        assertTrue("Existing directory symlink must remain untouched", Files.isSymbolicLink(f.archiveDir.toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(f.archiveDir.toPath()))
        assertSame("Unsafe archive directory must fall back to the full completed tool result", output, actual)
        assertEquals(output, actual)
    }

    @Test fun parentSwapAtArchiveOpenNeverWritesOutsideAndReturnsOriginalResult() {
        val f = Fixture(folder.newFolder())
        f.archiveDir.mkdirs()
        val outside = folder.newFolder("outside-raced-archive-target")
        val held = File(f.files, "held-original-archive-directory")
        val attacked = AtomicBoolean(false)
        val output = listOf(
            UIMessagePart.Text(large("COMPLETED FULL RESULT BEFORE PARENT RACE")),
            UIMessagePart.Image("file:///completed-action/image.png"),
        )
        fun swapOnce(path: Path) {
            if (path == f.archiveDir.toPath() && attacked.compareAndSet(false, true)) {
                assertTrue("Fixture must move the original archive directory", f.archiveDir.renameTo(held))
                Files.createSymbolicLink(f.archiveDir.toPath(), outside.toPath())
            }
        }
        val actual = f.invoke(
            tool("parent-open-race"), output,
            openDirectory = { path ->
                val opened = Files.newDirectoryStream(path)
                assertTrue("Race fixture requires a real descriptor-relative directory stream", opened is SecureDirectoryStream<*>)
                Proxy.newProxyInstance(
                    SecureDirectoryStream::class.java.classLoader,
                    arrayOf(SecureDirectoryStream::class.java),
                ) { _, method, arguments ->
                    // Swap after the production identity checks, immediately before
                    // the real descriptor-relative channel creation.
                    if (method.name == "newByteChannel") swapOnce(path)
                    try {
                        method.invoke(opened, *(arguments ?: emptyArray()))
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                } as DirectoryStream<Path>
            },
            openWriter = { path ->
                // The seam-only implementation still resolves the unsafe parent pathname.
                swapOnce(path.parent)
                Files.newBufferedWriter(path, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            },
        )
        assertTrue("The actual archive operation must execute one of the parent-race hooks", attacked.get())
        assertEquals("Swapped archive parent must receive no file", emptyList<String>(), outside.listFiles().orEmpty().map { it.name })
        assertTrue("Race-created symlink must remain untouched", Files.isSymbolicLink(f.archiveDir.toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(f.archiveDir.toPath()))
        assertSame("Changed archive-directory identity must preserve the complete original result", output, actual)
        assertEquals(output, actual)
    }

    @Test fun smallOutputAndSearchExemptionRemainUntouchedWithoutCreatingArchives() {
        val f = Fixture(folder.newFolder())
        val small = listOf(UIMessagePart.Text("USER exact short text"))
        val search = listOf(UIMessagePart.Text(large("SEARCH JSON EXACT")))
        assertSame(small, f.invoke(tool("small-call"), small))
        assertSame(search, f.invoke(tool("search-call", "search_web"), search))
        assertFalse(f.archiveDir.exists())
    }
}
