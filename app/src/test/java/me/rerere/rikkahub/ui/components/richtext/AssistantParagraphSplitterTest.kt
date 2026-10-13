package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantParagraphSplitterTest {
    @Test fun plainParagraphsAndUnsplitSource() {
        assertEquals(listOf("one", "two", "three"), splitAssistantParagraphs("one\n\n\n\ntwo\n\nthree"))
        listOf("", "  ", "  hi  ", "one\ntwo").forEach { assertEquals(listOf(it), splitAssistantParagraphs(it)) }
    }

    @Test fun fencesKeepBlankLinesAndStreamingTail() {
        listOf(
            "```kotlin\nval a = 1\n\nval b = 2\n```",
            "~~~kotlin\nval a = 1\n\nval b = 2\n~~~",
            "````\n```\n\nstill code\n````",
            "```\n```not-a-close\n\nstill code\n```",
        ).forEach { block -> assertEquals(listOf("intro", block, "end"), splitAssistantParagraphs("intro\n\n$block\n\nend")) }
        listOf("```kotlin\nval a = 1\n\nval b", "~~~kotlin\nval a = 1\n\nval b").forEach { block ->
            assertEquals(listOf("intro", block), splitAssistantParagraphs("intro\n\n$block"))
        }
    }

    @Test fun displayMathKeepsBlankLinesAndStreamingTail() {
        listOf("$$\na = b\n\nc = d\n$$", "\\[\na = b\n\nc = d\n\\]").forEach { block ->
            assertEquals(listOf("before", block, "after"), splitAssistantParagraphs("before\n\n$block\n\nafter"))
        }
        listOf("$$\na = b\n\nc =", "\\[\na = b\n\nc =").forEach { block ->
            assertEquals(listOf("before", block), splitAssistantParagraphs("before\n\n$block"))
        }
        assertEquals(listOf("escaped \\$$", "ordinary paragraph"), splitAssistantParagraphs("escaped \\$$\n\nordinary paragraph"))
    }

    @Test fun detailsStayWholeIncludingNestedAndUnclosedBlocks() {
        val block = "<details>\n<summary>more</summary>\n\nfirst\n\n<details>\n\ninner\n\n</details>\n\nlast\n</details>"
        assertEquals(listOf("intro", block, "end"), splitAssistantParagraphs("intro\n\n$block\n\nend"))
        val unfinished = "<details>\n<summary>more</summary>\n\nfirst\n\nstreaming"
        assertEquals(listOf("intro", unfinished), splitAssistantParagraphs("intro\n\n$unfinished"))
    }

    @Test fun listsHeadingsAndIndentedContinuationsKeepTheirMeaning() {
        val list = "1. first\n\n2. second\n\n3. third"
        assertEquals(listOf("Steps:", list), splitAssistantParagraphs("Steps:\n\n$list"))
        assertEquals(listOf("## Title\n\nbody", "tail"), splitAssistantParagraphs("## Title\n\nbody\n\ntail"))
        val code = "code:\n\n    line a\n\n    line b"
        assertEquals(listOf(code, "end"), splitAssistantParagraphs("$code\n\nend"))
        val nested = "- first\n\n    continuation\n\n    - child\n\n- second"
        assertEquals(listOf(nested, "end"), splitAssistantParagraphs("$nested\n\nend"))
    }

    @Test fun codeTokensCannotOpenDetailsOrMathOutsideCode() {
        val code = "```text\n<details>\n$$\n\\[\n```"
        assertEquals(listOf(code, "first", "second"), splitAssistantParagraphs("$code\n\nfirst\n\nsecond"))
        assertEquals(listOf("`<details>`", "first", "second"), splitAssistantParagraphs("`<details>`\n\nfirst\n\nsecond"))
    }

    @Test fun tablesAndBlockquotesKeepTheOriginalMarkdown() {
        val table = "| Key | Value |\n| --- | --- |\n| A | B |"
        val quote = "> first\n>\n> second"
        assertEquals(listOf("before", table, quote, "after"), splitAssistantParagraphs("before\n\n$table\n\n$quote\n\nafter"))
    }

    @Test fun documentWideReferenceDefinitionsStayWithTheirLinks() {
        listOf(
            "HTML <b>header</b>\n\n[Open][target]\n\n[target]: https://example.com",
            "[Open][target]\n\n[target]:\n  https://example.com \"Title\"\n\nend",
            "Footnote [^note]\n\n[^note]: Explanation\n\nend",
        ).forEach { text -> assertEquals(listOf(text), splitAssistantParagraphs(text)) }
        val code = "```text\n[target]: https://example.com\n```"
        assertEquals(listOf("intro", code, "tail"), splitAssistantParagraphs("intro\n\n$code\n\ntail"))
    }
}
