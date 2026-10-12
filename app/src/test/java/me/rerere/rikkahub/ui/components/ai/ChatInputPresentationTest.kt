package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputPresentationTest {
    @Test fun `empty idle draft cannot send`() {
        val view = chatInputPresentation(loading = false, empty = true)
        assertFalse(view.sendEnabled)
        assertFalse(view.showStop)
    }

    @Test fun `empty draft during generation offers stop`() {
        val view = chatInputPresentation(loading = true, empty = true)
        assertTrue(view.sendEnabled)
        assertTrue(view.showStop)
    }

    @Test fun `a draft can still send while generation runs`() {
        for (loading in listOf(false, true)) {
            val view = chatInputPresentation(loading = loading, empty = false)
            assertTrue(view.sendEnabled)
            assertFalse(view.showStop)
        }
    }

    @Test fun `missing prompt usage is not presented as zero`() {
        assertNull(chatContextUsagePresentation(null))
        assertNull(chatContextUsagePresentation(-1))
    }

    @Test fun `reported prompt usage is preserved without a made up context ratio`() {
        assertEquals(0, chatContextUsagePresentation(0)?.promptTokens)
        assertEquals(128_123, chatContextUsagePresentation(128_123)?.promptTokens)
    }
}
