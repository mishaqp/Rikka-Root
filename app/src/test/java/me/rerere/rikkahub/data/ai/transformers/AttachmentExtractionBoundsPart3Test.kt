package me.rerere.rikkahub.data.ai.transformers

import android.app.Application
import androidx.core.net.toUri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.PdfParser
import me.rerere.document.PdfRangeResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AttachmentExtractionBoundsPart3Test {
    @get:Rule val temporary = TemporaryFolder()
    private val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    @Test fun oversizedPlainSourceProducesRussianRefusalAndKeepsOriginal() = runBlocking {
        val source = temporary.newFile("large.txt")
        RandomAccessFile(source, "rw").use { it.write("Original source".toByteArray()); it.setLength(32L * 1024 * 1024 + 1) }
        val digest = digest(source)
        val output = transform(source, "text/plain")
        assertTrue("Expected visible source-size refusal", output.contains("32 МиБ"))
        assertTrue(output.contains(Regex("[А-Яа-я]")))
        assertTrue("Refusal must not embed the oversized source", output.length < 4096)
        assertArrayEquals(digest, digest(source))
    }

    @Test fun plainTextOverExistingExtractionCeilingIsExplicitlyTruncated() = runBlocking {
        val source = temporary.newFile("long.txt")
        source.writeText("x".repeat(4_000_000) + "DO_NOT_SILENTLY_EMBED_THIS_TAIL")
        val digest = digest(source)
        val output = transform(source, "text/plain")
        assertTrue("Prompt must say that extraction was truncated", output.contains("обрезан", ignoreCase = true))
        assertFalse(output.contains("DO_NOT_SILENTLY_EMBED_THIS_TAIL"))
        assertTrue(output.length < 4_002_000)
        assertTrue(output.contains("x".repeat(128)))
        assertArrayEquals(digest, digest(source))
    }

    @Test fun tooManyZipEntriesAreRefusedBeforeEmbeddingDocumentText() = runBlocking {
        val source = temporary.newFile("entries.docx")
        ZipOutputStream(source.outputStream()).use { zip ->
            repeat(10_000) { index -> entry(zip, "padding/$index", "") }
            entry(zip, "word/document.xml", docx("MUST_NOT_EMBED_OVER_BUDGET"))
        }
        val digest = digest(source)
        val output = transform(source, docxMime)
        assertTrue("Entry ceiling must be visible", output.contains("10000"))
        assertTrue(output.contains(Regex("[А-Яа-я]")))
        assertFalse(output.contains("MUST_NOT_EMBED_OVER_BUDGET"))
        assertArrayEquals(digest, digest(source))
    }

    @Test fun zipExpansionBudgetIncludesEntriesAfterTheReadableDocument() = runBlocking {
        val source = temporary.newFile("expanded.docx")
        ZipOutputStream(source.outputStream()).use { zip ->
            entry(zip, "word/document.xml", docx("MUST_NOT_EMBED_OVER_BUDGET"))
            zip.putNextEntry(ZipEntry("padding.bin"))
            val block = ByteArray(64 * 1024)
            repeat(1024) { zip.write(block) }
            zip.write(0)
            zip.closeEntry()
        }
        val digest = digest(source)
        val output = transform(source, docxMime)
        assertTrue("Expanded-size refusal must be visible", output.contains("64 МиБ"))
        assertFalse(output.contains("MUST_NOT_EMBED_OVER_BUDGET"))
        assertArrayEquals(digest, digest(source))
    }

    @Test fun smallTextDocxPptxAndEpubKeepTheirCompleteTextAndOriginalAttachment() = runBlocking {
        val text = temporary.newFile("small.txt").apply { writeText("Полный текст attachment") }
        assertTrue(transform(text, "text/plain").contains("Полный текст attachment"))
        val word = archive("small.docx", mapOf("word/document.xml" to docx("Полный документ")))
        assertTrue(transform(word, docxMime).contains("Полный документ"))
        val slides = archive("small.pptx", mapOf("ppt/slides/slide1.xml" to """<p:sld xmlns:p="urn:p" xmlns:a="urn:a"><p:sp><a:p><a:r><a:t>Полный слайд</a:t></a:r></a:p></p:sp></p:sld>"""))
        assertTrue(transform(slides, "application/vnd.openxmlformats-officedocument.presentationml.presentation").contains("Полный слайд"))
        val book = archive("small.epub", mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
            "OPS/one.xhtml" to "<html><body><p>Полная глава</p></body></html>"))
        assertTrue(transform(book, "application/epub+zip").contains("Полная глава"))
    }

    @Test fun limitedPdfExtractionMustBeVisibleInThePrompt() = runBlocking {
        val source = temporary.newFile("limited.pdf").apply { writeText("native PDF boundary fixture") }
        val text = "p".repeat(4_000_001)
        mockkObject(PdfParser)
        try {
            every { PdfParser.parserPdf(any()) } returns text
            every { PdfParser.parseRange(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3).invoke()
                PdfRangeResult(text, 80, limited = true)
            }
            val output = transform(source, "application/pdf")
            assertTrue("Native backend limited flag must reach model", output.contains("обрезан", ignoreCase = true))
            assertTrue(output.length < 4_002_000)
            assertEquals("native PDF boundary fixture", source.readText())
        } finally { unmockkObject(PdfParser) }
    }

    @Test fun cancellationStopsArchiveXmlIterationInsteadOfFinishingTheDocument() = runBlocking {
        val source = archive("cancel.docx", mapOf("word/document.xml" to docx("text")))
        val realFactory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val factory = mockk<XmlPullParserFactory>(relaxed = true)
        val child = Job()
        var steps = 0
        var stepsAfterCancel = 0
        every { factory.newPullParser() } answers {
            val parser = realFactory.newPullParser()
            object : XmlPullParser by parser {
                override fun next(): Int {
                    if (!child.isActive) stepsAfterCancel++
                    val event = parser.next()
                    if (++steps == 3) child.cancel(CancellationException("fixture cancellation"))
                    return event
                }
            }
        }
        mockkStatic(XmlPullParserFactory::class)
        every { XmlPullParserFactory.newInstance() } returns factory
        try {
            var cancelled = false
            try { withContext(child) { transform(source, docxMime) } }
            catch (_: CancellationException) { cancelled = true }
            assertTrue("Cancellation must propagate", cancelled)
            assertTrue("Fixture must reach a real XML parser event", steps >= 3)
            assertEquals("Parser must stop at cancellation, not consume remaining XML", 0, stepsAfterCancel)
            assertTrue(source.exists())
        } finally { child.cancel(); unmockkStatic(XmlPullParserFactory::class) }
    }

    private suspend fun transform(file: File, mime: String): String {
        val document = UIMessagePart.Document(url = file.toUri().toString(), fileName = file.name, mime = mime)
        val message = UIMessage.user("").copy(parts = listOf(document))
        val context = TransformerContext(RuntimeEnvironment.getApplication(), mockk(), mockk(), mockk())
        val result = DocumentAsPromptTransformer.transform(context, listOf(message)).single()
        assertEquals("Original attachment must remain available", document, result.parts.filterIsInstance<UIMessagePart.Document>().single())
        assertEquals(message.id, result.id)
        return result.parts.filterIsInstance<UIMessagePart.Text>().single().text
    }

    private fun archive(name: String, entries: Map<String, String>): File = temporary.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, text) -> entry(zip, path, text) } }
    }
    private fun entry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
    }
    private fun docx(text: String) = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""
    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val block = ByteArray(8192); while (true) { val size = input.read(block); if (size < 0) break; digest.update(block, 0, size) } }
        return digest.digest()
    }
}
