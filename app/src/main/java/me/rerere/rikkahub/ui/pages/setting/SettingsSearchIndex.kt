package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.Screen

data class SettingsSearchEntry(val title: String, val description: String, val route: Screen, val keywords: String = "")

/** Only implemented Rikka-Root pages belong here; never index credentials or configuration values. */
object SettingsSearchIndex {
    // Keep this list aligned with SettingPage and SettingPreferencesPage when adding destinations.
    val entries: List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry("Разрешения инструментов", "Автоодобрение, Всегда разрешать, отзыв разрешений", Screen.SettingToolApprovals, "root рут журнал полный доступ"),
        SettingsSearchEntry("Ассистенты", "Модель, системные инструкции, контекст и автоматическое сжатие", Screen.Assistant, "assistant compaction compression"),
        SettingsSearchEntry("Расширения", "Рабочие пространства, навыки, режимы и справочники", Screen.Extensions, "extensions skills lorebook"),
        SettingsSearchEntry("Рабочие пространства", "Файлы, rootfs, терминал и фоновые процессы workspace", Screen.Workspaces, "background shell"),
        SettingsSearchEntry("Модели по умолчанию", "Модели чата, быстрого ответа и сжатия", Screen.SettingModels, "models default"),
        SettingsSearchEntry("Провайдеры", "Подключение сервисов и вход в ChatGPT", Screen.SettingProvider, "providers openai oauth gpt"),
        SettingsSearchEntry("Веб-поиск", "Сервисы поиска в интернете", Screen.SettingSearch, "web search searx duckduckgo"),
        SettingsSearchEntry("Голос", "Синтез и распознавание речи", Screen.SettingSpeech, "speech tts asr"),
        SettingsSearchEntry("Изображения и видео", "Сервисы генерации медиа", Screen.SettingMedia, "media image video"),
        SettingsSearchEntry("MCP", "Подключение серверов инструментов", Screen.SettingMcp, "tools инструменты"),
        SettingsSearchEntry("Веб-сервер", "Веб-интерфейс и доступ через браузер", Screen.SettingWeb, "web server"),
        SettingsSearchEntry("Резервные копии", "Экспорт и восстановление данных", Screen.Backup, "backup restore"),
        SettingsSearchEntry("Файлы чата", "Вложения и занимаемое место", Screen.SettingFiles, "storage cache хранилище"),
        SettingsSearchEntry("Тема", "Цвета, тёмный режим и оформление", Screen.SettingPreferencesTheme, "theme colors"),
        SettingsSearchEntry("Уведомления", "Звук и уведомления об ответах", Screen.SettingPreferencesNotification, "notifications sound"),
        SettingsSearchEntry("Общие настройки", "Предпочтения приложения", Screen.SettingPreferencesGeneral, "general preferences"),
        SettingsSearchEntry("Интерфейс", "Отображение чата и элементов управления", Screen.SettingPreferencesUI, "ui display"),
        SettingsSearchEntry("Сеть", "Прокси, таймауты и повтор запросов", Screen.SettingPreferencesNetwork, "network proxy retry"),
        SettingsSearchEntry("Журнал запросов", "Просмотр запросов к модели", Screen.Log, "logs диагностика"),
        SettingsSearchEntry("О приложении", "Версия и обновления Rikka-Root", Screen.SettingAbout, "about update"),
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
