package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.wallpaperChatImages
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.service.HeadlessRuntimeBindings
import me.rerere.rikkahub.subagent.SubAgentCaller
import me.rerere.rikkahub.data.ai.tools.local.createCronJobTools
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronJobScheduler
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.subagent.SubAgentEngine
import me.rerere.rikkahub.subagent.SubAgentOwner
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.subagentDispatchTool
import me.rerere.rikkahub.subagent.subagentGetTool
import me.rerere.rikkahub.subagent.subagentListTool
import me.rerere.rikkahub.subagent.subagentCancelTool
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

/** Freeze the complete advertised capability set after workspace/skills/MCP tools were added. */
internal fun headlessToolAllowlist(
    advertisedToolNames: Collection<String>,
    allowExternalSearchReplacement: Boolean,
    externalSearchToolNames: Collection<String> = emptyList(),
): Set<String> = advertisedToolNames.toSet() +
    if (allowExternalSearchReplacement) externalSearchToolNames.toSet() else emptySet()

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val rootAccessStore: RootAccessStore,
    private val toolApprovalPreferences: ToolApprovalPreferences,
    private val runtimeBindings: HeadlessRuntimeBindings,
    private val subAgentEngine: () -> SubAgentEngine,
    private val subAgentRegistry: SubAgentRegistry,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: String? = null,
        messages: List<UIMessage> = emptyList(),
        tokenBudget: TokenBudgetLedger? = null,
        executionContext: RunExecutionContext? = null,
    ): List<Tool> = buildList {
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(
                buildMemoryTools(
                    json = json,
                    onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                )
            )
        }
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createSearchTools(settings))
        }
        val workspaceId = assistant.workspaceId?.toString()
        addAll(localTools.getTools(assistant.localTools, assistant.id.toString(), conversationId,
            workspaceCwd, wallpaperChatImages(messages), workspaceId?.let { id ->
                { path -> workspaceRepository.resolveRootfsFile(id, path) }
            }, modelCanReadImages = Modality.IMAGE in model.inputModalities,
            callerAssistant = assistant, tokenBudget = tokenBudget ?: executionContext?.costBudget))
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }

        val mcpTools = mcpManager.getAllAvailableTools(assistant)
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
        if (executionContext == null && LocalToolOption.CronJobs in assistant.localTools && conversationId != null) {
            val id = runCatching { Uuid.parse(conversationId) }.getOrNull()
            val stored = id?.let { conversationRepository.getConversationById(it) }
            if (id != null && (stored == null || stored.assistantId == assistant.id)) {
                // Generation supplied the effective chat snapshot, which may be newer than Room.
                val caller = (stored ?: Conversation.ofId(id, assistant.id)).copy(
                    config = ConversationConfig(chatModelId = model.id, reasoningLevel = assistant.reasoningLevel,
                        enableWebSearch = assistant.enableWebSearch, builtInSearch = BuiltInTools.Search in model.tools,
                        mcpServers = assistant.mcpServers, workspaceId = assistant.workspaceId,
                        enabledSkills = assistant.enabledSkills), workspaceCwd = workspaceCwd)
                val koin = GlobalContext.get()
                addAll(createCronJobTools(koin.get<ScheduledJobRepository>(), koin.get<ScheduledJobRunRepository>(),
                    koin.get<CronJobScheduler>(), caller, knownToolNames = {
                        headlessToolAllowlist(
                            advertisedToolNames = map { it.name },
                            allowExternalSearchReplacement = assistant.enableWebSearch && BuiltInTools.Search in model.tools,
                            externalSearchToolNames = if (assistant.enableWebSearch && BuiltInTools.Search in model.tools)
                                createSearchTools(settings).map { it.name } else emptyList(),
                        )
                    }))
            }
        }
        if (executionContext == null && LocalToolOption.SubAgents in assistant.localTools && conversationId != null) {
            val conversationUuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
            val conversation = conversationUuid?.let { conversationRepository.getConversationById(it) }
            // Owner comes from trusted generation/DB state, never model arguments or the selected assistant.
            if (conversationUuid != null && (conversation == null || conversation.assistantId == assistant.id)) {
                if (WebContentGuard.hasWebContent(messages)) runtimeBindings.markWebContent(conversationId)
                val owner = SubAgentOwner(assistant.id.toString(), conversationId)
                val caller = SubAgentCaller(
                    owner = owner,
                    // The assistant/model passed to this factory are the effective caller configuration.
                    conversationConfig = ConversationConfig(
                        chatModelId = model.id, reasoningLevel = assistant.reasoningLevel,
                        enableWebSearch = assistant.enableWebSearch, builtInSearch = BuiltInTools.Search in model.tools,
                        mcpServers = assistant.mcpServers, workspaceId = assistant.workspaceId,
                        enabledSkills = assistant.enabledSkills,
                    ),
                    workspaceCwd = workspaceCwd,
                    webTaint = runtimeBindings.webTaintFor(conversationId),
                    allowedTools = headlessToolAllowlist(
                        advertisedToolNames = map { it.name },
                        allowExternalSearchReplacement = assistant.enableWebSearch && BuiltInTools.Search in model.tools,
                        externalSearchToolNames = if (assistant.enableWebSearch && BuiltInTools.Search in model.tools)
                            createSearchTools(settings).map { it.name } else emptyList(),
                    ),
                )
                add(subagentDispatchTool(subAgentEngine(), caller))
                add(subagentListTool(subAgentRegistry, owner))
                add(subagentGetTool(subAgentRegistry, owner))
                add(subagentCancelTool(subAgentRegistry, owner))
            }
        }
    }.map { ToolPermissionPolicy.apply(it) }

    suspend fun restoreWebContentGuard(conversationId: String, messages: List<UIMessage>) {
        if (WebContentGuard.hasWebContent(messages)) markWebContent(conversationId)
    }

    suspend fun markWebContent(conversationId: String) {
        runtimeBindings.markWebContent(conversationId)
    }

    fun hasWebContent(conversationId: String): Boolean = runtimeBindings.hasWebContent(conversationId)

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
