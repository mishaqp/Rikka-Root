package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.costguards.TokenBudgetStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.HeadlessRuntimeBindings
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.subagent.SubAgentEngine
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.repository.AndroidCronPayloadCipher
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single { RootShellManager(controlDirectory = get<android.content.Context>().noBackupFilesDir) }
    single { RootAccessStore(java.io.File(get<android.content.Context>().noBackupFilesDir, "root-control")) }
    single { ToolApprovalPreferences(get(), get()) }
    single {
        val settings = get<SettingsStore>()
        TokenBudgetStore(java.io.File(get<android.content.Context>().noBackupFilesDir, "token-budgets"),
            assistantSource = { id -> settings.settingsFlow.value.assistants.singleOrNull { it.id == id } })
    }

    single { me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore(get()) }
    single { me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer(get()) }
    single { me.rerere.rikkahub.data.ssh.SshCredentialStore(get(), get()) }
    single { me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer(get()) }
    single { me.rerere.rikkahub.data.repository.SshHostRepository(
        get<me.rerere.rikkahub.data.db.AppDatabase>().sshHostDao(), get(), get()) }
    single { me.rerere.rikkahub.data.preferences.TermuxPreferences(get()) }
    single { SubAgentRegistry() }
    single<CronPayloadCipher> { AndroidCronPayloadCipher() }
    single { ScheduledJobRepository(get(), get()) }
    single { ScheduledJobRunRepository(get()) }
    single { CronJobScheduler(get(), get(), get(), get()) }
    single { HeadlessRuntimeBindings(get(), get(), get(), get(), get(), get(), get()) }
    single<HeadlessTaskRunner> { get<HeadlessRuntimeBindings>().createRunner(get(), get()) }
    single {
        val bindings = get<HeadlessRuntimeBindings>()
        val settings = get<SettingsStore>()
        SubAgentEngine(registry = get(), runner = get(), settings = { settings.settingsFlow.value },
            scope = get<AppScope>(), enabled = { bindings.subAgentsEnabled(it) },
            concurrencyLimit = { bindings.subAgentConcurrency(it) })
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        UpdateChecker(
            client = get(),
            appScope = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatToolFactory(
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            rootAccessStore = get(),
            toolApprovalPreferences = get(),
            runtimeBindings = get(),
            subAgentEngine = { get<SubAgentEngine>() },
            subAgentRegistry = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            toolApprovalPreferences = get(),
            tokenBudgetStore = get(),
            subAgentRegistry = get(),
        )
    }

    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
            okHttpClient = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
