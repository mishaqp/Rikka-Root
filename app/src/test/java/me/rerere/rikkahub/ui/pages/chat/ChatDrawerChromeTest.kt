package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatDrawerChromeTest {
    @Test fun `footer selection follows the active destination instead of the last tap`() {
        assertNull(chatDrawerBottomSelection(null, "assistant", moreExpanded = false))
        assertNull(chatDrawerBottomSelection(Screen.Chat("chat"), "assistant", moreExpanded = false))
        assertEquals("assistants", chatDrawerBottomSelection(Screen.Assistant, "assistant", moreExpanded = false))
        assertEquals("favorites", chatDrawerBottomSelection(Screen.Favorite, "assistant", moreExpanded = false))
        assertEquals("stats", chatDrawerBottomSelection(Screen.Stats, "assistant", moreExpanded = false))
    }

    @Test fun `more is selected for its active destination or while its sheet is expanded`() {
        assertEquals("more", chatDrawerBottomSelection(Screen.SettingRoot, "assistant", moreExpanded = false))
        assertEquals("more", chatDrawerBottomSelection(Screen.AssistantLocalTool("assistant"), "assistant", moreExpanded = false))
        assertEquals("more", chatDrawerBottomSelection(Screen.Favorite, "assistant", moreExpanded = true))
        assertNull(chatDrawerBottomSelection(Screen.MessageSearch, "assistant", moreExpanded = false))
    }

    @Test fun `unchecked root never displays a chip or starts a check`() {
        assertNull(chatDrawerRootStatus(RootStatus.UNCHECKED))
        assertNull(chatDrawerRootStatus(RootStatus.CHECKING))
    }

    @Test fun `verified root displays its cached result with meaningful colors`() {
        val ready = chatDrawerRootStatus(RootStatus.READY)!!
        assertEquals("Активен", ready.text)
        assertEquals(SettingsDashboardStatusTone.Success, ready.tone)
        for (status in listOf(RootStatus.UNAVAILABLE, RootStatus.DENIED_OR_FAILED, RootStatus.NOT_ROOT)) {
            assertEquals(status.name, SettingsDashboardStatusTone.Error, chatDrawerRootStatus(status)?.tone)
        }
        assertEquals(SettingsDashboardStatusTone.Warning, chatDrawerRootStatus(RootStatus.TIMED_OUT)?.tone)
    }

    @Test fun `assistant tool footer selection tracks the active assistant`() {
        assertEquals("more", chatDrawerBottomSelection(Screen.AssistantLocalTool("current"), "current", moreExpanded = false))
        assertNull(chatDrawerBottomSelection(Screen.AssistantLocalTool("other"), "current", moreExpanded = false))
    }

    @Test fun `cached root failure labels explain unavailable denied and timed out access`() {
        assertEquals("Недоступен", chatDrawerRootStatus(RootStatus.UNAVAILABLE)?.text)
        assertEquals("Нет доступа", chatDrawerRootStatus(RootStatus.DENIED_OR_FAILED)?.text)
        assertEquals("Нет доступа", chatDrawerRootStatus(RootStatus.NOT_ROOT)?.text)
        assertEquals("Таймаут", chatDrawerRootStatus(RootStatus.TIMED_OUT)?.text)
    }
}
