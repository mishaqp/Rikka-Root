package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.Screen
import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    @Test fun `dashboard includes permissions and existing destinations in section order`() {
        assertEquals(
            listOf(SettingsSection.General, SettingsSection.Ai, SettingsSection.Tools, SettingsSection.Data, SettingsSection.About),
            SettingsCatalog.sections,
        )
        assertEquals(
            listOf(
                "color_scheme", "appearance", "assistants", "extensions",
                "default_model_prompt", "providers", "search", "voice", "media",
                "permissions_access", "approvals", "root", "termux", "ssh", "browser", "mcp", "workflows", "cron", "mini_apps", "web",
                "backup", "files", "log", "about", "docs", "share",
            ),
            SettingsCatalog.items.map { it.id },
        )
        assertEquals(listOf(4, 5, 11, 3, 3), SettingsCatalog.sections.map { section ->
            SettingsCatalog.items.count { it.section == section }
        })
        assertEquals(SettingsCatalog.sections, SettingsCatalog.items.map { it.section }.distinct())
    }

    @Test fun `permissions and access is a distinct real destination in main settings`() {
        val item = SettingsCatalog.items.single { it.id == "permissions_access" }
        assertEquals("Разрешения и доступ", item.title)
        assertEquals(SettingsSection.Tools, item.section)
        assertNotEquals(Screen.SettingRoot, item.route)
        assertNotEquals(Screen.SettingToolApprovals, item.route)
        assertNotNull(item.route)
    }

    @Test fun `every dashboard item provides visible metadata and a usable action`() {
        assertEquals(26, SettingsCatalog.items.size)
        assertEquals(26, SettingsCatalog.items.map { it.id }.distinct().size)
        assertEquals(26, SettingsCatalog.items.map { it.title }.distinct().size)
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
        assertEquals(SettingsTarget.Route(Screen.MiniApps), target("mini_apps"))
        assertEquals(SettingsTarget.Route(Screen.Workflows), target("workflows"))
        assertEquals("Сценарии", SettingsCatalog.items.single { it.id == "workflows" }.title)
        assertEquals("Хранилище", SettingsCatalog.items.single { it.id == "files" }.title)
        assertEquals("О Rikka-Root", SettingsCatalog.items.single { it.id == "about" }.title)
        assertTrue(SettingsCatalog.items.single { it.id == "approvals" }.icon.name.contains("Shield"))
    }

    @Test fun `every main settings destination has a distinct icon`() {
        assertEquals(26, SettingsCatalog.items.map { it.icon.name }.distinct().size)
        assertEquals(26, SettingsCatalog.items.map { it.icon }.distinct().size)
        val root = SettingsCatalog.items.single { it.id == "root" }
        val termux = SettingsCatalog.items.single { it.id == "termux" }
        val ssh = SettingsCatalog.items.single { it.id == "ssh" }
        val web = SettingsCatalog.items.single { it.id == "web" }
        assertTrue(root.icon.name.contains("Shield") || root.icon.name.contains("Key"))
        assertNotEquals(root.icon, termux.icon)
        assertNotEquals(ssh.icon, web.icon)
        assertTrue(web.icon.name.contains("Globe"))
    }

    @Test fun `shared quick tiles use catalog icons and destinations`() {
        assertEquals(listOf("providers", "model", "tools", "root"), SettingsCatalog.quickTiles.map { it.id })
        assertEquals(
            listOf("providers", "default_model_prompt", "root"),
            SettingsCatalog.quickTiles.mapNotNull { it.catalogItemId },
        )
        SettingsCatalog.quickTiles.filter { it.catalogItemId != null }.forEach { tile ->
            val item = SettingsCatalog.items.single { it.id == tile.catalogItemId }
            assertSame(tile.id, item.icon, tile.icon)
            assertEquals(tile.id, item.route, tile.route(null))
            assertEquals(tile.id, item.route, tile.route("current-assistant"))
        }
    }

    @Test fun `assistant tools tile follows the current assistant without adding a main destination`() {
        val tools = SettingsCatalog.quickTiles.single { it.id == "tools" }
        assertNull(tools.catalogItemId)
        assertEquals(SettingsQuickTileTarget.CurrentAssistantTools, tools.target)
        assertEquals(Screen.AssistantLocalTool("current-assistant"), tools.route("current-assistant"))
        assertEquals(Screen.Assistant, tools.route(null))
        assertFalse(SettingsCatalog.items.any { it.id == "tools" })
        assertFalse(SettingsCatalog.items.any { it.icon == tools.icon })
        assertFalse(SettingsCatalog.items.any { it.icon.name == tools.icon.name })
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
