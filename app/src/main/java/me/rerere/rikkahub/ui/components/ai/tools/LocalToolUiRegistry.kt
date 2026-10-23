package me.rerere.rikkahub.ui.components.ai.tools

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.Icon1stBracket
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption

data class LocalToolUiCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val options: List<LocalToolOption>,
)

data class LocalToolUiEntry(
    val option: LocalToolOption,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val requiresRoot: Boolean = false,
    val background: Boolean = false,
) {
    fun title(context: Context): String = context.getString(titleRes)
    fun description(context: Context): String = context.getString(descriptionRes)
}

enum class LocalToolPreset(val title: String) {
    Minimum("Минимум"),
    Helper("Помощник"),
    Agent("Агент"),
    All("Всё"),
    None("Выключить всё"),
}

/** Display metadata only. Runtime tool names and behavior remain in the local tool factories. */
object LocalToolUiRegistry {
    private enum class Category(val id: String, val title: String, val icon: ImageVector) {
        AgentAutomation("agent_automation", "Агент и автоматизация", HugeIcons.AiBrain01),
        RootTerminal("root_terminal", "Root и терминал", HugeIcons.Bash),
        Internet("internet", "Интернет и интеграции", HugeIcons.Internet),
        FilesMedia("files_media", "Файлы и медиа", HugeIcons.Files02),
        Device("device", "Сведения об устройстве", HugeIcons.SmartPhone01),
        Phone("phone", "Управление телефоном", HugeIcons.Settings03),
        Personal("personal", "Личные данные", HugeIcons.Book01),
        CameraAudio("camera_audio", "Камера, звук и речь", HugeIcons.Camera01),
        Output("output", "Уведомления и вывод", HugeIcons.Megaphone01),
    }

    private data class Registration(val category: Category, val entry: LocalToolUiEntry)

