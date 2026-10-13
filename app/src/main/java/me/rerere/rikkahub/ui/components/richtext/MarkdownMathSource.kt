package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser

/** Parser-only masking keeps literal dollars intact without adding visible escapes. */
internal data class MarkdownMathSource(val parserSource: String, val dollarMask: Char?) {
    fun restoreLiteralDollars(value: String = parserSource): String =
        dollarMask?.let { value.replace(it, '$') } ?: value
}

private val htmlCodePattern = Regex(
    "<(pre|code)\\b[^>]*>[\\s\\S]*?(?:</\\1\\s*>|$)",
    RegexOption.IGNORE_CASE,
)
private val displayMathLineBreak = Regex("[ \\t]*\\r?\\n[ \\t]*")

/** Use the renderer's GFM AST, including tilde, indented and unfinished fences. */
internal fun markdownCodeRanges(
    content: String,
    deferTableMath: Boolean = false,
    includeHtmlCode: Boolean = true,
): List<IntRange> {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val ranges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE ||
            node.type == MarkdownElementTypes.CODE_BLOCK ||
            node.type == MarkdownElementTypes.CODE_SPAN ||
            (deferTableMath && node.type == GFMElementTypes.TABLE)
        ) {
            ranges += node.startOffset until node.endOffset
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(tree)
    if (includeHtmlCode) htmlCodePattern.findAll(content).forEach { ranges += it.range }
    return ranges.sortedBy { it.first }
}

internal data class MarkdownHtmlCodeSource(val parserSource: String, val codeBodies: Map<String, String>) {
    fun restoreCodeBodies(html: String): String {
        var restored = html
        codeBodies.forEach { (token, body) -> restored = restored.replace(token, body) }
        return restored
    }
}

/** Raw HTML code needs protection too: a GFM parser treats its body as inline Markdown. */
internal fun protectMarkdownHtmlCode(content: String): MarkdownHtmlCodeSource {
    val nativeCode = markdownCodeRanges(content, includeHtmlCode = false)
    val bodies = mutableMapOf<String, String>()
    val pattern = Regex("(<(pre|code)\\b[^>]*>)([\\s\\S]*?)(</\\2\\s*>|$)", RegexOption.IGNORE_CASE)
    val parserSource = pattern.replace(content) { match ->
        if (nativeCode.any { match.range.first in it }) {
            match.value
        } else {
            var token = "RIKKA_CODE_${content.hashCode().toUInt()}_${bodies.size}_END"
            while (token in content) token += "_"
            bodies[token] = match.groupValues[3]
            match.groupValues[1] + token + match.groupValues[4]
        }
    }
    return MarkdownHtmlCodeSource(parserSource, bodies)
}

internal fun String.isMarkdownEscaped(offset: Int): Boolean {
    var backslashes = 0
    var index = offset - 1
    while (index >= 0 && this[index--] == '\\') backslashes++
    return backslashes % 2 != 0
}

/** Convert explicit delimiters once, using original offsets for every code exclusion. */
internal fun prepareMarkdownMath(
    content: String,
    enableDollarLatex: Boolean,
    deferTableMath: Boolean = false,
): MarkdownMathSource {
    // Native table cells parse their original Markdown separately. Converting their
    // explicit delimiters here would make them look like disabled dollar delimiters.
    val protected = markdownCodeRanges(content, deferTableMath)
    val mask = if (enableDollarLatex) null else ('\uE000'..'\uF8FF').first { it !in content }
    val result = StringBuilder(content.length)
    var offset = 0
    while (offset < content.length) {
        val code = protected.firstOrNull { offset in it }
        if (code != null) {
            val end = code.last + 1
            result.append(content, offset, end)
            offset = end
            continue
        }
        val opening = content.getOrNull(offset + 1)
        if (content[offset] == '\\' && !content.isMarkdownEscaped(offset) &&
            (opening == '(' || opening == '[')
        ) {
            val closing = if (opening == '(') "\\)" else "\\]"
            var end = content.indexOf(closing, offset + 2)
            while (end >= 0 && content.isMarkdownEscaped(end)) {
                end = content.indexOf(closing, end + 2)
            }
            if (end >= 0 && protected.none { it.first < end + 2 && it.last >= offset }) {
                val formula = content.substring(offset + 2, end)
                if (opening == '[') {
                    result.append("$$").append(formula.trim().replace(displayMathLineBreak, " ")).append("$$")
                } else {
                    result.append('$').append(formula).append('$')
                }
                offset = end + 2
                continue
            }
        }
        result.append(if (content[offset] == '$' && mask != null) mask else content[offset])
        offset++
    }
    return MarkdownMathSource(result.toString(), mask)
}
