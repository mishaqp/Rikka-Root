package me.rerere.rikkahub.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.costguards.TokenBudgetStore
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.RunWebTaint
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.missingLocalToolPermissions
import me.rerere.rikkahub.data.ai.tools.resolveToolAutoApproval
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Headless calls have a fresh run scope, never the persisted creator chat's temporary grants. */
internal suspend fun resolveHeadlessAutoApproval(
    preferences: ToolApprovalPreferences,
    execution: RunExecutionContext,
    toolName: String,
    arguments: JsonElement,
    webContentSeen: Boolean,
    workspaceNeedsApproval: suspend () -> Boolean = { true },
): Boolean = HeadlessToolPolicy.blockReason(toolName, arguments) == null && resolveToolAutoApproval(
    preferences, execution.runId, toolName,
    webContentSeen = webContentSeen || execution.webTaint.isTainted(),
    workspaceNeedsApproval = workspaceNeedsApproval,
)

/** Production wiring of live permissions. No method here opens Android or root-manager UI. */
class HeadlessRuntimeBindings(
    private val androidContext: Context,
    private val settingsStore: SettingsStore,
    private val preferences: ToolApprovalPreferences,
    private val rootAccessStore: RootAccessStore,
    private val workspaceRepository: WorkspaceRepository,
    private val rootShellManager: RootShellManager,
    private val tokenBudgetStore: TokenBudgetStore,
) {
    private val taints = ConcurrentHashMap<String, RunWebTaint>()

    fun webTaintFor(conversationId: String): RunWebTaint = taints.computeIfAbsent(conversationId) {
        RunWebTaint(initial = rootAccessStore.isWebTainted(it))
    }.also { if (rootAccessStore.isWebTainted(conversationId)) it.mark() }

    suspend fun markWebContent(conversationId: String) {
        webTaintFor(conversationId).mark()
        if (preferences.currentAskAfterWebContent()) rootAccessStore.markWebContent(conversationId)
    }

    fun hasWebContent(conversationId: String): Boolean =
        taints[conversationId]?.isTainted() == true || rootAccessStore.isWebTainted(conversationId)

    suspend fun onWebContent(execution: RunExecutionContext) {
        execution.webTaint.mark()
        markWebContent(execution.runId.toString())
        execution.callerConversationId?.let { markWebContent(it.toString()) }
    }

    fun subAgentsEnabled(ownerId: Uuid): Boolean = settingsStore.settingsFlow.value.assistants
        .singleOrNull { it.id == ownerId }?.localTools?.contains(LocalToolOption.SubAgents) == true

    fun subAgentConcurrency(ownerId: Uuid): Int = settingsStore.settingsFlow.value.assistants
        .singleOrNull { it.id == ownerId }?.subAgentConcurrencyLimit?.coerceIn(1, 8) ?: 3

    suspend fun featureEnabled(execution: RunExecutionContext): Boolean {
        if (!execution.runStillAllowed()) return false
        val assistant = settingsStore.settingsFlow.value.assistants.singleOrNull { it.id == execution.ownerAssistantId }
            ?: return false
        return when (execution.origin) {
            RunOrigin.SUB_AGENT -> LocalToolOption.SubAgents in assistant.localTools
            RunOrigin.CRON -> LocalToolOption.CronJobs in assistant.localTools
        }
    }

    suspend fun authorize(execution: RunExecutionContext, name: String, arguments: JsonElement): Boolean {
        if (!featureEnabled(execution)) return false
        val assistant = settingsStore.settingsFlow.value.assistants.singleOrNull { it.id == execution.ownerAssistantId }
            ?: return false
        val parentWeb = execution.callerConversationId?.let { hasWebContent(it.toString()) } == true
        if (parentWeb) execution.webTaint.mark()
        return resolveHeadlessAutoApproval(preferences, execution, name, arguments,
            webContentSeen = parentWeb || hasWebContent(execution.runId.toString()),
            workspaceNeedsApproval = {
                val workspaceId = if (execution.callerConversationConfig != null) execution.callerConversationConfig.workspaceId else assistant.workspaceId
                val workspace = workspaceId?.let { workspaceRepository.getById(it.toString()) }
                workspace == null || resolveWorkspaceToolApproval(name, workspace.toolApprovalOverrides(), arguments)
            })
    }

    suspend fun preflight(execution: RunExecutionContext, name: String): String? {
        if (!featureEnabled(execution)) return "feature_disabled"
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.singleOrNull { it.id == execution.ownerAssistantId }
            ?: return "unknown_owner"
        if (execution.allowedTools != null && name !in execution.allowedTools) return "tool_not_allowed"
        val option = localOptionForHeadlessTool(name)
        if (option != null) {
            if (option !in assistant.localTools) return "tool_feature_disabled"
            if (missingLocalToolPermissions(androidContext, option).isNotEmpty()) return "android_permission_required"
        }
        val config = execution.callerConversationConfig
        when {
            name == "root_exec" && !rootShellManager.isHeadlessReady() -> return "root_interaction_required"
            name == "calendar_query" && !granted(Manifest.permission.READ_CALENDAR) -> return "android_permission_required"
            name == "calendar_create" && (!granted(Manifest.permission.READ_CALENDAR) || !granted(Manifest.permission.WRITE_CALENDAR)) -> return "android_permission_required"
            name == "set_brightness" && !android.provider.Settings.System.canWrite(androidContext) -> return "android_permission_required"
            name == "post_notification" && !androidx.core.app.NotificationManagerCompat.from(androidContext).areNotificationsEnabled() -> return "android_permission_required"
            name.startsWith("workspace_") -> {
                val workspaceId = if (config != null) config.workspaceId else assistant.workspaceId
                val workspace = workspaceId?.let { workspaceRepository.getById(it.toString()) }
                if (workspace?.shellStatus != WorkspaceShellStatus.READY.name) return "workspace_unavailable"
            }
            name.startsWith("mcp__") -> {
                val enabled = config?.mcpServers ?: assistant.mcpServers
                val present = settings.mcpServers.any { server ->
                    server.id in enabled && server.commonOptions.enable && server.commonOptions.tools.any { tool ->
                        tool.enable && name == "mcp__${server.commonOptions.name}__${tool.name}"
                    }
                }
                if (!present) return "tool_feature_disabled"
            }
            name == "memory_tool" && !assistant.enableMemory -> return "tool_feature_disabled"
            name in setOf("search_web", "scrape_web") && !(config?.enableWebSearch ?: assistant.enableWebSearch) -> return "tool_feature_disabled"
            name in setOf("recent_chats", "conversation_search") && !assistant.enableRecentChatsReference -> return "tool_feature_disabled"
            name == "use_skill" && (config?.enabledSkills ?: assistant.enabledSkills).isEmpty() -> return "tool_feature_disabled"
        }
        return null
    }

    fun createRunner(generationLoop: GenerationLoop, toolFactory: ChatToolFactory): HeadlessTaskRunner = HeadlessTaskRunner(
        generationLoop = generationLoop, toolFactory = toolFactory,
        settings = { settingsStore.settingsFlow.value },
        journal = AtomicHeadlessRunJournal(File(androidContext.noBackupFilesDir, "headless-runs")),
        authorize = ::authorize, preflight = ::preflight, featureEnabled = ::featureEnabled,
        onWebContent = ::onWebContent,
        budgetFor = { execution, assistant -> tokenBudgetStore.getLedger(assistant, execution.runId, emptyList()) },
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(androidContext, permission) == PackageManager.PERMISSION_GRANTED
}

/** Current capability switch is rechecked even when the tools were created before a settings change. */
internal fun localOptionForHeadlessTool(name: String): LocalToolOption? = when (name) {
    "list_files", "read_file", "write_binary_file", "delete_file", "move_file", "copy_file", "create_directory",
    "file_info", "find_files", "show_image", "open_file", "batch_copy", "batch_move", "batch_delete" -> LocalToolOption.Files
    "list_storage_volumes", "list_granted_directories", "grant_directory_access" -> LocalToolOption.ExternalStorage
    "zip_files", "unzip_file", "list_zip_contents" -> LocalToolOption.Archive
    "play_media", "stop_media", "pause_media", "resume_media", "seek_media", "get_media_status" -> LocalToolOption.MediaPlayer
    "scan_media" -> LocalToolOption.MediaScanner
    "download_file", "write_text_file" -> LocalToolOption.Download
    "create_calendar_event", "create_contact", "send_email_intent", "send_sms_intent", "open_wifi_settings", "show_location_on_map" -> LocalToolOption.SystemIntents
    "launch_app", "list_installed_apps", "open_url", "list_app_activities", "launch_activity" -> LocalToolOption.AppLauncher
    "get_location" -> LocalToolOption.Location
    "list_contacts", "search_contacts" -> LocalToolOption.Contacts
    "list_call_log" -> LocalToolOption.CallLog
    "list_sms_inbox", "search_sms" -> LocalToolOption.SmsInbox
    "send_sms" -> LocalToolOption.SmsSend
    "take_photo" -> LocalToolOption.CameraPhoto
    "record_audio" -> LocalToolOption.MicRecorder
    "speech_to_text" -> LocalToolOption.SpeechToText
    "verify_fingerprint" -> LocalToolOption.Fingerprint
    "root_exec" -> LocalToolOption.Root
    "eval_javascript" -> LocalToolOption.JavascriptEngine
    "get_time_info" -> LocalToolOption.TimeInfo
    "clipboard_tool" -> LocalToolOption.Clipboard
    "text_to_speech" -> LocalToolOption.Tts
    "ask_user" -> LocalToolOption.AskUser
    "get_screen_time" -> LocalToolOption.ScreenTime
    "calendar_query", "calendar_create" -> LocalToolOption.Calendar
    "chart_display" -> LocalToolOption.ChartDisplay
    "get_battery_status" -> LocalToolOption.Battery
    "get_audio_info" -> LocalToolOption.AudioInfo
    "get_telephony_info" -> LocalToolOption.TelephonyInfo
    "get_wifi_info" -> LocalToolOption.WifiInfo
    "list_sensors", "read_sensor" -> LocalToolOption.Sensors
    "get_storage_info" -> LocalToolOption.StorageInfo
    "show_toast" -> LocalToolOption.Toast
    "post_notification" -> LocalToolOption.Notification
    "share" -> LocalToolOption.Share
    "set_torch" -> LocalToolOption.Torch
    "vibrate" -> LocalToolOption.Vibrate
    "get_brightness", "set_brightness" -> LocalToolOption.Brightness
    "get_volume", "set_volume" -> LocalToolOption.Volume
    "set_wallpaper" -> LocalToolOption.Wallpaper
    "nfc_status", "nfc_read_tag", "nfc_write_tag" -> LocalToolOption.Nfc
    "check_token_usage" -> LocalToolOption.CostGuards
    "generate_bug_report" -> LocalToolOption.Reliability
    "subagent_dispatch", "subagent_get", "subagent_list", "subagent_cancel" -> LocalToolOption.SubAgents
    "schedule_job", "list_jobs", "get_job_history", "delete_job", "pause_job", "resume_job", "trigger_job_now" -> LocalToolOption.CronJobs
    else -> if (name.startsWith("keystore_")) LocalToolOption.Keystore else null
}
