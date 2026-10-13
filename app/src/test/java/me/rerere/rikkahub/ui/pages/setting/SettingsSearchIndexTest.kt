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
        for (name in listOf("telegram", "локальные ии", "shizuku")) {
            assertTrue(name, SettingsSearchIndex.search(name).isEmpty())
        }
        assertEquals(SettingsSearchIndex.entries.size, SettingsSearchIndex.entries.map { it.title }.distinct().size)
    }

    @Test fun `all dashboard actions are indexed with their original icon section and target`() {
        SettingsCatalog.items.forEach { item ->
            val entry = SettingsSearchIndex.entries.single { it.id == item.id }
            assertSame(item.icon, entry.icon)
            assertEquals(item.section, entry.section)
            assertEquals(item.target, entry.target)
            assertTrue(item.id, SettingsSearchIndex.search(item.title).contains(entry))
        }
        assertTrue(SettingsSearchIndex.search("документация").any { it.target is SettingsTarget.Url })
        assertTrue(SettingsSearchIndex.search("поделиться").any { it.target is SettingsTarget.Share })
    }

    @Test fun `root shell remote server battery and updates accept synonyms mixed case and whitespace`() {
        for (query in listOf("ROOT", "  РуТ  ", "Su")) {
            assertTrue(query, SettingsSearchIndex.search(query).any { it.route == Screen.SettingRoot })
        }
        assertTrue(SettingsSearchIndex.search("\tSsH\nСеРвЕр  ").any { it.route == Screen.SettingSsh })
        assertTrue(SettingsSearchIndex.search("termux").any { it.route == Screen.SettingTermux })
        for (query in listOf("батарея", "BATTERY", "DoZe")) {
            assertTrue(query, SettingsSearchIndex.search(query).any { it.route == Screen.SettingSchedules })
            assertTrue(query, SettingsSearchIndex.search(query).any { it.route == Screen.Workflows })
        }
        val updates = SettingsSearchIndex.search("  ОБНОВЛЕНИЕ  ").map { it.route }
        assertTrue(updates.contains(Screen.SettingAbout))
        assertTrue(updates.contains(Screen.SettingUpdate))
    }

    @Test fun `nested preferences and extension destinations remain searchable`() {
        val routes = SettingsSearchIndex.entries.mapNotNull { it.route }.toSet()
        assertTrue(routes.containsAll(listOf(
            Screen.SettingTheme,
            Screen.SettingPreferencesNotification,
            Screen.SettingPreferencesGeneral,
            Screen.SettingPreferencesUI,
            Screen.SettingPreferencesNetwork,
            Screen.QuickMessages,
            Screen.Prompts,
            Screen.Skills,
            Screen.Workspaces,
        )))
        assertEquals(SettingsSearchIndex.entries.size, SettingsSearchIndex.entries.map { it.id }.distinct().size)
    }

    @Test fun `customization searches open the page that owns the actual preference`() {
        val expected = mapOf(
            "Markdown пользователя" to Screen.SettingInterfaceRendering,
            "имя пользователя" to Screen.SettingInterfaceMessages,
            "вибрация переключателей" to Screen.SettingInterfaceHaptics,
            "размытие" to Screen.SettingInterfaceMessageStyle,
            "порог символы" to Screen.SettingInterfaceBehavior,
            "Шрифт кода" to Screen.SettingInterfaceFonts("code"),
            "Google Fonts" to Screen.SettingInterfaceGoogleFonts("app"),
            "фон чата обои" to Screen.SettingInterfaceBackground,
            "язык приложения" to Screen.SettingInterfaceLanguage,
            "непрозрачность поля ввода" to Screen.SettingInterfaceInputOpacity,
            "сворачивать завершённые шаги" to Screen.SettingInterfaceBehavior,
        )
        expected.forEach { (query, destination) ->
            assertTrue(query, SettingsSearchIndex.search(query).any { it.route == destination })
        }
        val newEntries = SettingsSearchIndex.entries.filter { it.id.startsWith("ui_") }
        assertTrue(newEntries.isNotEmpty())
        assertTrue(newEntries.all { it.titleRes != null && it.descriptionRes != null })
        assertFalse(SettingsSearchIndex.search("системное плавающее окно").any { it.id.startsWith("ui_") })
    }
}
