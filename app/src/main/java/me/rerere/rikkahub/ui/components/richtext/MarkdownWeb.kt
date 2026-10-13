package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(
    context: Context,
    markdown: String,
    colorScheme: ColorScheme,
    displayOptions: MarkdownDisplayOptions? = null,
): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }
    val enableLatex = displayOptions?.enableLatexRendering ?: true
    val source = if (enableLatex) {
        prepareMarkdownMath(markdown, displayOptions?.enableDollarLatex ?: true)
    } else {
        MarkdownMathSource(markdown, null)
    }
    val protected = protectMarkdownHtmlCode(source.parserSource)

    return htmlTemplate
        .replace("{{MARKDOWN_BASE64}}", protected.parserSource.base64Encode())
        .replace("{{HTML_CODE_BODIES_BASE64}}", Json.encodeToString(protected.codeBodies).base64Encode())
        .replace("{{ENABLE_LATEX}}", enableLatex.toString())
        .replace("{{DOLLAR_MASK_CODE}}", (source.dollarMask?.code ?: -1).toString())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}
