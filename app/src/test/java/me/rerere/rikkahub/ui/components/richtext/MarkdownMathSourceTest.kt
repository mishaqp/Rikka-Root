package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathSourceTest {
    @Test fun dollarDelimitersCanBeDisabledWhileExplicitDelimitersStillRender() {
        val original = "Dollar ${'$'}x${'$'} and ${'$'}${'$'}y${'$'}${'$'}, explicit \\(z\\) and \\[a\nb\\]"
        val source = prepareMarkdownMath(original, enableDollarLatex = false)
        assertEquals("Dollar ${'$'}x${'$'} and ${'$'}${'$'}y${'$'}${'$'}, explicit ${'$'}z${'$'} and ${'$'}${'$'}a b${'$'}${'$'}", source.restoreLiteralDollars())
        val math = mathNodes(source.parserSource)
        assertTrue(math.any { source.parserSource.substring(it.startOffset, it.endOffset).contains("z") })
        assertTrue(math.any { source.parserSource.substring(it.startOffset, it.endOffset).contains("a b") })
        assertFalse(math.any { source.parserSource.substring(it.startOffset, it.endOffset).contains("x") })
        assertFalse(math.any { source.parserSource.substring(it.startOffset, it.endOffset).contains("y") })
    }

    @Test fun enabledDollarMathRetainsTheDefaultGfmBehavior() {
        val text = "Formula ${'$'}x+1${'$'}"
        assertEquals(text, prepareMarkdownMath(text, true).parserSource)
        assertTrue(mathNodes(prepareMarkdownMath(text, true).parserSource).isNotEmpty())
    }

    @Test fun codeStaysVerbatimAfterEarlierExplicitFormulaChangesOffsets() {
        listOf(
            "```kotlin\n\\[code\\] ${'$'}code${'$'}\n```",
            "~~~kotlin\n\\[code\\] ${'$'}code${'$'}\n~~~",
            "``\\[code\\] ${'$'}code${'$'}``",
            "    \\[code\\] ${'$'}code${'$'}",
            "<pre><code>\\[code\\] ${'$'}code${'$'}</code></pre>",
        ).forEach { code ->
            val source = prepareMarkdownMath("\\(first\\)\n\n$code\n\n\\[last\\]", false)
            assertTrue(source.parserSource.contains(code))
            assertTrue(source.restoreLiteralDollars().endsWith("${'$'}${'$'}last${'$'}${'$'}"))
        }
    }

    @Test fun unfinishedStreamingFencesKeepExplicitAndDollarTokensAsCode() {
        listOf("```kotlin\n\\(code\\)\n\n${'$'}code${'$'}", "~~~kotlin\n\\[code\\]\n\n${'$'}code${'$'}").forEach { text ->
            assertEquals(text, prepareMarkdownMath(text, false).parserSource)
        }
    }

    @Test fun escapedDollarsAndEscapedExplicitOpenersRemainLiteral() {
        val text = "escaped \\${'$'}x\\${'$'} and \\\\(literal\\\\)"
        val source = prepareMarkdownMath(text, false)
        assertEquals(text, source.restoreLiteralDollars())
        assertTrue(mathNodes(source.parserSource).isEmpty())
        assertFalse(source.parserSource.contains("${'$'}literal${'$'}"))
    }

    @Test fun htmlAndHtmlTableCellsHonorTheDollarOptionAndKeepCodeAndCitations() {
        val html = generateMarkdownHtml("Dollar ${'$'}x${'$'} explicit \\(z\\)<br> <code>\\(code\\) ${'$'}code${'$'}</code> [citation](1)", false)
        val document = Jsoup.parse(html)
        assertTrue(document.body().text().contains("${'$'}x${'$'}"))
        assertEquals("z", document.select("span.math").single().text())
        assertEquals("\\(code\\) ${'$'}code${'$'}", document.select("code").single().text())
        assertEquals("1", document.select("a").single().attr("href"))
        val table = "| Value |\n| --- |\n| ${'$'}x${'$'} \\(z\\) [citation](1) |"
        val tableDocument = Jsoup.parse(generateMarkdownHtml(table, false))
        assertTrue(tableDocument.select("td").text().contains("${'$'}x${'$'}"))
        assertEquals("z", tableDocument.select("td span.math").single().text())
        assertEquals("1", tableDocument.select("td a").single().attr("href"))
    }

    @Test fun nativeTablesDeferMathToTheOriginalCellSource() {
        val table = "| Value |\n| --- |\n| ${'$'}x${'$'} \\(z\\) |"
        val outer = prepareMarkdownMath(table, false, deferTableMath = true)
        assertEquals(table, outer.parserSource)
        val cell = prepareMarkdownMath("${'$'}x${'$'} \\(z\\)", false)
        assertEquals(1, mathNodes(cell.parserSource).size)
    }

    @Test fun htmlCodeProtectionLeavesMarkdownCodeFencesAndSpansVerbatim() {
        val fenced = "```html\n<code><b>${'$'}x${'$'}</b></code>\n```"
        assertEquals(fenced, protectMarkdownHtmlCode(fenced).parserSource)
        val inline = "`<code><b>${'$'}x${'$'}</b></code>`"
        assertEquals(inline, protectMarkdownHtmlCode(inline).parserSource)
        val code = Jsoup.parse(generateMarkdownHtml(fenced, false)).select("pre code").single()
        assertEquals("<code><b>${'$'}x${'$'}</b></code>", code.text())
        assertTrue(code.children().isEmpty())
    }

    private fun mathNodes(source: String): List<ASTNode> {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val result = mutableListOf<ASTNode>()
        fun visit(node: ASTNode) {
            if (node.type == GFMElementTypes.INLINE_MATH || node.type == GFMElementTypes.BLOCK_MATH) result += node
            node.children.forEach(::visit)
        }
        visit(tree)
        return result
    }
}
