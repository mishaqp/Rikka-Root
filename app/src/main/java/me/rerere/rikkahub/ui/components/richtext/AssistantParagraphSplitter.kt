package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private val assistantListItem = Regex("^\\s*(?:[-*+]\\s|\\d+[.)]\\s)")
private val assistantHeading = Regex("^#{1,6}\\s")
private val assistantDetailsTag = Regex("<(/?)details\\b[^>]*>", RegexOption.IGNORE_CASE)

/** Moru paragraph bubbles, with structure boundaries from Rikka's actual GFM parser. */
fun splitAssistantParagraphs(text: String): List<String> {
    if (text.isBlank()) return listOf(text)
    val structure = assistantParagraphStructure(text)
    // Each bubble owns a Markdown document. Reference definitions must remain
    // in the same document as their links, including definitions inside lists.
    if (structure.hasDocumentReferences) return listOf(text)
    val protected = structure.protectedRanges
    val lines = text.split('\n')
    val chunks = mutableListOf<String>()
    val current = mutableListOf<String>()
    var offset = 0
    fun flush() {
        current.joinToString("\n").trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        current.clear()
    }
    lines.forEachIndexed { index, line ->
        val lineStart = offset
        offset += line.length + 1
        val next = lines.asSequence().drop(index + 1).firstOrNull { it.isNotBlank() }
        val continuesIndentedBlock = next?.let { it.startsWith("    ") || it.startsWith('\t') } == true
        if (line.isBlank() && protected.none { lineStart in it } && !continuesIndentedBlock) {
            flush()
        } else {
            current += line
        }
    }
    flush()
    if (chunks.size < 2) return listOf(text)
    val merged = mutableListOf<String>()
    chunks.forEach { chunk ->
        val previous = merged.lastOrNull()
        val headingOnly = previous != null && !previous.contains('\n') && assistantHeading.containsMatchIn(previous)
        val adjacentLists = previous != null && assistantListItem.containsMatchIn(previous) && assistantListItem.containsMatchIn(chunk)
        if (headingOnly || adjacentLists) {
            merged[merged.lastIndex] = "$previous\n\n$chunk"
        } else {
            merged += chunk
        }
    }
    return merged
}

private data class AssistantParagraphStructure(
    val protectedRanges: List<IntRange>,
    val hasDocumentReferences: Boolean,
)

private fun assistantParagraphStructure(text: String): AssistantParagraphStructure {
    val codes = markdownCodeRanges(text)
    val protected = codes.toMutableList()
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    fun hasReferenceDefinition(node: ASTNode): Boolean =
        node.type == MarkdownElementTypes.LINK_DEFINITION || node.children.any(::hasReferenceDefinition)
    val hasDocumentReferences = hasReferenceDefinition(tree)
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.ORDERED_LIST ||
            node.type == MarkdownElementTypes.UNORDERED_LIST ||
            node.type == MarkdownElementTypes.BLOCK_QUOTE ||
            node.type == MarkdownElementTypes.HTML_BLOCK ||
            node.type == GFMElementTypes.TABLE
        ) {
            protected += node.startOffset until node.endOffset
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(tree)

    var detailsStart = -1
    var detailsDepth = 0
    assistantDetailsTag.findAll(text).forEach { tag ->
        if (codes.none { tag.range.first in it }) {
            if (tag.groupValues[1].isEmpty()) {
                if (detailsDepth++ == 0) detailsStart = tag.range.first
            } else if (detailsDepth > 0 && --detailsDepth == 0) {
                protected += detailsStart..tag.range.last
                detailsStart = -1
            }
        }
    }
    if (detailsStart >= 0) protected += detailsStart until text.length

    var offset = 0
    var mathStart = -1
    var mathClose: String? = null
    while (offset < text.length) {
        val code = codes.firstOrNull { offset in it }
        if (code != null) {
            offset = code.last + 1
            continue
        }
        if (!text.isMarkdownEscaped(offset)) {
            if (mathClose != null && text.startsWith(mathClose, offset)) {
                protected += mathStart until offset + mathClose.length
                offset += mathClose.length
                mathClose = null
                continue
            }
            if (mathClose == null) {
                mathClose = when {
                    text.startsWith("$$", offset) -> "$$"
                    text.startsWith("\\[", offset) -> "\\]"
                    else -> null
                }
                if (mathClose != null) {
                    mathStart = offset
                    offset += 2
                    continue
                }
            }
        }
        offset++
    }
    if (mathClose != null) protected += mathStart until text.length
    return AssistantParagraphStructure(protected, hasDocumentReferences)
}
