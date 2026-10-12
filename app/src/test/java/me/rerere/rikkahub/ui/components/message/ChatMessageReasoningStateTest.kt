package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ChatMessageReasoningStateTest {
    private class FixedClock(private val value: Instant) : Clock { override fun now(): Instant = value }

    @Test fun elapsedUsesPartTimestampsAndInjectableClock() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = FixedClock(start + 7.seconds)
        assertEquals(7.seconds, reasoningElapsed(UIMessagePart.Reasoning("running", start, null), clock))
        assertEquals(3.seconds, reasoningElapsed(UIMessagePart.Reasoning("done", start, start + 3.seconds), clock))
    }

    @Test fun settingsIndependentlyControlPreviewAndFinish() {
        assertEquals(ReasoningCardState.Collapsed,
            nextReasoningCardState(ReasoningCardState.Collapsed, loading = true, showThinkingContent = false, autoCloseThinking = true))
        assertEquals(ReasoningCardState.Preview,
            nextReasoningCardState(ReasoningCardState.Collapsed, loading = true, showThinkingContent = true, autoCloseThinking = true))
        assertEquals(ReasoningCardState.Collapsed,
            nextReasoningCardState(ReasoningCardState.Preview, loading = true, showThinkingContent = false, autoCloseThinking = true))
        assertEquals(ReasoningCardState.Expanded,
            nextReasoningCardState(ReasoningCardState.Expanded, loading = true, showThinkingContent = false, autoCloseThinking = true))
        assertEquals(ReasoningCardState.Collapsed,
            nextReasoningCardState(ReasoningCardState.Preview, loading = false, showThinkingContent = true, autoCloseThinking = true))
        assertEquals(ReasoningCardState.Expanded,
            nextReasoningCardState(ReasoningCardState.Preview, loading = false, showThinkingContent = false, autoCloseThinking = false))
        assertEquals(ReasoningCardState.Collapsed,
            nextReasoningCardState(ReasoningCardState.Collapsed, loading = false, showThinkingContent = false, autoCloseThinking = false))
    }
}
