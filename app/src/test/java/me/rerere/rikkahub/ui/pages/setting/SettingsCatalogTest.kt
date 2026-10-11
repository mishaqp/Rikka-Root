package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.Screen
import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    @Test fun `dashboard has the 24 requested destinations in section order`() {
        assertEquals(
            listOf(SettingsSection.General, SettingsSection.Ai, SettingsSection.Tools, SettingsSection.Data, SettingsSection.About),
            SettingsCatalog.sections,
        )
        assertEquals(
            listOf(
                "color_scheme", "appearance", "assistants", "extensions",
                "default_model_prompt", "providers", "search", "voice", "media",
                "approvals", "root", "termux", "ssh", "browser", "mcp", "workflows", "cron", "web",
                "backup", "files", "log", "about", "docs", "share",
            ),
            SettingsCatalog.items.map { it.id },
        )
        assertEquals(listOf(4, 5, 9, 3, 3), SettingsCatalog.sections.map { section ->
            SettingsCatalog.items.count { it.section == section }
        })
        assertEquals(SettingsCatalog.sections, SettingsCatalog.items.map { it.section }.distinct())
    }

    @Test fun `every dashboard item provides visible metadata and a usable action`() {
        assertEquals(24, SettingsCatalog.items.size)
        assertEquals(24, SettingsCatalog.items.map { it.id }.distinct().size)
        assertEquals(24, SettingsCatalog.items.map { it.title }.distinct().size)
        SettingsCatalog.items.forEach { item ->
            assertTrue(item.id, item.title.isNotBlank())
            assertTrue(item.id, item.description.isNotBlank())
            assertTrue(item.id, item.icon.name.isNotBlank())
            assertEquals(item.id, 1, SettingsCatalog.sections.count { it == item.section })
            when (val target = item.target) {
                is SettingsTarget.Route -> assertNotEquals(item.id, Screen.Setting, target.screen)
                is SettingsTarget.Url -> assertTrue(item.id, target.url.startsWith("https://"))
                is SettingsTarget.Share -> assertTrue(item.id, target.text.isNotBlank())
            }
        }
    }

    @Test fun `new dashboard entries use their own real destination and approvals uses a shield`() {
        fun target(id: String) = SettingsCatalog.items.single { it.id == id }.target
        assertEquals(SettingsTarget.Route(Screen.SettingPreferencesTheme), target("color_scheme"))
        assertEquals(SettingsTarget.Route(Screen.SettingPreferences), target("appearance"))
        assertEquals(SettingsTarget.Route(Screen.SettingRoot), target("root"))
        assertEquals(SettingsTarget.Route(Screen.SettingSchedules), target("cron"))
        assertEquals(SettingsTarget.Route(Screen.Workflows), target("workflows"))
        assertEquals("Сценарии", SettingsCatalog.items.single { it.id == "workflows" }.title)
        assertEquals("Хранилище", SettingsCatalog.items.single { it.id == "files" }.title)
        assertEquals("О Rikka-Root", SettingsCatalog.items.single { it.id == "about" }.title)
        assertTrue(SettingsCatalog.items.single { it.id == "approvals" }.icon.name.contains("Shield"))
    }

    @Test fun `documentation keeps its destination and share points to fork releases`() {
        assertEquals("Документация RikkaHub (оригинал)", SettingsCatalog.items.single { it.id == "docs" }.title)
        assertEquals(
            SettingsTarget.Url("https://docs.rikka-ai.com/introduction"),
            SettingsCatalog.items.single { it.id == "docs" }.target,
        )
        val share = SettingsCatalog.items.single { it.id == "share" }.target as SettingsTarget.Share
        assertTrue(share.text.contains("https://github.com/mishaqp/Rikka-Root/releases"))
    }
}
