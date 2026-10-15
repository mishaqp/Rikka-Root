package me.rerere.rikkahub.ui.components.ai.tools

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.cancel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.automation.ExternalAutomationConfig
import me.rerere.rikkahub.automation.externalAutomationAddTrustedPackageTool
import me.rerere.rikkahub.automation.externalAutomationRemoveTrustedPackageTool
import me.rerere.rikkahub.automation.externalAutomationSetEnabledTool
import me.rerere.rikkahub.automation.externalAutomationStatusTool
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.ToolInvocationContext
import me.rerere.rikkahub.costguards.checkTokenUsageTool
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.control.*
import me.rerere.rikkahub.data.ai.tools.local.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.reliability.BugReportBuilder
import me.rerere.rikkahub.reliability.generateBugReportTool
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.service.HeadlessRunJournal
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.skills.SkillInstallCaller
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.asSaver
import me.rerere.rikkahub.skills.js.JsSkillRunner
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.skills.js.runJsTool
import me.rerere.rikkahub.skills.skillInstallFromTextTool
import me.rerere.rikkahub.skills.skillInstallFromUrlTool
import me.rerere.rikkahub.subagent.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.tools.*
import me.rerere.tts.provider.TTSManager
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/**
 * UI-only definitions from the production factories, including disabled capabilities.
 * Runtime managers with startup behavior use an isolated, already-cancelled scope.
 * Every returned definition rejects execution before reaching its factory callback.
 */
class LocalToolMetadata(context: Context) {
    private val context = context.applicationContext
    private val koin get() = GlobalContext.get()
    private val settings by lazy { koin.get<SettingsStore>() }
    private val files by lazy { koin.get<FilesManager>() }
    private val skills by lazy { koin.get<SkillManager>() }
    private val ssh by lazy { koin.get<SshHostRepository>() }
    private val eventBus = AppEventBus()
    private val rootManager = RootShellManager()
    private val cancelledScope = AppScope().apply { cancel() }
    private val mcpSecrets by lazy { koin.get<McpControlSecretStore>() }
    private val mcpManager by lazy { McpManager(settings, cancelledScope, files, mcpSecrets) }
    private val importer by lazy { SkillUrlImporter(skills.asSaver(), OkHttpClient()) }
    private val jsRunner = JsSkillRunner(this.context)
    private val jsSecrets by lazy { koin.get<SkillSecretsStore>() }
    private val tts by lazy { koin.get<TTSManager>() }
    private val keystore by lazy { KeystoreCrypto(AndroidToolKeyStore(this.context)) }
    private val workflowRepository by lazy { koin.get<WorkflowRepository>() }
    private val workflowEngine by lazy {
        WorkflowEngine(workflowRepository, settings, ContextProvider(this.context),
            WorkflowActionRunner { _, _, _ -> metadataOnly() })
    }
    private val subagentRegistry = SubAgentRegistry()
    private val subagentEngine by lazy {
        // Provider constructors migrate a legacy key cache when it exists. Metadata
        // receives an absent cache path without creating it or opening the real cache.
        val absentCache = File(this.context.filesDir, ".ui-tool-metadata-${Uuid.random()}")
        check(!absentCache.exists()) { "Metadata cache path must not exist" }
        val providerContext = object : ContextWrapper(this.context) {
            override fun getCacheDir(): File = absentCache
        }
        val generationLoop = GenerationLoop(this.context,
            ProviderManager(OkHttpClient(), providerContext), JsonInstant)
        val runner = HeadlessTaskRunner(
            generationLoop = generationLoop,
            toolSource = { _, _, _, _ -> metadataOnly() },
            settings = { metadataOnly() },
            journal = object : HeadlessRunJournal {
                override suspend fun claim(runId: String): Boolean = metadataOnly()
                override suspend fun checkpoint(runId: String, toolCallIds: Set<String>): Unit = metadataOnly()
            },
            authorize = { _, _, _ -> false },
            preflight = { _, _ -> "ui_metadata_only" },
            featureEnabled = { false },
        )
        SubAgentEngine(subagentRegistry, runner, { metadataOnly() }, cancelledScope,
            enabled = { false }, concurrencyLimit = { 1 })
    }

