package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.ShieldCheck
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkahub.Screen

/** Material color roles are resolved by the UI, so metadata also works in dark mode. */
enum class SettingsSectionTone { Primary, Secondary, Tertiary, Surface, Outline }

enum class SettingsSection(val id: String, val title: String, val tone: SettingsSectionTone) {
    General("general", "Общие", SettingsSectionTone.Primary),
    Ai("ai", "ИИ", SettingsSectionTone.Secondary),
    Tools("tools", "Инструменты и автоматизация", SettingsSectionTone.Tertiary),
    Data("data", "Данные", SettingsSectionTone.Surface),
    About("about", "О приложении", SettingsSectionTone.Outline),
}

sealed interface SettingsTarget {
    data class Route(val screen: Screen) : SettingsTarget
    data class Url(val url: String) : SettingsTarget
    data class Share(val text: String) : SettingsTarget
}

data class SettingsCatalogItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val section: SettingsSection,
    val target: SettingsTarget,
    val keywords: String = "",
) {
    /** Route convenience; external actions retain their own target. */
    val route: Screen? get() = (target as? SettingsTarget.Route)?.screen
}

/** Display metadata only; never include credentials or user configuration values. */
object SettingsCatalog {
    val sections: List<SettingsSection> = SettingsSection.entries.toList()