    // One ordered mapping: add a tool here with its category and display metadata.
    private val registrations = listOf(
        tool(Category.AgentAutomation, LocalToolOption.SubAgents, R.string.agent_tools_subagent_title, R.string.agent_tools_subagent_desc, HugeIcons.GitFork, background = true),
        tool(Category.AgentAutomation, LocalToolOption.CronJobs, R.string.agent_tools_cron_title, R.string.agent_tools_cron_desc, HugeIcons.Clock02, background = true),
        tool(Category.AgentAutomation, LocalToolOption.Workflows, R.string.assistant_page_local_tools_workflows_title, R.string.assistant_page_local_tools_workflows_desc, HugeIcons.Zap, background = true),
        tool(Category.AgentAutomation, LocalToolOption.SkillImport, R.string.assistant_page_local_tools_skill_import_title, R.string.assistant_page_local_tools_skill_import_desc, HugeIcons.FileImport),
        tool(Category.AgentAutomation, LocalToolOption.JsSkills, R.string.assistant_page_local_tools_js_skills_title, R.string.assistant_page_local_tools_js_skills_desc, HugeIcons.Code),
        tool(Category.AgentAutomation, LocalToolOption.JavascriptEngine, R.string.assistant_page_local_tools_javascript_engine_title, R.string.assistant_page_local_tools_javascript_engine_desc, HugeIcons.Icon1stBracket),
        tool(Category.AgentAutomation, LocalToolOption.AskUser, R.string.assistant_page_local_tools_ask_user_title, R.string.assistant_page_local_tools_ask_user_desc, HugeIcons.BubbleChatQuestion),
        tool(Category.AgentAutomation, LocalToolOption.CostGuards, R.string.agent_tools_cost_title, R.string.agent_tools_cost_desc, HugeIcons.MoneyBag02),
        tool(Category.AgentAutomation, LocalToolOption.Reliability, R.string.agent_tools_reliability_title, R.string.agent_tools_reliability_desc, HugeIcons.Bug01),
        tool(Category.AgentAutomation, LocalToolOption.ManageAssistants, R.string.assistant_page_local_tools_manage_assistants_title, R.string.assistant_page_local_tools_manage_assistants_desc, HugeIcons.AiBrain01),
        tool(Category.AgentAutomation, LocalToolOption.ManageAppSettings, R.string.assistant_page_local_tools_manage_app_settings_title, R.string.assistant_page_local_tools_manage_app_settings_desc, HugeIcons.Settings03),

        tool(Category.RootTerminal, LocalToolOption.Root, R.string.assistant_page_local_tools_root_title, R.string.assistant_page_local_tools_root_desc, HugeIcons.Bash, requiresRoot = true),
        tool(Category.RootTerminal, LocalToolOption.Termux, R.string.assistant_page_local_tools_termux_title, R.string.shell_tools_termux_desc, HugeIcons.ComputerTerminal01),
        tool(Category.RootTerminal, LocalToolOption.Ssh, R.string.assistant_page_local_tools_ssh_title, R.string.assistant_page_local_tools_ssh_desc, HugeIcons.ServerStack01),
        tool(Category.RootTerminal, LocalToolOption.ScreenAutomation, R.string.assistant_page_local_tools_screen_automation_title, R.string.assistant_page_local_tools_screen_automation_desc, HugeIcons.CursorPointer01, requiresRoot = true),

        tool(Category.Internet, LocalToolOption.HttpRequest, R.string.new_local_tools_http_title, R.string.new_local_tools_http_desc, HugeIcons.Internet, background = true),
        tool(Category.Internet, LocalToolOption.Browser, R.string.assistant_page_local_tools_browser_title, R.string.assistant_page_local_tools_browser_desc, HugeIcons.Earth),
        tool(Category.Internet, LocalToolOption.McpControl, R.string.assistant_page_local_tools_mcp_control_title, R.string.assistant_page_local_tools_mcp_control_desc, HugeIcons.McpServer),
        tool(Category.Internet, LocalToolOption.ExternalAutomation, R.string.assistant_page_local_tools_external_automation_title, R.string.assistant_page_local_tools_external_automation_desc, HugeIcons.Connect),
        tool(Category.Internet, LocalToolOption.Download, R.string.tool_ui_download_title, R.string.file_tools_download_desc, HugeIcons.Download01),

        tool(Category.FilesMedia, LocalToolOption.QrCodes, R.string.new_local_tools_qr_title, R.string.new_local_tools_qr_desc, HugeIcons.Image02, background = true),
        tool(Category.FilesMedia, LocalToolOption.ReadDocument, R.string.new_local_tools_document_title, R.string.new_local_tools_document_desc, HugeIcons.Files02, background = true),
        tool(Category.FilesMedia, LocalToolOption.Files, R.string.tool_ui_files_title, R.string.file_tools_files_desc, HugeIcons.Files02),
        tool(Category.FilesMedia, LocalToolOption.ExternalStorage, R.string.tool_ui_storage_title, R.string.file_tools_storage_desc, HugeIcons.Folder01),
        tool(Category.FilesMedia, LocalToolOption.MiniApps, R.string.mini_apps_tool_title, R.string.mini_apps_tool_description, HugeIcons.Package01, background = true),
        tool(Category.FilesMedia, LocalToolOption.Archive, R.string.file_tools_archive_title, R.string.file_tools_archive_desc, HugeIcons.Package01),
        tool(Category.FilesMedia, LocalToolOption.MediaPlayer, R.string.file_tools_player_title, R.string.file_tools_player_desc, HugeIcons.MusicNote03),
        tool(Category.FilesMedia, LocalToolOption.MediaScanner, R.string.file_tools_scanner_title, R.string.file_tools_scanner_desc, HugeIcons.Image02),

        tool(Category.Device, LocalToolOption.Battery, R.string.assistant_page_local_tools_battery_title, R.string.assistant_page_local_tools_battery_desc, HugeIcons.Zap),
        tool(Category.Device, LocalToolOption.AudioInfo, R.string.assistant_page_local_tools_audio_info_title, R.string.assistant_page_local_tools_audio_info_desc, HugeIcons.VolumeHigh),
        tool(Category.Device, LocalToolOption.TelephonyInfo, R.string.assistant_page_local_tools_telephony_info_title, R.string.assistant_page_local_tools_telephony_info_desc, HugeIcons.SmartPhone01),
        tool(Category.Device, LocalToolOption.WifiInfo, R.string.assistant_page_local_tools_wifi_info_title, R.string.assistant_page_local_tools_wifi_info_desc, HugeIcons.Internet),
        tool(Category.Device, LocalToolOption.Sensors, R.string.assistant_page_local_tools_sensors_title, R.string.assistant_page_local_tools_sensors_desc, HugeIcons.Cpu),
        tool(Category.Device, LocalToolOption.StorageInfo, R.string.assistant_page_local_tools_storage_info_title, R.string.assistant_page_local_tools_storage_info_desc, HugeIcons.Database02),
        tool(Category.Device, LocalToolOption.TimeInfo, R.string.assistant_page_local_tools_time_info_title, R.string.assistant_page_local_tools_time_info_desc, HugeIcons.Clock02),
        tool(Category.Device, LocalToolOption.ScreenTime, R.string.assistant_page_local_tools_screen_time_title, R.string.assistant_page_local_tools_screen_time_desc, HugeIcons.Time02),

        tool(Category.Phone, LocalToolOption.AlarmClock, R.string.new_local_tools_alarm_title, R.string.new_local_tools_alarm_desc, HugeIcons.Clock02),
        tool(Category.Phone, LocalToolOption.Torch, R.string.assistant_page_local_tools_torch_title, R.string.assistant_page_local_tools_torch_desc, HugeIcons.Zap),
        tool(Category.Phone, LocalToolOption.Vibrate, R.string.assistant_page_local_tools_vibrate_title, R.string.assistant_page_local_tools_vibrate_desc, HugeIcons.SmartPhone01),
        tool(Category.Phone, LocalToolOption.Brightness, R.string.assistant_page_local_tools_brightness_title, R.string.assistant_page_local_tools_brightness_desc, HugeIcons.Sun01),
        tool(Category.Phone, LocalToolOption.Volume, R.string.assistant_page_local_tools_volume_title, R.string.assistant_page_local_tools_volume_desc, HugeIcons.VolumeHigh),
        tool(Category.Phone, LocalToolOption.Wallpaper, R.string.assistant_page_local_tools_wallpaper_title, R.string.assistant_page_local_tools_wallpaper_desc, HugeIcons.Image02),
        tool(Category.Phone, LocalToolOption.Nfc, R.string.assistant_page_local_tools_nfc_title, R.string.assistant_page_local_tools_nfc_desc, HugeIcons.Connect),
        tool(Category.Phone, LocalToolOption.SystemIntents, R.string.file_tools_intents_title, R.string.file_tools_intents_desc, HugeIcons.Wrench01),
        tool(Category.Phone, LocalToolOption.AppLauncher, R.string.file_tools_launcher_title, R.string.file_tools_launcher_desc, HugeIcons.Rocket01),
        tool(Category.Phone, LocalToolOption.Clipboard, R.string.assistant_page_local_tools_clipboard_title, R.string.assistant_page_local_tools_clipboard_desc, HugeIcons.Clipboard),

        tool(Category.Personal, LocalToolOption.Location, R.string.personal_tools_location_title, R.string.personal_tools_location_desc, HugeIcons.Pin),
        tool(Category.Personal, LocalToolOption.Contacts, R.string.personal_tools_contacts_title, R.string.personal_tools_contacts_desc, HugeIcons.Book01),
        tool(Category.Personal, LocalToolOption.CallLog, R.string.personal_tools_call_log_title, R.string.personal_tools_call_log_desc, HugeIcons.TransactionHistory),
        tool(Category.Personal, LocalToolOption.SmsInbox, R.string.personal_tools_sms_inbox_title, R.string.personal_tools_sms_inbox_desc, HugeIcons.Message01),
        tool(Category.Personal, LocalToolOption.SmsSend, R.string.personal_tools_sms_send_title, R.string.personal_tools_sms_send_desc, HugeIcons.MessageAdd01),
        tool(Category.Personal, LocalToolOption.Calendar, R.string.assistant_page_local_tools_calendar_title, R.string.assistant_page_local_tools_calendar_desc, HugeIcons.Calendar03),
        tool(Category.Personal, LocalToolOption.Keystore, R.string.tool_ui_keystore_title, R.string.personal_tools_keystore_desc, HugeIcons.Wrench01),
        tool(Category.Personal, LocalToolOption.Fingerprint, R.string.personal_tools_fingerprint_title, R.string.personal_tools_fingerprint_desc, HugeIcons.CheckmarkCircle01),

        tool(Category.CameraAudio, LocalToolOption.CameraPhoto, R.string.personal_tools_camera_title, R.string.personal_tools_camera_desc, HugeIcons.Camera01),
        tool(Category.CameraAudio, LocalToolOption.MicRecorder, R.string.personal_tools_mic_title, R.string.personal_tools_mic_desc, HugeIcons.Mic01),
        tool(Category.CameraAudio, LocalToolOption.SpeechToText, R.string.personal_tools_speech_title, R.string.personal_tools_speech_desc, HugeIcons.Voice),
        tool(Category.CameraAudio, LocalToolOption.Tts, R.string.tool_ui_tts_title, R.string.assistant_page_local_tools_tts_desc, HugeIcons.VolumeHigh),

        tool(Category.Output, LocalToolOption.Toast, R.string.assistant_page_local_tools_toast_title, R.string.assistant_page_local_tools_toast_desc, HugeIcons.Message01),
        tool(Category.Output, LocalToolOption.Notification, R.string.assistant_page_local_tools_notification_title, R.string.assistant_page_local_tools_notification_desc, HugeIcons.Notification01),
        tool(Category.Output, LocalToolOption.Share, R.string.assistant_page_local_tools_share_title, R.string.assistant_page_local_tools_share_desc, HugeIcons.Share01),
        tool(Category.Output, LocalToolOption.ChartDisplay, R.string.assistant_page_local_tools_chart_display_title, R.string.assistant_page_local_tools_chart_display_desc, HugeIcons.ChartColumn),
    )

