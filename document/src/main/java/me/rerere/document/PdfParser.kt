package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

data class PdfRangeResult(val text: String, val pages: Int, val limited: Boolean = false)

object PdfParser {
    /** Native-resource cleanup adapted from ExTV/rikkahub-agent (AGPL v3). */
    fun parseRange(
        file: File,
        pageFrom: Int = 1,
        pageTo: Int? = null,
        checkActive: () -> Unit = {},
        maxChars: Int = Int.MAX_VALUE,
    ): PdfRangeResult {
        require(pageFrom >= 1 && (pageTo == null || pageTo >= pageFrom)) { "Некорректный диапазон страниц PDF." }
        checkActive()
        val document = PDFDocument.openDocument(file.absolutePath)
        try {
            require(document.isPDF) { "Файл не является PDF-документом." }
            require(!document.needsPassword()) { "PDF защищён паролем. Откройте незашифрованную копию документа." }
            val pages = document.countPages()
            val last = pageTo ?: pages
            require(pageFrom <= pages && last <= pages) { "Диапазон страниц выходит за пределы PDF-документа." }
            val result = StringBuilder()
            for (number in pageFrom..last) {
                // A small file can expand to hundreds of MB of text; stop before that exhausts memory.
                if (result.length > maxChars) return PdfRangeResult(result.substring(0, maxChars + 1), pages, limited = true)
                checkActive()
                val page = document.loadPage(number - 1)
                try {
                    val structuredText = page.toStructuredText()
                    try {
                        checkActive()
                        result.append("---Страница $number:\n")
                        result.append(structuredText.asText())
                        result.appendLine()
                    } finally {
                        structuredText.destroy()
                    }
                } finally {
                    page.destroy()
                }
                checkActive()
            }
            if (result.length > maxChars) return PdfRangeResult(result.substring(0, maxChars + 1), pages, limited = true)
            return PdfRangeResult(result.toString(), pages)
        } finally {
            document.destroy()
        }
    }

    fun parserPdf(file: File): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val pages = document.countPages()
        val result = StringBuilder()
        for (i in 0 until pages) {
            val page = document.loadPage(i).toStructuredText()
            result.append("---")
            result.append("Page ${i + 1}:\n")
            result.append(page.asText())
            result.appendLine()
        }
        return result.toString()
    }
}