    fun toolsForAssistant(
        assistant: Assistant,
        model: Model? = null,
        activeBrowserPreferences: Map<String, Boolean>? = null,
    ): Map<LocalToolOption, List<Tool>> {
        val canReadImages = model?.inputModalities?.contains(Modality.IMAGE) == true
        val callerId = assistant.id.toString()
        val conversationId = Uuid.random()
        val conversationConfig = ConversationConfig(
            chatModelId = model?.id ?: assistant.chatModelId,
            reasoningLevel = assistant.reasoningLevel,
            enableWebSearch = assistant.enableWebSearch,
            builtInSearch = model?.tools?.contains(BuiltInTools.Search) == true,
            mcpServers = assistant.mcpServers,
            workspaceId = assistant.workspaceId,
            enabledSkills = assistant.enabledSkills,
        )
        val caller = Conversation(
            id = conversationId,
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = conversationConfig,
            workspaceCwd = assistant.workspaceId?.let { "/workspace" },
        )
        val resolveWorkspace: (suspend (String) -> File)? =
            if (assistant.workspaceId == null) null else { _: String -> metadataOnly() }
        val access by lazy {
            // The existing files directory makes LocalFileAccess's mkdirs a no-op.
            // This resolver is retained only to produce the correct workspace scope text.
            check(context.filesDir.isDirectory) { "Application files directory is unavailable" }
            LocalFileAccess(
                scratchDir = context.filesDir,
                uploadDir = File(context.filesDir, "upload"),
                workspaceCwd = caller.workspaceCwd,
                resolveWorkspacePath = resolveWorkspace,
                context = context,
            )
        }
        val knownToolNames = { emptyList<String>() }
        return LocalToolUiRegistry.entries.associate { entry ->
            val tools = when (entry.option) {
                LocalToolOption.Browser -> BrowserToolDefaults.ALL_TOOLS
                    .filter { activeBrowserPreferences == null || activeBrowserPreferences[it] == true }
                    .mapNotNull { name -> createBrowserTool(name, context,
                        ToolInvocationContext(callerAssistantId = callerId,
                            callerConversationId = conversationId.toString(), modelCanSeeImages = canReadImages)) }
                LocalToolOption.SkillImport -> {
                    val installCaller = SkillInstallCaller(
                        enabledSkills = { assistant.enabledSkills }, saveEnabledSkills = { metadataOnly() })
                    listOf(skillInstallFromUrlTool(importer, installCaller, skills),
                        skillInstallFromTextTool(importer, installCaller, skills))
                }
                LocalToolOption.JsSkills -> listOf(runJsTool(context, skills, jsRunner, jsSecrets))
                LocalToolOption.ScreenAutomation -> createScreenAutomationTools(context, rootManager) { _, _ -> false }
                LocalToolOption.Workflows -> listOf(
                    workflowCreateTool(workflowRepository, knownToolNames, callerId, conversationId.toString(), conversationConfig),
                    workflowUpdateTool(workflowRepository, knownToolNames, callerId, conversationId.toString(), conversationConfig),
                    workflowListTool(workflowRepository), workflowGetTool(workflowRepository),
                    workflowDeleteTool(workflowRepository, callerId), workflowSetEnabledTool(workflowRepository, callerId),
                    workflowRunTool(workflowEngine, workflowRepository, callerId),
                )
                LocalToolOption.ExternalAutomation -> {
                    val config = koin.get<ExternalAutomationConfig>()
                    listOf(externalAutomationStatusTool(config), externalAutomationSetEnabledTool(config),
                        externalAutomationAddTrustedPackageTool(config), externalAutomationRemoveTrustedPackageTool(config))
                }
                LocalToolOption.McpControl -> {
                    val mcpCaller = McpControlCaller(
                        assistantMcpServers = { assistant.mcpServers },
                        addServer = { metadataOnly() }, removeServer = { metadataOnly() })
                    listOf(mcpListTool(settings, mcpManager, mcpCaller), mcpGetTool(settings, mcpManager, mcpCaller),
                        mcpAddTool(settings, mcpManager, mcpCaller, mcpSecrets), mcpUpdateTool(settings, mcpManager, mcpSecrets),
                        mcpDeleteTool(settings, mcpManager, mcpCaller), mcpSetEnabledTool(settings, mcpManager, mcpSecrets),
                        mcpTestTool(settings, mcpManager, mcpSecrets), mcpListToolsTool(settings, mcpManager, mcpCaller),
                        mcpSetToolApprovalTool(settings))
                }
                LocalToolOption.Ssh -> listOf(sshExecTool(context, ssh.toolSecrets), saveSshHostTool(ssh),
                    listSshHostsTool(ssh), deleteSshHostTool(ssh), forgetSshHostKeyTool(context),
                    sshExecSavedTool(context, ssh), sshUploadTool(context, ssh, access), sshDownloadTool(context, ssh, access))
                LocalToolOption.Termux -> listOf(termuxRunCommandTool(context), termuxSessionStartTool(context),
                    termuxSessionSendTool(context), termuxSessionReadTool(context), termuxSessionKillTool(context),
                    termuxSessionListTool(context))
                LocalToolOption.SubAgents -> {
                    val owner = SubAgentOwner(callerId, conversationId.toString())
                    listOf(subagentDispatchTool(subagentEngine, SubAgentCaller(owner, conversationConfig, caller.workspaceCwd)),
                        subagentListTool(subagentRegistry, owner), subagentGetTool(subagentRegistry, owner),
                        subagentCancelTool(subagentRegistry, owner))
                }
                LocalToolOption.CronJobs -> createCronJobTools(koin.get<ScheduledJobRepository>(),
                    koin.get<ScheduledJobRunRepository>(), koin.get<CronJobScheduler>(), caller) { emptySet() }
                LocalToolOption.Reliability -> listOf(generateBugReportTool(context, BugReportBuilder(context)))
                LocalToolOption.Files -> fileManagerTools(access, context, canReadImages)
                LocalToolOption.ExternalStorage -> listOf(listStorageVolumesTool(context), listGrantedDirectoriesTool(context),
                    grantDirectoryAccessTool(context), revokeDirectoryAccessTool(context))
                LocalToolOption.Archive -> listOf(zipFilesTool(context, access), unzipFileTool(context, access),
                    listZipContentsTool(context, access))
                LocalToolOption.MediaPlayer -> listOf(playMediaTool(context, access), stopMediaTool(context),
                    pauseMediaTool(context), resumeMediaTool(context, access), seekMediaTool(context), getMediaStatusTool())
                LocalToolOption.MediaScanner -> listOf(mediaScannerTool(context, access))
                LocalToolOption.Download -> listOf(downloadTool(context, access), writeTextFileTool(access))
                LocalToolOption.SystemIntents -> systemIntentTools(context)
                LocalToolOption.AppLauncher -> appLauncherTools(context)
                LocalToolOption.Location -> listOf(locationTool(context))
                LocalToolOption.Contacts -> listOf(listContactsTool(context), searchContactsTool(context))
                LocalToolOption.CallLog -> listOf(callLogTool(context))
                LocalToolOption.SmsInbox -> listOf(listSmsInboxTool(context), searchSmsTool(context))
                LocalToolOption.SmsSend -> listOf(smsSendTool(context))
                LocalToolOption.CameraPhoto -> listOf(cameraPhotoTool(context, files))
                LocalToolOption.MicRecorder -> listOf(micRecorderTool(context, files))
                LocalToolOption.SpeechToText -> listOf(speechToTextTool(context))
                LocalToolOption.Fingerprint -> listOf(fingerprintTool(context))
                LocalToolOption.Keystore -> keystoreTools(context, keystore)
                LocalToolOption.Root -> listOf(buildRootTool(rootManager, assistantId = callerId))
                LocalToolOption.JavascriptEngine -> listOf(buildJavascriptTool())
                LocalToolOption.TimeInfo -> listOf(buildTimeInfoTool())
                LocalToolOption.Clipboard -> listOf(buildClipboardTool(context))
                LocalToolOption.Tts -> listOf(buildTextToSpeechTool(eventBus, tts, settings))
                LocalToolOption.AskUser -> listOf(buildAskUserTool())
                LocalToolOption.ScreenTime -> listOf(buildScreenTimeTool(context, eventBus))
                LocalToolOption.Calendar -> listOf(buildCalendarQueryTool(context), buildCalendarCreateTool(context))
                LocalToolOption.ChartDisplay -> listOf(buildChartDisplayTool())
                LocalToolOption.Battery -> listOf(batteryTool(context))
                LocalToolOption.AudioInfo -> listOf(audioInfoTool(context))
                LocalToolOption.TelephonyInfo -> listOf(telephonyInfoTool(context))
                LocalToolOption.WifiInfo -> listOf(wifiInfoTool(context))
                LocalToolOption.Sensors -> listOf(listSensorsTool(context), readSensorTool(context))
                LocalToolOption.StorageInfo -> listOf(storageTool())
                LocalToolOption.Toast -> listOf(toastTool(context))
                LocalToolOption.Notification -> listOf(notificationTool(context, conversationId.toString()))
                LocalToolOption.Share -> listOf(shareTool(context))
                LocalToolOption.Torch -> listOf(torchTool(context))
                LocalToolOption.Vibrate -> listOf(vibrateTool(context))
                LocalToolOption.Brightness -> listOf(getBrightnessTool(context), setBrightnessTool(context))
                LocalToolOption.Volume -> listOf(getVolumeTool(context), setVolumeTool(context))
                LocalToolOption.Wallpaper -> listOf(setWallpaperTool(context,
                    LocalImageSources(File(context.filesDir, "upload"), caller.workspaceCwd, resolveWorkspacePath = resolveWorkspace)))
                LocalToolOption.Nfc -> listOf(nfcStatusTool(context), nfcReadTagTool(context), nfcWriteTagTool(context))
                LocalToolOption.CostGuards -> listOf(checkTokenUsageTool(assistant, conversationId,
                    loadConversation = { metadataOnly() }))
            }
            entry.option to tools.map { it.copy(execute = { metadataOnly() }) }
        }
    }
}

private fun metadataOnly(): Nothing = error("UI metadata cannot execute tools")
