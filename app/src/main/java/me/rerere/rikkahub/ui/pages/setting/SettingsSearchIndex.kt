package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.Screen

typealias SettingsSearchEntry = SettingsCatalogItem

/** Only implemented Rikka-Root pages belong here; never index credentials or configuration values. */
object SettingsSearchIndex {
    // Main rows are the catalog items themselves, so labels, icons and navigation stay aligned.
    // Additional pages have a stable entry only when they can open without a user-specific ID.
    val entries: List<SettingsSearchEntry> = SettingsCatalog.items + listOf(
        SettingsSearchEntry(
            "theme_palettes", "Темы и палитры", "Палитры цветов и оформление приложения",
            HugeIcons.Sun01, SettingsSection.General, SettingsTarget.Route(Screen.SettingTheme),
            "theme colors тема цвета палитра оформление",
        ),
        SettingsSearchEntry(
            "notifications", "Уведомления", "Звук и уведомления об ответах",
            HugeIcons.Notification01, SettingsSection.General, SettingsTarget.Route(Screen.SettingPreferencesNotification),
            "notifications sound",
        ),
        SettingsSearchEntry(
            "general_preferences", "Общие настройки", "Поведение приложения, прокрутка и ввод, автоназвания чатов",
            HugeIcons.Settings03, SettingsSection.General, SettingsTarget.Route(Screen.SettingPreferencesGeneral),
            "general preferences взаимодействие",
        ),
        SettingsSearchEntry(
            "interface_preferences", "Интерфейс", "Отображение чата, шрифтов и элементов управления",
            HugeIcons.PaintBoard, SettingsSection.General, SettingsTarget.Route(Screen.SettingPreferencesUI),
            "ui display блоки кода",
        ),
        SettingsSearchEntry(
            "network_preferences", "Сеть", "Прокси, таймауты и повтор запросов",
            HugeIcons.Internet, SettingsSection.General, SettingsTarget.Route(Screen.SettingPreferencesNetwork),
            "network proxy retry user-agent",
        ),
        SettingsSearchEntry(
            "quick_messages", "Быстрые сообщения", "Готовые сообщения для быстрого ввода",
            HugeIcons.Zap, SettingsSection.General, SettingsTarget.Route(Screen.QuickMessages),
            "quick messages шаблон",
        ),
        SettingsSearchEntry(
            "prompts", "Промпты и справочники", "Режимы, инструкции и справочники для ассистентов",
            HugeIcons.Book03, SettingsSection.General, SettingsTarget.Route(Screen.Prompts),
            "prompts lorebook injections modebook системный промпт",
        ),
        SettingsSearchEntry(
            "skills", "Навыки", "Установка и настройка навыков агента",
            HugeIcons.Puzzle, SettingsSection.General, SettingsTarget.Route(Screen.Skills),
            "skills agent расширения",
        ),
        SettingsSearchEntry(
            "workspaces", "Рабочие пространства", "Файлы, rootfs, терминал и фоновые процессы workspace",
            HugeIcons.Folder01, SettingsSection.General, SettingsTarget.Route(Screen.Workspaces),
            "workspace background shell",
        ),
        SettingsSearchEntry(
            "updates", "Обновления", "Проверка версии и доступных обновлений Rikka-Root",
            HugeIcons.Refresh03, SettingsSection.About, SettingsTarget.Route(Screen.SettingUpdate),
            "update обновление версия релиз apk nightly",
        ),
    )

    fun search(query: String): List<SettingsSearchEntry> {
        val words = query.lowercase().trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val text = "${entry.title} ${entry.description} ${entry.keywords}".lowercase()
            words.all(text::contains)
        }
    }
}
