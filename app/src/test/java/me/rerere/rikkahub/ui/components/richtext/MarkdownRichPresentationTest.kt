package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownRichPresentationTest {
    @Test fun streamFadeTargetsOnlyAppendedCharacters() {
        assertEquals(5..8, appendedMarkdownRange("Ответ", "Ответ ещё"))
        assertEquals(0..4, appendedMarkdownRange("", "Ответ"))
        assertNull(appendedMarkdownRange("Ответ", "Ответ"))
        assertNull(appendedMarkdownRange("Ответ", "От"))
        assertNull(appendedMarkdownRange("Ответ", "Другое"))
    }
    @Test fun csvPreservesQuotesCommasNewlinesAndEmptyCells() {
        assertEquals("A,B,C\n\"a,b\",\"say \"\"yes\"\"\",\"first\nsecond\"\n,,",
            buildMarkdownTableCsv(listOf("A", "B", "C"), listOf(listOf("a,b", "say \"yes\"", "first\nsecond"), listOf("", "", ""))))
    }

    @Test fun htmlTableCopyEscapesPipesWithoutChangingCellText() {
        assertEquals("| A | B |\n| --- | --- |\n| a\\|b | two<br>lines |",
            buildMarkdownTableSource(listOf("A", "B"), listOf(listOf("a|b", "two\nlines"))))
    }
}
