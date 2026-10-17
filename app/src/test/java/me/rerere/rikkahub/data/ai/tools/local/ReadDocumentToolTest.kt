package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ReadDocumentToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun access(scratch: File = temporary.newFolder(), upload: File = temporary.newFolder()) = LocalFileAccess(scratch, upload)
    private suspend fun result(access: LocalFileAccess, input: JsonObject, backend: ReadDocumentBackend? = null): JsonObject =
        Json.parseToJsonElement(readDocumentTool(context, access, backend).execute(input)
            .filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
    private fun args(path: String, max: Int? = null) = buildJsonObject { put("path", path); max?.let { put("max_chars", it) } }
    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, value) ->
            output.putNextEntry(ZipEntry(name)); output.write(value.toByteArray()); output.closeEntry()
        } }
    }
    private fun docx(file: File, text: String) = zip(file, mapOf("word/document.xml" to
        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""))
    private fun assertRussianError(result: JsonObject) {
        val error = result["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(result.toString(), error.contains(Regex("[А-Яа-я]")))
        assertNull(result["text"])
    }

    @Test fun realDocxCountsBeforeTruncatingAndReturnsNullPages() = runBlocking {
        val scratch = temporary.newFolder(); docx(File(scratch, "report.docx"), "Привет документ")
        val full = result(access(scratch), args("/scratch/report.docx"))
        assertEquals("Привет документ", full["text"]!!.jsonPrimitive.content)
        assertEquals("Привет документ".length, full["total_chars"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, full["pages"])
        assertFalse(full["truncated"]!!.jsonPrimitive.boolean)
        val limited = result(access(scratch), args("/scratch/report.docx", 6))
        assertEquals("Привет", limited["text"]!!.jsonPrimitive.content)
        assertEquals("Привет документ".length, limited["total_chars"]!!.jsonPrimitive.int)
        assertTrue(limited["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("report.docx"), scratch.list()!!.toList())
    }

    @Test fun realPptxAndEpubUseExistingParsers() = runBlocking {
        val scratch = temporary.newFolder()
        zip(File(scratch, "slides.pptx"), mapOf("ppt/slides/slide1.xml" to
            """<p:sld xmlns:p="urn:p" xmlns:a="urn:a"><p:sp><a:p><a:r><a:t>Первый слайд</a:t></a:r></a:p></p:sp></p:sld>"""))
        zip(File(scratch, "book.epub"), mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
            "OPS/one.xhtml" to "<html><body><p>Первая глава</p></body></html>"))
        assertTrue(result(access(scratch), args("slides.pptx"))["text"]!!.jsonPrimitive.content.contains("Первый слайд"))
        assertTrue(result(access(scratch), args("book.epub"))["text"]!!.jsonPrimitive.content.contains("Первая глава"))
    }

    @Test fun pdfRangeAndPageCountReachBackendThroughOwnedSnapshot() = runBlocking {
        val scratch = temporary.newFolder(); val source = File(scratch, "test.pdf").apply { writeText("pdf fixture") }
        var staged: File? = null
        val backend = ReadDocumentBackend { file, extension, from, to ->
            staged = file
            assertNotEquals(source.canonicalFile, file.canonicalFile)
            assertEquals("pdf fixture", file.readText())
            assertEquals("pdf", extension); assertEquals(301, from); assertEquals(302, to)
            ReadDocumentExtraction("страницы", 400)
        }
        val output = result(access(scratch), buildJsonObject { put("path", "test.pdf"); put("page_from", 301); put("page_to", 302); put("max_chars", 4) }, backend)
        assertEquals("стра", output["text"]!!.jsonPrimitive.content)
        assertEquals(8, output["total_chars"]!!.jsonPrimitive.int)
        assertEquals(400, output["pages"]!!.jsonPrimitive.int)
        assertFalse(staged!!.exists()); assertTrue(source.exists())
    }

    @Test fun defaultAndMaximumCharacterLimitsAreEnforced() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "test.pdf").writeText("pdf")
        val backend = ReadDocumentBackend { _, _, _, _ -> ReadDocumentExtraction("я".repeat(70_000), 1) }
        assertEquals(20_000, result(access(scratch), args("test.pdf"), backend)["text"]!!.jsonPrimitive.content.length)
        assertEquals(65_536, result(access(scratch), args("test.pdf", 65_536), backend)["text"]!!.jsonPrimitive.content.length)
        for (value in listOf(JsonPrimitive(0), JsonPrimitive(65537), JsonPrimitive("3"), JsonPrimitive(1.5), JsonNull)) {
            assertRussianError(result(access(scratch), buildJsonObject { put("path", "test.pdf"); put("max_chars", value) }, backend))
        }
    }

    @Test fun invalidPageRangesAndNonPdfPageArgumentsAreRejectedBeforeParsing() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "test.pdf").writeText("pdf"); docx(File(scratch, "test.docx"), "Текст")
        val backend = ReadDocumentBackend { _, _, _, _ -> fail("Invalid arguments reached parser"); ReadDocumentExtraction("") }
        for (input in listOf(
            buildJsonObject { put("path", "test.pdf"); put("page_from", 0) },
            buildJsonObject { put("path", "test.pdf"); put("page_from", 3); put("page_to", 2) },
            buildJsonObject { put("path", "test.pdf"); put("page_to", "2") },
            buildJsonObject { put("path", "test.docx"); put("page_from", 1) },
        )) assertRussianError(result(access(scratch), input, backend))
    }

    @Test fun badFilesAndParserFailuresReturnRussianErrorsAndCleanSnapshot() = runBlocking {
        val scratch = temporary.newFolder(); File(scratch, "bad.docx").writeText("not a zip")
        File(scratch, "empty.pptx").let { zip(it, mapOf("readme" to "no slides")) }
        File(scratch, "bad.epub").let { zip(it, mapOf("readme" to "no container")) }
        File(scratch, "bad.pdf").writeText("pdf")
        val resolver = access(scratch)
        for (path in listOf("bad.docx", "empty.pptx", "bad.epub", "absent.pdf", "/scratch", "unknown.txt", "/system/build.prop", "../private.pdf"))
            assertRussianError(result(resolver, args(path)))
        assertRussianError(result(resolver, args("bad.pdf"), ReadDocumentBackend { _, _, _, _ -> throw IllegalStateException("native secret details") }))
        assertFalse(scratch.list()!!.any { it.startsWith(".read-document-") })
    }

    @Test fun zipBombAndOversizedSourceAreRejectedBeforeBackend() = runBlocking {
        val scratch = temporary.newFolder()
        RandomAccessFile(File(scratch, "large.pdf"), "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        ZipOutputStream(File(scratch, "bomb.docx").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write("<document/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload.bin")); val chunk = ByteArray(65536)
            repeat(1025) { zip.write(chunk) }; zip.closeEntry()
        }
        val backend = ReadDocumentBackend { _, _, _, _ -> fail("Oversized source reached parser"); ReadDocumentExtraction("") }
        assertRussianError(result(access(scratch), args("large.pdf"), backend))
        val bomb = result(access(scratch), args("bomb.docx"), backend)
        assertRussianError(bomb)
        assertTrue(bomb.toString(), bomb["error"]!!.jsonPrimitive.content.contains("64 МиБ"))
        assertFalse(scratch.list()!!.any { it.startsWith(".read-document-") })
    }

    @Test fun sharedRevocationAfterParsingCannotReleaseExtractedText() = runBlocking {
        val primary = temporary.newFolder(); val scratch = temporary.newFolder()
        val source = File(primary, "report.pdf").apply { writeText("private") }; var enabled = true
        val policy = SharedStorageAccess({ enabled }, { true }, { mapOf("primary" to primary) }, emptyList())
        val resolver = LocalFileAccess(scratch, temporary.newFolder(), sharedStorageAccess = policy)
        assertRussianError(result(resolver, args(source.path), ReadDocumentBackend { _, _, _, _ -> enabled = false; ReadDocumentExtraction("secret", 1) }))
        assertTrue(source.exists()); assertTrue(scratch.list()!!.isEmpty())
    }

    @Test fun cancellationPropagatesAndRemovesOwnedSnapshot() = runBlocking {
        val scratch = temporary.newFolder(); val source = File(scratch, "cancel.pdf").apply { writeText("pdf") }
        var staged: File? = null
        var backendReached = false
        var callerJob: Job? = null
        try {
            withContext(Job()) {
                callerJob = currentCoroutineContext()[Job]!!
                result(access(scratch), args("cancel.pdf"), ReadDocumentBackend { file, _, _, _ ->
                    backendReached = true
                    staged = file
                    callerJob!!.cancel()
                    throw CancellationException("cancelled")
                })
            }
            fail("Cancellation was converted to document text")
        } catch (_: CancellationException) { }
        assertTrue("Cancellation must originate from the reached parser backend", backendReached)
        assertTrue("The actual tool caller must be cancelled", callerJob!!.isCancelled)
        assertNotNull(staged)
        assertFalse(staged!!.exists())
        assertTrue(source.exists())
        assertEquals("pdf", source.readText())
        assertEquals(listOf("cancel.pdf"), scratch.list()!!.toList())
    }

    @Test fun sourceSymlinkCannotBypassFileAccessAndToolDoesNotAskForExtraApproval() = runBlocking {
        val scratch = temporary.newFolder(); val outside = temporary.newFile().apply { writeText("secret") }
        Files.createSymbolicLink(File(scratch, "escape.pdf").toPath(), outside.toPath())
        assertRussianError(result(access(scratch), args("escape.pdf")))
        val tool = readDocumentTool(context, access())
        assertEquals("read_document", tool.name)
        assertFalse(tool.needsApproval(args("report.pdf")))
        assertEquals(listOf("path"), (tool.parameters() as me.rerere.ai.core.InputSchema.Obj).required)
    }

    @Test fun safDocumentUsesProviderStreamAndRevocationAfterParsingPreventsTextRelease() = runBlocking {
        val scratch = temporary.newFolder()
        val file = File(temporary.newFolder(), "report.docx").also { docx(it, "Документ SAF") }
        val tree = DocumentsContract.buildTreeDocumentUri("rikka.document.fixture", "primary:Docs")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Docs/report.docx")
        val provider = DocumentProvider(file)
        provider.attachInfo(context, ProviderInfo().apply {
            authority = tree.authority; name = provider.javaClass.name; packageName = context.packageName
            exported = true; grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal(tree.authority!!, provider)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolver = LocalFileAccess(scratch, temporary.newFolder(), context = context)
        val output = result(resolver, args(uri.toString()))
        assertEquals("Документ SAF", output["text"]!!.jsonPrimitive.content)
        assertTrue(provider.opens > 0)
        val revoked = result(resolver, args(uri.toString()), ReadDocumentBackend { _, _, _, _ ->
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ReadDocumentExtraction("secret")
        })
        assertRussianError(revoked)
        assertTrue(scratch.list()!!.isEmpty())
        assertTrue(file.exists())
    }

    private class DocumentProvider(private val file: File) : ContentProvider() {
        var opens = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE)
            return MatrixCursor(columns).apply { addRow(columns.map<String, Any> { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "primary:Docs/report.docx"
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> getType(uri)
                DocumentsContract.Document.COLUMN_SIZE -> file.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                else -> 0
            } }.toTypedArray()) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
            AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
