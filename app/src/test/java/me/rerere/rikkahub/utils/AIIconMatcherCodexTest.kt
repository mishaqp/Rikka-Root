package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AIIconMatcherCodexTest {
    @Test
    fun `codex model names prefer codex icon over generic gpt icon`() {
        assertEquals("codex.svg", computeAIIconByName("GPT-5-Codex"))
        assertEquals("codex.svg", computeAIIconByName("Codex"))
        assertEquals("openai.svg", computeAIIconByName("GPT-5"))
    }
}
