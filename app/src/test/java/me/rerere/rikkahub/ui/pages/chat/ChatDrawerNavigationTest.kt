package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDrawerNavigationTest {
    @Test fun `all previous drawer destinations remain accessible`() {
        val routes = ChatDrawerNavigation.items.mapNotNull { it.destination("assistant-id") }.toSet()
        assertTrue(routes.containsAll(setOf(
            Screen.MessageSearch, Screen.History, Screen.MediaCreationSessions,
            Screen.Assistant, Screen.AssistantDetail("assistant-id"), Screen.Translator,
            Screen.ImageGen, Screen.Favorite, Screen.Stats, Screen.Setting, Screen.Backup,
        )))
    }

    @Test fun `bottom row has the four requested labeled actions`() {
        assertEquals(listOf("Ассистенты", "Избранное", "Статистика", "Ещё"),
            ChatDrawerNavigation.section(ChatDrawerNavigationSection.Bottom).map { it.title })
        assertEquals(listOf(Screen.Assistant, Screen.Favorite, Screen.Stats, null),
            ChatDrawerNavigation.section(ChatDrawerNavigationSection.Bottom).map { it.destination("a") })
    }

    @Test fun `more sheet includes every requested destination and backups`() {
        assertEquals(listOf(
            Screen.Translator, Screen.ImageGen, Screen.MediaCreationSessions,
            Screen.Workflows, Screen.SettingSchedules, Screen.AssistantLocalTool("a"),
            Screen.SettingRoot, Screen.Setting, Screen.Backup,
        ), ChatDrawerNavigation.section(ChatDrawerNavigationSection.More).map { it.destination("a") })
    }

    @Test fun `each action has a unique identifier label and icon`() {
        val entries = ChatDrawerNavigation.items
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        entries.forEach { item ->
            assertTrue(item.id, item.title.isNotBlank())
            assertTrue(item.id, item.icon.name.isNotBlank())
            assertEquals(item.id, item.id == "more", item.destination("a") == null)
        }
    }

    @Test fun `assistant destinations follow the current assistant without changing models`() {
        assertEquals(Screen.AssistantLocalTool("first"), ChatDrawerNavigation.item("tools").destination("first"))
        assertEquals(Screen.AssistantDetail("second"), ChatDrawerNavigation.item("assistant_detail").destination("second"))
        assertEquals(Screen.SettingModels, ChatDrawerNavigation.item("model").destination("a"))
        assertFalse(ChatDrawerNavigation.items.any { it.destination("a") is Screen.Chat })
    }
}
