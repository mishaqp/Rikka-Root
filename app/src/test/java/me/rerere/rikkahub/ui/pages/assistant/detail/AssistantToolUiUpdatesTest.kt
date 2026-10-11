package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AssistantToolUiUpdatesTest {
    @Test fun `permission result preserves independently enabled tools and unrelated settings`() {
        val before = Assistant(name = "До", localTools = listOf(LocalToolOption.TimeInfo))
        val current = before.copy(name = "Новое имя", localTools = before.localTools + LocalToolOption.Root,
            tokenBudgetHardCap = 999)
        val requested = before.copy(localTools = before.localTools + LocalToolOption.Calendar)
        val merged = mergeToolUiChanges(current, before, requested)
        assertEquals(setOf(LocalToolOption.TimeInfo, LocalToolOption.Root, LocalToolOption.Calendar), merged.localTools.toSet())
        assertEquals("Новое имя", merged.name)
        assertEquals(999, merged.tokenBudgetHardCap)
    }

    @Test fun `bulk disable removes selected tools without discarding concurrent additions`() {
        val before = Assistant(localTools = listOf(LocalToolOption.Root, LocalToolOption.Termux))
        val current = before.copy(localTools = before.localTools + LocalToolOption.TimeInfo)
        val requested = before.copy(localTools = emptyList())
        assertEquals(listOf(LocalToolOption.TimeInfo), mergeToolUiChanges(current, before, requested).localTools)
    }

    @Test fun `detail token caps and concurrency save only fields changed in UI`() {
        val before = Assistant(tokenBudgetSoftCap = 100, tokenBudgetHardCap = 200)
        val current = before.copy(tokenBudgetHardCap = 500)
        val requested = before.copy(tokenBudgetSoftCap = null, subAgentConcurrencyLimit = 3)
        val merged = mergeToolUiChanges(current, before, requested)
        assertNull(merged.tokenBudgetSoftCap)
        assertEquals(500, merged.tokenBudgetHardCap)
        assertEquals(3, merged.subAgentConcurrencyLimit)
    }

    @Test fun `a no-op UI update preserves all current data`() {
        val before = Assistant()
        val current = before.copy(name = "Сохранено", localTools = listOf(LocalToolOption.Contacts))
        assertEquals(current, mergeToolUiChanges(current, before, before))
    }
}
