package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.MAX_DOCUMENT_BYTES
import me.rerere.rikkahub.data.ai.tools.local.MAX_PDF_TEXT_CHARS
import me.rerere.rikkahub.data.ai.tools.local.ReadDocumentExtraction
import me.rerere.rikkahub.data.ai.tools.local.extractAttachmentDocument
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    // 上传文件保存在 filesDir/upload 下, 该目录通过 proot 挂载到 workspace 的 /upload
    // 返回文件在 workspace 内的绝对路径, 便于 AI 用 workspace 工具直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }

    private suspend fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[Не удалось прочитать вложение: некорректный адрес файла.]"
        if (!file.exists() || !file.isFile) return "[Не удалось прочитать вложение: файл не найден.]"
        return try {
            currentCoroutineContext().ensureActive()
            require(file.length() <= MAX_DOCUMENT_BYTES) { "Документ превышает ограничение 32 МиБ. Исходный файл сохранён." }
            val extension = when (document.mime) {
                "application/pdf" -> "pdf"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                "application/epub+zip" -> "epub"
                else -> null
            }
            val extracted = if (extension == null) readPlainText(file) else extractAttachmentDocument(file, extension)
            currentCoroutineContext().ensureActive()
            var end = minOf(extracted.text.length, MAX_PDF_TEXT_CHARS)
            if (end < extracted.text.length && end > 0 && extracted.text[end - 1].isHighSurrogate() &&
                extracted.text[end].isLowSurrogate()) end--
            val limited = extracted.limited || end < extracted.text.length
            extracted.text.substring(0, end) + if (limited) {
                "\n[Текст вложения обрезан до $MAX_PDF_TEXT_CHARS символов. Исходный файл сохранён. Для полного чтения используйте меньшие части или диапазоны страниц.]"
            } else ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            val detail = error.message.orEmpty().takeIf { message ->
                listOf("Документ ", "Распакованное содержимое ", "В документе ", "В архиве документа ",
                    "PDF защищён ", "Диапазон страниц ", "Файл не является PDF").any(message::startsWith)
            } ?: "Проверьте формат и целостность файла."
            "[Не удалось прочитать вложение: $detail Исходный файл сохранён.]"
        } catch (_: LinkageError) {
            "[Не удалось загрузить модуль чтения PDF. Исходный файл сохранён.]"
        } catch (_: Exception) {
            "[Не удалось извлечь текст вложения. Проверьте формат и целостность файла. Исходный файл сохранён.]"
        }
    }

    private suspend fun readPlainText(file: File): ReadDocumentExtraction {
        val result = StringBuilder()
        file.reader().use { reader ->
            val buffer = CharArray(8192)
            while (result.length <= MAX_PDF_TEXT_CHARS) {
                currentCoroutineContext().ensureActive()
                val size = reader.read(buffer, 0, minOf(buffer.size, MAX_PDF_TEXT_CHARS + 1 - result.length))
                if (size < 0) break
                result.append(buffer, 0, size)
            }
        }
        return ReadDocumentExtraction(result.toString(), limited = result.length > MAX_PDF_TEXT_CHARS)
    }
}
