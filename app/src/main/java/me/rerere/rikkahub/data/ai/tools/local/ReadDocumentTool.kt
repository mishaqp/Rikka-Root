package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.zip.ZipFile

private const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
private const val MAX_DOCUMENT_EXPANDED_BYTES = 64L * 1024 * 1024
private const val MAX_DOCUMENT_ENTRIES = 10_000

internal data class ReadDocumentExtraction(val text: String, val pages: Int? = null)

internal fun interface ReadDocumentBackend {
    suspend fun extract(file: File, extension: String, pageFrom: Int?, pageTo: Int?): ReadDocumentExtraction
}

internal fun readDocumentTool(
    @Suppress("UNUSED_PARAMETER")
    context: Context,
    access: LocalFileAccess,
    backend: ReadDocumentBackend? = null,
): Tool = Tool(
    name = "read_document",
    description = "Извлекает текст PDF, DOCX, PPTX или EPUB через разрешённый файловый доступ. " +
        "max_chars: по умолчанию 20000, максимум 65536 символов. page_from и page_to: " +
        "страницы PDF с 1 включительно. pages — общее число страниц PDF, для остальных форматов null. " +
        "total_chars — размер текста выбранного диапазона до обрезки. Документ до 32 МиБ.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("path", buildJsonObject { put("type", "string"); put("description", "Путь к документу или URI выбранной папки SAF.") })
        put("max_chars", buildJsonObject { put("type", "integer"); put("default", 20000); put("minimum", 1); put("maximum", 65536) })
        for (parameter in listOf("page_from", "page_to")) put(parameter, buildJsonObject {
            put("type", "integer"); put("minimum", 1); put("description", "Номер страницы PDF с 1 включительно.")
        })
    }, listOf("path")) },
    needsApproval = { false },
    execute = { input -> deviceToolResult(FileToolOperation.READ) { withContext(Dispatchers.IO) {
        val arguments = requireNotNull(input as? JsonObject) { "Параметры должны быть объектом." }
        val path = fileArgument(arguments, "path")
        val maxChars = fileInteger(arguments, "max_chars", 20_000, 1..65_536)
        val pageFrom = if ("page_from" in arguments) fileInteger(arguments, "page_from", 1, 1..Int.MAX_VALUE) else null
        val pageTo = if ("page_to" in arguments) fileInteger(arguments, "page_to", 1, 1..Int.MAX_VALUE) else null
        require(pageTo == null || pageTo >= (pageFrom ?: 1)) { "Конец диапазона страниц должен быть не меньше начала." }
        val source = access.resolve(path)
        require(access.exists(source)) { "Документ не найден. Проверьте путь." }
        require(!access.isDirectory(source)) { "Укажите файл документа, а не папку." }
        val extension = access.name(source).substringAfterLast('.', "").lowercase()
        require(extension in setOf("pdf", "docx", "pptx", "epub")) { "Поддерживаются только PDF, DOCX, PPTX и EPUB." }
        require(extension == "pdf" || (pageFrom == null && pageTo == null)) { "Диапазон страниц поддерживается только для PDF." }
        require(access.size(source) <= MAX_DOCUMENT_BYTES) { "Документ превышает ограничение 32 МиБ." }
        val scratch = access.resolve("/scratch") as LocalFileSource.Local
        val snapshot = LocalFileSource.Local(Files.createTempFile(access.checkFile(scratch.file).toPath(), ".read-document-", ".$extension").toFile())
        var failure: Throwable? = null
        try {
            access.openInput(source).use { inputStream -> access.openOutput(snapshot).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    access.resolve(path) // An already-open SAF descriptor does not prove a live grant.
                    access.checkFile(snapshot.file, write = true)
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_DOCUMENT_BYTES) { "Документ превышает ограничение 32 МиБ." }
                    output.write(buffer, 0, count)
                }
            } }
            currentCoroutineContext().ensureActive()
            access.resolve(path)
            if (extension != "pdf") preflightDocumentArchive(access.checkFile(snapshot.file), extension)
            currentCoroutineContext().ensureActive()
            access.resolve(path)
            val extracted = try {
                (backend ?: defaultReadDocumentBackend).extract(access.checkFile(snapshot.file), extension, pageFrom, pageTo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                throw IllegalStateException("Не удалось загрузить модуль чтения PDF на этом устройстве.")
            } catch (error: Exception) {
                if (error is IllegalArgumentException && error.message?.startsWith("Диапазон страниц") == true ||
                    error is IllegalArgumentException && error.message?.startsWith("PDF защищён") == true) throw error
                throw IllegalStateException("Не удалось извлечь текст документа. Проверьте формат и целостность файла.")
            }
            currentCoroutineContext().ensureActive()
            access.resolve(path) // Do not release text if SAF/shared access was revoked during parsing.
            checkDocumentParserOutcome(extracted.text)
            buildJsonObject {
                put("text", extracted.text.take(maxChars))
                put("total_chars", extracted.text.length)
                put("truncated", extracted.text.length > maxChars)
                put("pages", if (extension == "pdf") extracted.pages?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    require(access.delete(snapshot)) { "Не удалось удалить временную копию документа. Проверьте свободное место." }
                } catch (cleanup: Exception) {
                    val original = failure
                    if (original != null) original.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    } } },
)