    val items: List<SettingsCatalogItem> = listOf(
        SettingsCatalogItem(
            "color_scheme", "Цветовая схема", "Светлая, тёмная и системная тема",
            HugeIcons.Sun01, SettingsSection.General, SettingsTarget.Route(Screen.SettingPreferencesTheme),
            "theme colors цвета оформление amoled динамические палитра",
        ),
        SettingsCatalogItem(
            "appearance", "Внешний вид и поведение", "Интерфейс, уведомления и общие настройки",
            HugeIcons.Settings03, SettingsSection.General, SettingsTarget.Route(Screen.SettingPreferences),
            "appearance preferences настройки оформление",
        ),
        SettingsCatalogItem(
            "assistants", "Ассистенты", "Модель, инструкции, контекст и автоматическое сжатие",
            HugeIcons.LookTop, SettingsSection.General, SettingsTarget.Route(Screen.Assistant),
            "assistant compaction compression промпт",
        ),
        SettingsCatalogItem(
            "extensions", "Расширения", "Рабочие пространства, навыки, режимы и справочники",
            HugeIcons.Package, SettingsSection.General, SettingsTarget.Route(Screen.Extensions),
            "extensions skills lorebook",
        ),
        SettingsCatalogItem(
            "default_model_prompt", "Модель и промпт по умолчанию", "Модели чата, быстрого ответа и сжатия",
            HugeIcons.AiMagic, SettingsSection.Ai, SettingsTarget.Route(Screen.SettingModels),
            "models default prompt системные инструкции",
        ),
        SettingsCatalogItem(
            "providers", "Провайдеры", "Подключение сервисов и вход в ChatGPT",
            HugeIcons.Brain02, SettingsSection.Ai, SettingsTarget.Route(Screen.SettingProvider),
            "providers openai oauth gpt api",
        ),
        SettingsCatalogItem(
            "search", "Веб-поиск", "Сервисы поиска в интернете",
            HugeIcons.GlobalSearch, SettingsSection.Ai, SettingsTarget.Route(Screen.SettingSearch),
            "web search searx duckduckgo поиск",
        ),
        SettingsCatalogItem(
            "voice", "Голос", "Синтез и распознавание речи",
            HugeIcons.Megaphone01, SettingsSection.Ai, SettingsTarget.Route(Screen.SettingSpeech),
            "speech tts asr голос озвучивание",
        ),
        SettingsCatalogItem(
            "media", "Изображения и видео", "Сервисы генерации медиа",
            HugeIcons.Image02, SettingsSection.Ai, SettingsTarget.Route(Screen.SettingMedia),
            "media image video картинки",
        ),
        SettingsCatalogItem(
            "approvals", "Разрешения инструментов", "Автоодобрение, постоянный доступ и отзыв разрешений",
            HugeIcons.ShieldCheck, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingToolApprovals),
            "approvals permission root рут всегда разрешать журнал полный доступ",
        ),
        SettingsCatalogItem(
            "root", "Root", "Root-доступ и инструменты устройства",
            HugeIcons.Bash, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingRoot),
            "root рут su суперпользователь shell терминал",
        ),
        SettingsCatalogItem(
            "termux", "Termux", "Терминал и запуск команд через Termux",
            HugeIcons.ComputerTerminal01, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingTermux),
            "termux термукс shell команды bash",
        ),
        SettingsCatalogItem(
            "ssh", "SSH", "Удалённые серверы и выполнение команд",
            HugeIcons.ServerStack01, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingSsh),
            "ssh сервер server remote shell подключение",
        ),
        SettingsCatalogItem(
            "browser", "Браузер", "Веб-страницы и инструменты браузера",
            HugeIcons.Internet, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingBrowser),
            "browser web интернет страницы javascript",
        ),
        SettingsCatalogItem(
            "mcp", "MCP", "Подключение серверов инструментов",
            HugeIcons.McpServer, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingMcp),
            "mcp tools инструменты сервер",
        ),
        SettingsCatalogItem(
            "workflows", "Сценарии", "Автоматизация по событиям и условиям",
            HugeIcons.GitFork, SettingsSection.Tools, SettingsTarget.Route(Screen.Workflows),
            "workflow workflows сценарий события условия автоматизация батарея battery doze фоновые задания",
        ),
        SettingsCatalogItem(
            "cron", "Задания по расписанию", "Расписания Cron и работа в фоне",
            HugeIcons.Calendar03, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingSchedules),
            "cron schedules расписание таймер задания батарея battery doze энергосбережение",
        ),
        SettingsCatalogItem(
            "web", "Веб-сервер", "Веб-интерфейс и доступ через браузер",
            HugeIcons.ServerStack01, SettingsSection.Tools, SettingsTarget.Route(Screen.SettingWeb),
            "web server сервер порт локальная сеть",
        ),
        SettingsCatalogItem(
            "backup", "Резервные копии", "Экспорт и восстановление данных",
            HugeIcons.Database02, SettingsSection.Data, SettingsTarget.Route(Screen.Backup),
            "backup restore бэкап копия импорт экспорт",
        ),
        SettingsCatalogItem(
            "files", "Хранилище", "Файлы чата, вложения и занимаемое место",
            HugeIcons.Folder01, SettingsSection.Data, SettingsTarget.Route(Screen.SettingFiles),
            "storage cache файлы хранилище кэш",
        ),
        SettingsCatalogItem(
            "log", "Журнал запросов", "Просмотр запросов к модели",
            HugeIcons.Bookshelf01, SettingsSection.Data, SettingsTarget.Route(Screen.Log),
            "logs log диагностика ошибки",
        ),
        SettingsCatalogItem(
            "about", "О Rikka-Root", "Версия, сведения о приложении и обновления",
            HugeIcons.Clapping01, SettingsSection.About, SettingsTarget.Route(Screen.SettingAbout),
            "about update обновление версия релиз",
        ),
        SettingsCatalogItem(
            "docs", "Документация RikkaHub (оригинал)", "Руководство оригинального приложения",
            HugeIcons.Book01, SettingsSection.About, SettingsTarget.Url("https://docs.rikka-ai.com/introduction"),
            "docs documentation инструкция помощь руководство",
        ),
        SettingsCatalogItem(
            "share", "Поделиться", "Ссылка на релизы Rikka-Root",
            HugeIcons.Share04, SettingsSection.About,
            SettingsTarget.Share("Rikka-Root — открытый Android-ассистент с ИИ\nhttps://github.com/mishaqp/Rikka-Root/releases"),
            "share поделиться отправить releases релизы",
        ),
    )
}
