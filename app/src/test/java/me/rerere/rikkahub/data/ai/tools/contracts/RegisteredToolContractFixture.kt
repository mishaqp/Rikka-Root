package me.rerere.rikkahub.data.ai.tools.contracts

import android.content.Context
import android.content.ContextWrapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.automation.ExternalAutomationConfig
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.RootMode
import me.rerere.rikkahub.data.ai.tools.local.createCronJobTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.service.HeadlessRuntimeBindings
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.js.JsSkillRunner
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.subagent.*
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSManager
import me.rerere.workspace.WorkspaceShellStatus
import okhttp3.OkHttpClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import java.io.Closeable
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/**
 * Real Tool creators and production aggregate; mocks provide inert runtime dependencies only.
 * No replacement Tool objects stand in for the local catalogue. Calling execute is forbidden,
 * except the separate remote-adapter test, whose manager is a strict no-network mock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RegisteredToolContractFixture(directory: File) : Closeable {
    val options: List<LocalToolOption> = LocalToolOption::class.sealedSubclasses
        .map { requireNotNull(it.objectInstance) { "Add a fixture for non-object option ${it.qualifiedName}" } }
        .sortedBy { it::class.simpleName }
    val searchOptions: List<SearchServiceOptions> = SearchServiceOptions.TYPES.keys.map { type ->
        type.constructors.single { ctor -> ctor.parameters.all { it.isOptional } }.callBy(emptyMap())
    }
    val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
    }
    val model = Model(modelId = "contract-tool-model", abilities = listOf(ModelAbility.TOOL))
    val conversationId = Uuid.parse("12345678-0000-4000-8000-000000000001")
    val workspaceId = Uuid.parse("12345678-0000-4000-8000-000000000002")
    val remoteId = Uuid.parse("12345678-0000-4000-8000-000000000003")
    val rootState = MutableStateFlow(RootStatus.READY)
    private val state = MutableStateFlow(Settings())
    val settingsStore = mockk<SettingsStore> { every { settingsFlow } returns state }
    val manager = mockk<McpManager>()
    private val conversations = mockk<ConversationRepository>()
    private val memories = mockk<MemoryRepository>()
    private val workspace = mockk<WorkspaceRepository>()
    private val root = mockk<RootShellManager> { every { status } returns rootState }
    private val rootAccess = mockk<RootAccessStore>()
    private val skills = mockk<SkillManager>()
    private val skill = SkillMetadata("contract_skill", "Локальный навык для проверки регистрации", skillDir = File(directory, "skill"))
    private val cron = mockk<ScheduledJobRepository>()
    private val cronRuns = mockk<ScheduledJobRunRepository>()
    private val cronScheduler = mockk<CronJobScheduler>()
    private val subEngine = mockk<SubAgentEngine>()
    private val subRegistry = SubAgentRegistry()
    val client = OkHttpClient.Builder().addInterceptor { error("Contract tests must not perform HTTP") }.build()
    val remoteTools = listOf(
        McpTool(name = "exact.remote-tool", description = "External remote description", inputSchema = InputSchema.Obj(
            buildJsonObject {
                put("format", buildJsonObject { put("type", "string"); put("description", "External format field"); put("format", "date") })
                put("enum", buildJsonObject { put("type", "string"); put("description", "External enum field") })
                put("nested", buildJsonObject {
                    put("type", "object"); put("description", "External nested field")
                    put("properties", buildJsonObject { put("const", buildJsonObject { put("type", "boolean"); put("description", "External flag") }) })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("const"))))
                })
            }, required = listOf("format", "enum", "nested"))),
        McpTool(name = "no_parameters", description = "External tool without parameters"),
    )
    private val remoteConfig = McpServerConfig.StreamableHTTPServer(id = remoteId,
        commonOptions = McpCommonOptions(name = "Contract Server", tools = remoteTools), url = "https://example.invalid/mcp")
    val settings = Settings(mcpServers = listOf(remoteConfig))
    private val local: LocalTools
    private val factory: ChatToolFactory

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { conversations.getConversationById(any()) } returns null
        coEvery { workspace.getById(workspaceId.toString()) } returns WorkspaceEntity(workspaceId.toString(),
            "Контракт", File(directory, "workspace").path, WorkspaceShellStatus.READY.name, 0L, 0L)
        every { skills.listSkills() } returns listOf(skill)
        every { manager.getAllAvailableTools(any(), any()) } returns remoteTools.map { Triple(remoteId, "Contract Server", it) }
        remoteTools.forEach { remote ->
            coEvery { manager.callTool(remoteId, remote.name, any()) } returns listOf(UIMessagePart.Text("remote-contract-result"))
        }
        val browser = mockk<BrowserPreferences> {
            every { snapshotBlocking() } returns BrowserToolDefaults.ALL_TOOLS.associateWith { true }
        }
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val secretStore = McpControlSecretStore(File(context.noBackupFilesDir, "mcp")) { key }
        val sshSecrets = SshToolSecretSanitizer(SshCredentialStore(File(context.noBackupFilesDir, "ssh.enc"), Json) { key })
        val ssh = mockk<SshHostRepository> { every { toolSecrets } returns sshSecrets }
        val workflows = mockk<WorkflowRepository>()
        val workflowEngine = mockk<WorkflowEngine>()
        val runtime = mockk<HeadlessRuntimeBindings> { every { webTaintFor(any()) } returns RunWebTaint() }
        startKoin { modules(module {
            single { settingsStore }
            single { manager }
            single { secretStore }
            single { McpToolSecretSanitizer(secretStore) }
            single { AssistantOwnershipGuard { true } }
            single { ExternalAutomationConfig(context) }
            single { skills }
            single { mockk<SkillUrlImporter>() }
            single { mockk<JsSkillRunner>() }
            single { mockk<SkillSecretsStore>() }
            single { workflows }
            single { workflowEngine }
            single { cron }
            single { cronRuns }
            single { cronScheduler }
            single { root }
            single { client }
        }) }
        local = LocalTools(context, AppEventBus(), mockk<TTSManager>(), settingsStore, root, rootAccess,
            mockk<FilesManager>(), mockk<TermuxPreferences>(), ssh, browser)
        factory = ChatToolFactory(Json, memories, conversations, local, manager, skills, workspace, rootAccess,
            mockk<ToolApprovalPreferences>(), runtime, { subEngine }, subRegistry)
    }

    fun assistant(rootMode: Boolean = false) = Assistant(localTools = options, enableMemory = true,
        enableRecentChatsReference = true, enableWebSearch = true, enabledSkills = setOf(skill.name),
        workspaceId = workspaceId, mcpServers = setOf(remoteId), rootMode = rootMode)

    suspend fun views(): List<ToolView> {
        val normal = view("normal", assistant(), RootStatus.READY)
        val root = view("root", assistant(rootMode = true), RootStatus.READY)
        val fallback = view("root-unavailable", assistant(rootMode = true), RootStatus.UNAVAILABLE)
        return listOf(normal, root, fallback)
    }

    private suspend fun view(label: String, assistant: Assistant, status: RootStatus): ToolView {
        rootState.value = status
        state.value = settings.copy(assistants = listOf(assistant))
        val tools = factory.createTools(state.value, assistant, model, conversationId = conversationId.toString())
        val effective = RootMode.effectiveOptions(options, assistant.rootMode, status)
        val groups = effective.associate { option -> option::class.simpleName!! to local.getTools(listOf(option),
            assistantId = assistant.id.toString(), conversationId = conversationId.toString(), callerAssistant = assistant) }
            .toMutableMap()
        groups["context/memory"] = buildMemoryTools(Json, { error("Do not execute memory") }, { _, _ -> error("Do not execute memory") }, {})
        groups["context/search"] = createSearchTools(settings).toList()
        groups["context/recent-chats"] = createConversationTools(conversations, assistant.id)
        groups["context/workspace"] = createWorkspaceTools(workspaceId.toString(), workspace)
        groups["context/skills"] = createSkillTools(assistant.enabledSkills, listOf(skill), skills)
        groups["context/cron"] = createCronJobTools(cron, cronRuns, cronScheduler,
            Conversation.ofId(conversationId, assistant.id), knownToolNames = { tools.map { it.name }.toSet() })
        val owner = SubAgentOwner(assistant.id.toString(), conversationId.toString())
        groups["context/subagent"] = listOf(subagentDispatchTool(subEngine, mockk()), subagentListTool(subRegistry, owner),
            subagentGetTool(subRegistry, owner), subagentCancelTool(subRegistry, owner))
        // These are actual factory-created adapters. The remote schema is never rebuilt in tests.
        groups["external/MCP"] = tools.filter { it.name.startsWith("mcp__") }
        return ToolView(label, tools, groups)
    }

    /** Also exercise schemas selected by every production search-service variant. */
    fun searchViews(): List<ToolView> = (searchOptions + SearchServiceOptions.CustomJsOptions(
        scrapeScript = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT)).map { option ->
        val tools = createSearchTools(settings.copy(searchServices = listOf(option), searchServiceSelected = 0)).toList()
        val branch = if (option is SearchServiceOptions.CustomJsOptions && option.scrapeScript.isNotBlank()) "/scrape-enabled" else ""
        ToolView("search/${option::class.simpleName}$branch", tools, mapOf("search" to tools))
    }

    override fun close() {
        stopKoin()
        Dispatchers.resetMain()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal data class ToolView(val label: String, val tools: List<Tool>, val sources: Map<String, List<Tool>>) {
    fun origin(tool: Tool): String = sources.filterValues { source -> source.any { it.name == tool.name } }.keys.joinToString("+")
    fun isExternal(tool: Tool): Boolean = sources["external/MCP"].orEmpty().any { it.name == tool.name }
}
