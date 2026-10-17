package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.mcp.control.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.tts.provider.TTSManager
import java.io.File
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.costguards.checkTokenUsageTool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext.get
import kotlin.uuid.Uuid
import me.rerere.rikkahub.reliability.generateBugReportTool
import me.rerere.rikkahub.reliability.BugReportBuilder
import me.rerere.rikkahub.reliability.copyBugReportToWorkspace
import me.rerere.rikkahub.service.ChatService

/** The file-backed tool dispatcher shares one scoped resolver for the selected workspace. */
internal fun scopedFileTools(
    context: Context,
    options: List<LocalToolOption>,
    workspaceCwd: String? = null,
    chatImages: List<String> = emptyList(),
    resolveWorkspacePath: (suspend (String) -> File)? = null,
    modelCanReadImages: Boolean = false,
    guard: (LocalToolOption, Tool) -> Tool = { _, tool -> tool },
): List<Tool> = buildList {
    if (options.any { it in listOf(LocalToolOption.Files, LocalToolOption.Archive,
            LocalToolOption.MediaPlayer, LocalToolOption.MediaScanner, LocalToolOption.Download,
            LocalToolOption.QrCodes, LocalToolOption.ReadDocument) }) {
        val access = LocalFileAccess(context, workspaceCwd, chatImages, resolveWorkspacePath)
        if (LocalToolOption.QrCodes in options) addAll(qrCodeTools(context, access, modelCanReadImages).map { guard(LocalToolOption.QrCodes, it) })
        if (LocalToolOption.ReadDocument in options) add(guard(LocalToolOption.ReadDocument, readDocumentTool(context, access)))
        if (LocalToolOption.Files in options) addAll(fileManagerTools(access, context, modelCanReadImages).map { guard(LocalToolOption.Files, it) })
        if (LocalToolOption.Archive in options) addAll(listOf(zipFilesTool(context, access), unzipFileTool(context, access), listZipContentsTool(context, access)).map { guard(LocalToolOption.Archive, it) })
        if (LocalToolOption.MediaPlayer in options) addAll(listOf(playMediaTool(context, access), stopMediaTool(context), pauseMediaTool(context), resumeMediaTool(context, access), seekMediaTool(context), getMediaStatusTool()).map { guard(LocalToolOption.MediaPlayer, it) })
        if (LocalToolOption.MediaScanner in options) add(guard(LocalToolOption.MediaScanner, mediaScannerTool(context, access)))
        if (LocalToolOption.Download in options) addAll(listOf(downloadTool(context, access), writeTextFileTool(access)).map { guard(LocalToolOption.Download, it) })
    }
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val rootShellManager: RootShellManager,
    private val rootAccessStore: RootAccessStore,
    private val filesManager: FilesManager,
    private val termuxPreferences: me.rerere.rikkahub.data.preferences.TermuxPreferences,
    private val sshHostRepository: me.rerere.rikkahub.data.repository.SshHostRepository,
    private val browserPreferences: me.rerere.rikkahub.browser.BrowserPreferences? = null,
) {
    private val keystoreCrypto by lazy { KeystoreCrypto(AndroidToolKeyStore(context)) }
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    fun getTools(
        options: List<LocalToolOption>, assistantId: String? = null, conversationId: String? = null,
        workspaceCwd: String? = null, chatImages: List<String> = emptyList(),
        resolveWorkspacePath: (suspend (String) -> File)? = null,
        modelCanReadImages: Boolean = false,
        callerAssistant: Assistant? = null,
        tokenBudget: TokenBudgetLedger? = null,
        mcpCaller: McpControlCaller? = null,
        skillCaller: me.rerere.rikkahub.skills.SkillInstallCaller? = null,
        knownToolNames: (() -> Collection<String>)? = null,
        managerCaller: AssistantManagerCaller? = null,
        managerCatalog: (suspend () -> AssistantManagerCatalog)? = null,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        val callerId = assistantId ?: callerAssistant?.id?.toString()
        fun guard(option: LocalToolOption, tool: Tool): Tool = tool.copy(execute = { input ->
            // localTools belongs to the stored assistant, not the conversation's workspace/config snapshot.
            val live = settingsStore.settingsFlow.value.assistants.singleOrNull { it.id.toString() == callerId }
            val reason = when {
                live == null -> "Ассистент недоступен. Откройте чат заново перед вызовом инструмента."
                option !in live.localTools -> "Функция отключена у ассистента. Включите её в настройках инструментов перед новым вызовом."
                else -> null
            }
            if (reason != null) listOf(UIMessagePart.Text(deviceToolError(reason).toString())) else tool.execute(input)
        })
        fun add(option: LocalToolOption, tool: Tool) { tools.add(guard(option, tool)) }
        fun addAll(option: LocalToolOption, values: List<Tool>) { values.forEach { add(option, it) } }
        if (LocalToolOption.ManageAssistants in options) {
            val caller = managerCaller ?: AssistantManagerCaller(
                currentAssistantId = { callerId?.let { runCatching { Uuid.parse(it) }.getOrNull() } },
                switchAssistant = { id ->
                    settingsStore.update { live ->
                        require(live.assistants.any { it.id == id }) { "Ассистент больше недоступен. Обновите список." }
                        live.copy(assistantId = id)
                    }
                },
            )
            add(LocalToolOption.ManageAssistants, manageAssistantsTool(settingsStore,
                catalog = managerCatalog ?: {
                    val skills = get().get<me.rerere.rikkahub.data.files.SkillManager>().listSkills()
                    val workspaces = get().get<me.rerere.rikkahub.data.repository.WorkspaceRepository>().listFlow().first()
                    AssistantManagerCatalog(
                        skills = skills.map { AssistantManagerOption(it.name, it.name, it.description) },
                        workspaces = workspaces.map { AssistantManagerOption(it.id, it.name) },
                        localTools = me.rerere.rikkahub.ui.components.ai.tools.LocalToolUiRegistry.entries.map {
                            AssistantManagerOption(localToolId(it.option), it.title(context), it.description(context))
                        },
                    )
                }, caller = caller))
        }
        if (LocalToolOption.ManageAppSettings in options) {
            add(LocalToolOption.ManageAppSettings, buildManageAppSettingsTool(settingsStore, context))
        }
        if (LocalToolOption.Browser in options) {
            val enabled = (browserPreferences ?: get().get<me.rerere.rikkahub.browser.BrowserPreferences>()).snapshotBlocking()
            val invocation = me.rerere.rikkahub.browser.ToolInvocationContext(
                callerAssistantId = assistantId, callerConversationId = conversationId, modelCanSeeImages = modelCanReadImages)
            me.rerere.rikkahub.browser.BrowserToolDefaults.ALL_TOOLS.forEach { name ->
                if (enabled[name] == true) createBrowserTool(name, context, invocation)?.let { add(LocalToolOption.Browser, it) }
            }
        }
        if (LocalToolOption.SkillImport in options) {
            val manager = get().get<me.rerere.rikkahub.data.files.SkillManager>()
            val caller = skillCaller ?: me.rerere.rikkahub.skills.SkillInstallCaller(
                enabledSkills = { callerAssistant?.enabledSkills ?: emptySet() },
                saveEnabledSkills = { error("Для установки навыка нужен подтверждённый контекст текущего чата.") })
            val importer = get().get<me.rerere.rikkahub.skills.SkillUrlImporter>()
            add(LocalToolOption.SkillImport, me.rerere.rikkahub.skills.skillInstallFromUrlTool(importer, caller, manager))
            add(LocalToolOption.SkillImport, me.rerere.rikkahub.skills.skillInstallFromTextTool(importer, caller, manager))
        }
        if (LocalToolOption.JsSkills in options) add(LocalToolOption.JsSkills, me.rerere.rikkahub.skills.js.runJsTool(
            context, get().get(), get().get(), get().get()))
        if (LocalToolOption.ScreenAutomation in options) addAll(LocalToolOption.ScreenAutomation, createScreenAutomationTools(context, rootShellManager) { name, input ->
            val live = settingsStore.settingsFlow.value.assistants.singleOrNull { it.id.toString() == assistantId }
            if (live == null || LocalToolOption.ScreenAutomation !in live.localTools) false
            else {
                val execution = kotlinx.coroutines.currentCoroutineContext()[me.rerere.rikkahub.data.ai.tools.RunExecutionContext]
                if (execution == null) true
                else get().get<me.rerere.rikkahub.service.HeadlessRuntimeBindings>().let { bindings ->
                    bindings.preflight(execution, name) == null && bindings.authorize(execution, name, input)
                }
            }
        })
        if (LocalToolOption.ExternalAutomation in options) {
            val config = get().get<me.rerere.rikkahub.automation.ExternalAutomationConfig>()
            addAll(LocalToolOption.ExternalAutomation, listOf(me.rerere.rikkahub.automation.externalAutomationStatusTool(config),
                me.rerere.rikkahub.automation.externalAutomationSetEnabledTool(config),
                me.rerere.rikkahub.automation.externalAutomationAddTrustedPackageTool(config),
                me.rerere.rikkahub.automation.externalAutomationRemoveTrustedPackageTool(config)))
        }
        if (LocalToolOption.McpControl in options) {
            val manager = get().get<me.rerere.rikkahub.data.ai.mcp.McpManager>()
            val secrets = get().get<McpControlSecretStore>()
            val sanitizer = get().get<McpToolSecretSanitizer>()
            val caller = mcpCaller ?: McpControlCaller(
                assistantMcpServers = { callerAssistant?.mcpServers ?: emptySet() },
                addServer = { error("Контекст текущего чата MCP недоступен.") },
                removeServer = { error("Контекст текущего чата MCP недоступен.") })
            addAll(LocalToolOption.McpControl, listOf(mcpListTool(settingsStore, manager, caller), mcpGetTool(settingsStore, manager, caller),
                mcpAddTool(settingsStore, manager, caller, secrets), mcpUpdateTool(settingsStore, manager, secrets),
                mcpDeleteTool(settingsStore, manager, caller), mcpSetEnabledTool(settingsStore, manager, secrets),
                mcpTestTool(settingsStore, manager, secrets), mcpListToolsTool(settingsStore, manager, caller),
                mcpSetToolApprovalTool(settingsStore)).map { tool ->
                    tool.copy(execute = { input ->
                        tool.execute(kotlinx.serialization.json.Json.parseToJsonElement(
                            sanitizer.sanitizeForPersistence(tool.name, input.toString())))
                    })
                })
        }
        if (LocalToolOption.Ssh in options) {
            val access = LocalFileAccess(context, workspaceCwd, chatImages, resolveWorkspacePath)
            addAll(LocalToolOption.Ssh, listOf(sshExecTool(context, sshHostRepository.toolSecrets),
                saveSshHostTool(sshHostRepository), listSshHostsTool(sshHostRepository),
                deleteSshHostTool(sshHostRepository), forgetSshHostKeyTool(context),
                sshExecSavedTool(context, sshHostRepository), sshUploadTool(context, sshHostRepository, access),
                sshDownloadTool(context, sshHostRepository, access)))
        }
        if (LocalToolOption.Termux in options) addAll(LocalToolOption.Termux, listOf(
            termuxRunCommandTool(context), termuxSessionStartTool(context), termuxSessionSendTool(context),
            termuxSessionReadTool(context), termuxSessionKillTool(context), termuxSessionListTool(context)))
        if (LocalToolOption.Reliability in options) add(LocalToolOption.Reliability, generateBugReportTool(
            context, BugReportBuilder(context) {
                get().get<ChatService>().errors.value.map { it.error }
            }, copyToWorkspace = resolveWorkspacePath?.let { resolver ->
                { zip -> copyBugReportToWorkspace(zip,
                    LocalFileAccess(context, workspaceCwd = "/workspace", resolveWorkspacePath = resolver)) }
            },
        ))
        if (LocalToolOption.CostGuards in options) {
            val caller = callerAssistant ?: Assistant(
                id = assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random(),
                localTools = options,
            )
            add(LocalToolOption.CostGuards, checkTokenUsageTool(callerAssistant = caller,
                conversationId = conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                tokenBudget = tokenBudget,
                loadConversation = { id -> get().get<ConversationRepository>().getConversationById(id) }))
        }
        if (LocalToolOption.HttpRequest in options) add(LocalToolOption.HttpRequest, httpRequestTool(get().get<OkHttpClient>()))
        if (LocalToolOption.AlarmClock in options) addAll(LocalToolOption.AlarmClock, alarmClockTools(context))
        tools.addAll(scopedFileTools(context, options, workspaceCwd, chatImages, resolveWorkspacePath, modelCanReadImages, ::guard))
        if (LocalToolOption.ExternalStorage in options) addAll(LocalToolOption.ExternalStorage, listOf(listStorageVolumesTool(context), listGrantedDirectoriesTool(context), grantDirectoryAccessTool(context), revokeDirectoryAccessTool(context)))
        if (LocalToolOption.SystemIntents in options) addAll(LocalToolOption.SystemIntents, systemIntentTools(context))
        if (LocalToolOption.AppLauncher in options) addAll(LocalToolOption.AppLauncher, appLauncherTools(context))
        if (LocalToolOption.Location in options) add(LocalToolOption.Location, locationTool(context))
        if (LocalToolOption.Contacts in options) { add(LocalToolOption.Contacts, listContactsTool(context)); add(LocalToolOption.Contacts, searchContactsTool(context)) }
        if (LocalToolOption.CallLog in options) add(LocalToolOption.CallLog, callLogTool(context))
        if (LocalToolOption.SmsInbox in options) { add(LocalToolOption.SmsInbox, listSmsInboxTool(context)); add(LocalToolOption.SmsInbox, searchSmsTool(context)) }
        if (LocalToolOption.SmsSend in options) add(LocalToolOption.SmsSend, smsSendTool(context))
        if (LocalToolOption.CameraPhoto in options) add(LocalToolOption.CameraPhoto, cameraPhotoTool(context, filesManager))
        if (LocalToolOption.MicRecorder in options) add(LocalToolOption.MicRecorder, micRecorderTool(context, filesManager))
        if (LocalToolOption.SpeechToText in options) add(LocalToolOption.SpeechToText, speechToTextTool(context))
        if (LocalToolOption.Fingerprint in options) add(LocalToolOption.Fingerprint, fingerprintTool(context))
        if (LocalToolOption.Keystore in options) addAll(LocalToolOption.Keystore, keystoreTools(context, keystoreCrypto))
        if (LocalToolOption.Battery in options) add(LocalToolOption.Battery, batteryTool(context))
        if (LocalToolOption.AudioInfo in options) add(LocalToolOption.AudioInfo, audioInfoTool(context))
        if (LocalToolOption.TelephonyInfo in options) add(LocalToolOption.TelephonyInfo, telephonyInfoTool(context))
        if (LocalToolOption.WifiInfo in options) add(LocalToolOption.WifiInfo, wifiInfoTool(context))
        if (LocalToolOption.Sensors in options) {
            add(LocalToolOption.Sensors, listSensorsTool(context))
            add(LocalToolOption.Sensors, readSensorTool(context))
        }
        if (LocalToolOption.StorageInfo in options) add(LocalToolOption.StorageInfo, storageTool())
        if (LocalToolOption.Toast in options) add(LocalToolOption.Toast, toastTool(context))
        if (LocalToolOption.Notification in options) add(LocalToolOption.Notification, notificationTool(context, conversationId))
        if (LocalToolOption.Share in options) add(LocalToolOption.Share, shareTool(context))
        if (LocalToolOption.Torch in options) add(LocalToolOption.Torch, torchTool(context))
        if (LocalToolOption.Vibrate in options) add(LocalToolOption.Vibrate, vibrateTool(context))
        if (LocalToolOption.Brightness in options) {
            add(LocalToolOption.Brightness, getBrightnessTool(context))
            add(LocalToolOption.Brightness, setBrightnessTool(context))
        }
        if (LocalToolOption.Volume in options) {
            add(LocalToolOption.Volume, getVolumeTool(context))
            add(LocalToolOption.Volume, setVolumeTool(context))
        }
        if (LocalToolOption.Wallpaper in options) add(LocalToolOption.Wallpaper, setWallpaperTool(context,
            LocalImageSources(File(context.filesDir, "upload"), workspaceCwd, chatImages, resolveWorkspacePath,
                LocalFileAccess(context, workspaceCwd, chatImages, resolveWorkspacePath))))
        if (LocalToolOption.Nfc in options) {
            add(LocalToolOption.Nfc, nfcStatusTool(context))
            add(LocalToolOption.Nfc, nfcReadTagTool(context))
            add(LocalToolOption.Nfc, nfcWriteTagTool(context))
        }
        if (options.contains(LocalToolOption.Root)) {
            add(LocalToolOption.Root, buildRootTool(rootShellManager, rootAccessStore, assistantId))
        }
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            add(LocalToolOption.JavascriptEngine, javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            add(LocalToolOption.TimeInfo, timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            add(LocalToolOption.Clipboard, clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            add(LocalToolOption.Tts, ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            add(LocalToolOption.AskUser, askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            add(LocalToolOption.ScreenTime, screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            add(LocalToolOption.Calendar, calendarQueryTool)
            add(LocalToolOption.Calendar, calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) {
            add(LocalToolOption.ChartDisplay, chartDisplayTool)
        }
        if (LocalToolOption.Workflows in options) {
            val repo = get().get<me.rerere.rikkahub.workflow.repository.WorkflowRepository>()
            val engine = get().get<me.rerere.rikkahub.workflow.execution.WorkflowEngine>()
            val config = callerAssistant?.let { me.rerere.rikkahub.data.model.ConversationConfig(
                chatModelId = it.chatModelId, reasoningLevel = it.reasoningLevel, enableWebSearch = it.enableWebSearch,
                mcpServers = it.mcpServers, workspaceId = it.workspaceId, enabledSkills = it.enabledSkills) }
            val registered = { knownToolNames?.invoke()?.toList() ?: tools.map { it.name } }
            addAll(LocalToolOption.Workflows, listOf(
                me.rerere.rikkahub.workflow.tools.workflowCreateTool(repo, registered, assistantId, conversationId, config),
                me.rerere.rikkahub.workflow.tools.workflowUpdateTool(repo, registered, assistantId, conversationId, config),
                me.rerere.rikkahub.workflow.tools.workflowListTool(repo), me.rerere.rikkahub.workflow.tools.workflowGetTool(repo),
                me.rerere.rikkahub.workflow.tools.workflowDeleteTool(repo, assistantId),
                me.rerere.rikkahub.workflow.tools.workflowSetEnabledTool(repo, assistantId),
                me.rerere.rikkahub.workflow.tools.workflowRunTool(engine, repo, assistantId)))
        }
        return tools
    }
}
