package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.Screen
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchIndexTest {
    @Test fun `root approvals and compression are discoverable without indexing settings values`() {
        assertTrue(SettingsSearchIndex.search("ROOT").any { it.route == Screen.SettingToolApprovals })
        assertTrue(SettingsSearchIndex.search("сжатие").any { it.route == Screen.Assistant })
        assertTrue(SettingsSearchIndex.search("  фоновые   процессы  ").any { it.route == Screen.Workspaces })
    }

    @Test fun `all query terms are matched case insensitively and empty or unrelated searches return no results`() {
        assertTrue(SettingsSearchIndex.search("УВЕДОМЛЕНИЯ звук").any { it.route == Screen.SettingPreferencesNotification })
        assertTrue(SettingsSearchIndex.search("уведомления SSH").isEmpty())
        assertTrue(SettingsSearchIndex.search("   ").isEmpty())
    }

    @Test fun `absent Agent capabilities have no routes and index titles are unique`() {
        for (name in listOf("ssh", "telegram", "локальные ии", "shizuku", "termux")) {
            assertTrue(name, SettingsSearchIndex.search(name).isEmpty())
        }
        assertEquals(SettingsSearchIndex.entries.size, SettingsSearchIndex.entries.map { it.title }.distinct().size)
    }
}
