package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageToVideo
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.ShieldKey
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.pages.setting.SettingsSectionTone

internal enum class ChatDrawerNavigationSection { Header, Search, Assistant, Bottom, More }

internal data class ChatDrawerNavigationItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val tone: SettingsSectionTone,
    val section: ChatDrawerNavigationSection,
    val destination: (String) -> Screen?,
)

/** Every drawer destination, including the existing assistant editor and backup reminder. */
internal object ChatDrawerNavigation {
    val items = listOf(
        ChatDrawerNavigationItem("header_settings", "Настройки", HugeIcons.Settings03, SettingsSectionTone.Primary,
            ChatDrawerNavigationSection.Header) { Screen.Setting },
        ChatDrawerNavigationItem("model", "Модель по умолчанию", HugeIcons.AiMagic, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.Header) { Screen.SettingModels },
        ChatDrawerNavigationItem("root_status", "Root", HugeIcons.ShieldKey, SettingsSectionTone.Tertiary,
            ChatDrawerNavigationSection.Header) { Screen.SettingRoot },
        ChatDrawerNavigationItem("update", "Есть обновление", HugeIcons.Sparkles, SettingsSectionTone.Primary,
            ChatDrawerNavigationSection.Header) { Screen.SettingUpdate },
        ChatDrawerNavigationItem("search", "Поиск по сообщениям", HugeIcons.Search01, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.Search) { Screen.MessageSearch },
        ChatDrawerNavigationItem("history", "История чатов", HugeIcons.TransactionHistory, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.Search) { Screen.History },
        ChatDrawerNavigationItem("assistant_detail", "Настройки ассистента", HugeIcons.PencilEdit01, SettingsSectionTone.Primary,
            ChatDrawerNavigationSection.Assistant) { Screen.AssistantDetail(it) },
        ChatDrawerNavigationItem("assistant_manage", "Управление ассистентами", HugeIcons.LookTop, SettingsSectionTone.Primary,
            ChatDrawerNavigationSection.Assistant) { Screen.Assistant },
        ChatDrawerNavigationItem("assistants", "Ассистенты", HugeIcons.LookTop, SettingsSectionTone.Primary,
            ChatDrawerNavigationSection.Bottom) { Screen.Assistant },
        ChatDrawerNavigationItem("favorites", "Избранное", HugeIcons.InLove, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.Bottom) { Screen.Favorite },
        ChatDrawerNavigationItem("stats", "Статистика", HugeIcons.ChartColumn, SettingsSectionTone.Tertiary,
            ChatDrawerNavigationSection.Bottom) { Screen.Stats },
        ChatDrawerNavigationItem("more", "Ещё", HugeIcons.Sparkles, SettingsSectionTone.Outline,
            ChatDrawerNavigationSection.Bottom) { null },
        ChatDrawerNavigationItem("translator", "Переводчик", HugeIcons.LanguageCircle, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.More) { Screen.Translator },
        ChatDrawerNavigationItem("image_gen", "Генерация изображений", HugeIcons.Image02, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.More) { Screen.ImageGen },
        ChatDrawerNavigationItem("media_creation", "Создание медиа", HugeIcons.ImageToVideo, SettingsSectionTone.Secondary,
            ChatDrawerNavigationSection.More) { Screen.MediaCreationSessions },
        ChatDrawerNavigationItem("workflows", "Сценарии", HugeIcons.GitFork, SettingsSectionTone.Tertiary,
            ChatDrawerNavigationSection.More) { Screen.Workflows },
        ChatDrawerNavigationItem("schedules", "Задания по расписанию", HugeIcons.Calendar03, SettingsSectionTone.Tertiary,
            ChatDrawerNavigationSection.More) { Screen.SettingSchedules },
        ChatDrawerNavigationItem("tools", "Инструменты ассистента", HugeIcons.Tools, SettingsSectionTone.Tertiary,
            ChatDrawerNavigationSection.More) { Screen.AssistantLocalTool(it) },
        ChatDrawerNavigationItem("root", "Root", HugeIcons.ShieldKey, SettingsSectionTone.Tertiary,
            ChatDrawerNavigationSection.More) { Screen.SettingRoot },
        ChatDrawerNavigationItem("settings", "Настройки", HugeIcons.Settings03, SettingsSectionTone.Primary,
            ChatDrawerNavigationSection.More) { Screen.Setting },
        ChatDrawerNavigationItem("backup", "Резервные копии", HugeIcons.Database02, SettingsSectionTone.Surface,
            ChatDrawerNavigationSection.More) { Screen.Backup },
    )

    fun section(section: ChatDrawerNavigationSection): List<ChatDrawerNavigationItem> =
        items.filter { it.section == section }

    fun item(id: String): ChatDrawerNavigationItem = items.single { it.id == id }

    fun route(id: String, assistantId: String = ""): Screen? = item(id).destination(assistantId)
}