    val categories: List<LocalToolUiCategory> = Category.entries.map { category ->
        LocalToolUiCategory(category.id, category.title, category.icon,
            registrations.filter { it.category == category }.map { it.entry.option })
    }
    val entries: List<LocalToolUiEntry> = registrations.map { it.entry }

    private val entryByOption = entries.associateBy { it.option }
    private val categoryByOption = categories.flatMap { category ->
        category.options.map { it to category }
    }.toMap()

    fun entry(option: LocalToolOption): LocalToolUiEntry = entryByOption.getValue(option)

    fun category(option: LocalToolOption): LocalToolUiCategory = categoryByOption.getValue(option)

    fun preset(preset: LocalToolPreset): Set<LocalToolOption> {
        val minimum = setOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard, LocalToolOption.AskUser)
        val helper = minimum + setOf(LocalToolOption.Files, LocalToolOption.Notification,
            LocalToolOption.Calendar, LocalToolOption.Browser) +
            categories.first { it.id == Category.Device.id }.options
        return when (preset) {
            LocalToolPreset.Minimum -> minimum
            LocalToolPreset.Helper -> helper
            LocalToolPreset.Agent -> helper + setOf(LocalToolOption.Root, LocalToolOption.Termux,
                LocalToolOption.ScreenAutomation, LocalToolOption.SubAgents, LocalToolOption.CronJobs,
                LocalToolOption.Workflows, LocalToolOption.SkillImport, LocalToolOption.JsSkills)
            LocalToolPreset.All -> entries.mapTo(linkedSetOf()) { it.option }
            LocalToolPreset.None -> emptySet()
        }
    }

    private fun tool(
        category: Category,
        option: LocalToolOption,
        @StringRes titleRes: Int,
        @StringRes descriptionRes: Int,
        icon: ImageVector,
        requiresRoot: Boolean = false,
        background: Boolean = false,
    ): Registration = Registration(category,
        LocalToolUiEntry(option, titleRes, descriptionRes, icon, requiresRoot, background))
}