private val defaultReadDocumentBackend = ReadDocumentBackend { file, extension, pageFrom, pageTo ->
    val coroutine = currentCoroutineContext()
    when (extension) {
        "pdf" -> PdfParser.parseRange(file, pageFrom ?: 1, pageTo) { coroutine.ensureActive() }
            .let { ReadDocumentExtraction(it.text, it.pages) }
        "docx" -> ReadDocumentExtraction(DocxParser.parse(file))
        "pptx" -> ReadDocumentExtraction(PptxParser.parse(file))
        "epub" -> ReadDocumentExtraction(EpubParser.parse(file))
        else -> error("Неподдерживаемый формат документа.")
    }
}

private fun checkDocumentParserOutcome(text: String) {
    val knownFailure = Regex("(?m)^(?:Error parsing (?:DOCX file|document XML|PPTX file|slide XML|notes XML|EPUB file):|" +
        "Unable to find document content in DOCX file$|No slides found in PPTX file$|" +
        "Unable to (?:find|read) OPF file in EPUB$|No readable content found in EPUB file$)")
    require(!knownFailure.containsMatchIn(text)) { "Не удалось прочитать содержимое документа. Проверьте формат и целостность файла." }
}

/** Opt-in validation for this tool; attachment parsers retain their previous behavior. */
private suspend fun preflightDocumentArchive(file: File, extension: String) {
    val coroutine = currentCoroutineContext()
    var expanded = 0L
    try {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var count = 0
            var hasDocument = false
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                coroutine.ensureActive()
                require(++count <= MAX_DOCUMENT_ENTRIES) { "В документе слишком много файлов: максимум 10000." }
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                hasDocument = hasDocument || when (extension) {
                    "docx" -> entry.name == "word/document.xml"
                    "pptx" -> entry.name.matches(Regex("ppt/slides/slide\\d+\\.xml"))
                    "epub" -> entry.name == "META-INF/container.xml"
                    else -> false
                }
                zip.getInputStream(entry).use { raw ->
                    val input = object : FilterInputStream(raw) {
                        private fun account(size: Int): Int {
                            coroutine.ensureActive()
                            if (size > 0) expanded += size
                            require(expanded <= MAX_DOCUMENT_EXPANDED_BYTES) { "Распакованное содержимое документа превышает 64 МиБ." }
                            return size
                        }
                        override fun read(): Int = `in`.read().also { account(if (it < 0) 0 else 1) }
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = account(`in`.read(bytes, offset, length))
                        override fun read(bytes: ByteArray): Int = read(bytes, 0, bytes.size)
                    }
                    val isXml = entry.name.endsWith(".xml", true) || entry.name.endsWith(".xhtml", true) ||
                        (extension == "epub" && entry.name.endsWith(".html", true)) || entry.name.endsWith(".opf", true)
                    if (isXml) {
                        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
                        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                        parser.setInput(input, null)
                        while (parser.eventType != XmlPullParser.END_DOCUMENT) { coroutine.ensureActive(); parser.next() }
                    }
                    // Count even trailing bytes that an XML parser may leave unread.
                    while (input.read(buffer) >= 0) coroutine.ensureActive()
                }
            }
            require(hasDocument) { "В архиве документа отсутствует основное содержимое." }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (_: Exception) {
        throw IllegalArgumentException("Документ повреждён или содержит некорректный XML. Проверьте файл.")
    }
}
