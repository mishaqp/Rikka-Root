package me.rerere.rikkahub.di

import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessToolAction
import me.rerere.rikkahub.service.HeadlessRuntimeBindings
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.trigger.TriggerRegistry
import me.rerere.rikkahub.workflow.repository.sanitizeWorkflowDefinition
import org.koin.dsl.module
import kotlin.uuid.Uuid

/** Agent workflow bindings adapted to Root's separate DB and guarded side-context runner. */
val workflowModule = module {
    single { WorkflowDatabase.create(get()) }
    single {
        val database = get<WorkflowDatabase>()
        val ssh = get<SshToolSecretSanitizer>()
        val mcp = get<McpToolSecretSanitizer>()
        WorkflowRepository(database.workflowDao(), database.workflowRunDao()) { definition ->
            sanitizeWorkflowDefinition(definition) { name, input ->
                me.rerere.rikkahub.data.ai.tools.protectToolArguments(name, input, ssh, mcp)
            }
        }
    }
    single { ContextProvider(get()) }
    single {
        WorkflowActionRunner { definition, action, workflowWebTaint ->
            val repository = get<WorkflowRepository>()
            val owner = Uuid.parse(requireNotNull(definition.authoringAssistantId))
            val caller = definition.authoringConversationId?.let { Uuid.parse(it) }
            val config = definition.callerConversationConfig
            val liveConversation = caller?.let { get<ConversationRepository>().getConversationById(it) }
            val cwd = liveConversation?.takeIf {
                it.assistantId == owner && it.config?.workspaceId == config?.workspaceId
            }?.workspaceCwd
            val bindings = get<HeadlessRuntimeBindings>()
            get<HeadlessTaskRunner>().run(
                HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction(action.tool, action.args))),
                RunExecutionContext(runId = Uuid.random(), ownerAssistantId = owner, origin = RunOrigin.WORKFLOW,
                    callerConversationId = caller, callerConversationConfig = config, workspaceCwd = cwd,
                    modelId = config?.chatModelId?.toString(), maxSteps = 1,
                    timeoutMillis = (action.timeoutSeconds.toLong() * 1000).coerceIn(1, 480000),
                    webTaint = caller?.let { bindings.webTaintFor(it.toString()) } ?: workflowWebTaint,
                    runStillAllowed = {
                        repository.getById(definition.id)?.let { live ->
                            live.entity.enabled && live.definition == definition && live.definition.authoringAssistantId == owner.toString()
                        } == true
                    }),
            )
        }
    }
    single {
        WorkflowEngine(get(), get<SettingsStore>(), get(), get()).also { get<WorkflowRepository>().bindEngine(it) }
    }
    single { TriggerRegistry(get(), get<AppScope>(), get()).also { it.setEngineCallback(get<WorkflowEngine>().triggerCallback) } }
}
