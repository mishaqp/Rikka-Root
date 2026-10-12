package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsagePresentationTest {
    @Test fun `unknown and invalid reported usage stay hidden even for a known model`() {
        assertNull(contextUsagePresentation(null, "deepseek-v4-pro"))
        assertNull(contextUsagePresentation(-1, "deepseek-v4-pro"))
    }

    @Test fun `unknown registry window uses compact reported tokens without a fraction`() {
        val usage = contextUsagePresentation(214_000, "gpt-4.1")!!
        assertEquals(214_000, usage.promptTokens)
        assertEquals("214K", usage.compactTokens)
        assertNull(usage.windowTokens)
        assertNull(usage.fraction)
        assertNull(usage.filledFraction)
        assertNull(usage.percentage)
    }

    @Test fun `known registry window uses its actual capacity and last prompt usage`() {
        val usage = contextUsagePresentation(214_000, "deepseek-v4-pro")!!
        assertEquals(1_000_000, usage.windowTokens)
        assertEquals(0.214, usage.fraction!!, 0.00001)
        assertEquals(0.214f, usage.filledFraction!!, 0.00001f)
        assertEquals(21, usage.percentage)
        assertEquals(ContextUsageLevel.Normal, usage.level)
    }

    @Test fun `reported zero is distinct from unknown usage`() {
        val usage = contextUsagePresentation(0, "deepseek-v4-pro")!!
        assertEquals(0f, usage.filledFraction!!, 0f)
        assertEquals(0, usage.percentage)
        assertEquals("0", usage.compactTokens)
    }

    @Test fun `warning starts at 85 percent and full starts at the registry capacity`() {
        assertEquals(ContextUsageLevel.Normal, contextUsagePresentation(849_999, "deepseek-v4-pro")!!.level)
        assertEquals(ContextUsageLevel.Warning, contextUsagePresentation(850_000, "deepseek-v4-pro")!!.level)
        assertEquals(ContextUsageLevel.Full, contextUsagePresentation(1_000_000, "deepseek-v4-pro")!!.level)
    }

    @Test fun `overflow clamps the ring while retaining the actual count and percentage`() {
        val usage = contextUsagePresentation(1_250_000, "deepseek-v4-pro")!!
        assertEquals(1_250_000, usage.promptTokens)
        assertEquals(1f, usage.filledFraction!!, 0f)
        assertEquals(125, usage.percentage)
        assertEquals(ContextUsageLevel.Full, usage.level)
    }

    @Test fun `compact usage formats small thousands and millions consistently`() {
        assertEquals("537", contextUsagePresentation(537)!!.compactTokens)
        assertEquals("1.2K", contextUsagePresentation(1_234)!!.compactTokens)
        assertEquals("18.5K", contextUsagePresentation(18_450)!!.compactTokens)
        assertEquals("1.3M", contextUsagePresentation(1_250_000)!!.compactTokens)
    }
}
