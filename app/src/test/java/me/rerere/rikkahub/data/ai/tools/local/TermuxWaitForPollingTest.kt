package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxWaitForPollingTest {
    @Test fun `wait_for does not finish just because the screen is temporarily stable`() {
        val samples = listOf(PaneSample(0, "connecting..."), PaneSample(1000, "connecting..."))
        assertEquals(PollResult.Continue,
            evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = "password:"))
    }

    @Test fun `unmatched wait_for at the deadline reports timeout instead of settled`() {
        val samples = listOf(PaneSample(0, "connecting..."), PaneSample(20_000, "connecting..."))
        assertEquals(PollResult.Reason.TIMEOUT,
            (evaluatePoll(samples, settleMs = 600, timeoutMs = 20_000, waitFor = "password:") as PollResult.Done).reason)
    }
}
