package me.rerere.rikkahub.service

import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.costguards.TokenBudgetExceededException
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.WebContentGuard
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.root.executeRootToolOnce
import me.rerere.rikkahub.subagent.resolveSubAgentModel
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

enum class HeadlessTaskStatus { SUCCEEDED, FAILED, BLOCKED, TIMED_OUT, CANCELLED, INDETERMINATE }
data class HeadlessTaskResult(
    val status: HeadlessTaskStatus, val output: String? = null, val errorCode: String? = null,
    val tokensIn: Long = 0, val tokensOut: Long = 0, val steps: Int = 0, val webTainted: Boolean = false,
)
data class HeadlessToolAction(val name: String, val arguments: JsonElement)
sealed interface HeadlessTaskRequest {
    data class Prompt(val task: String, val systemPrompt: String? = null) : HeadlessTaskRequest
    data class DirectActions(val actions: List<HeadlessToolAction>) : HeadlessTaskRequest
}
interface HeadlessRunJournal {
    suspend fun claim(runId: String): Boolean
    suspend fun checkpoint(runId: String, toolCallIds: Set<String>)
}

/** No prompt, command, result, model identifier, key or token data is written here. */
class AtomicHeadlessRunJournal(private val directory: File) : HeadlessRunJournal {
    private val lock = Any()
    override suspend fun claim(runId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = file(runId)
            check(directory.isDirectory || directory.mkdirs())
            // Claimed records are never replayed, even when process death left an empty file.
            if (!file.createNewFile()) return@synchronized false
            write(file, emptySet())
            true
        }
    }
    override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = file(runId)
            check(file.exists())
            write(file, toolCallIds)
        }
    }
    private fun file(id: String): File = File(directory, "${Uuid.parse(id)}.run")
    private fun write(file: File, ids: Set<String>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val safe = ids.sorted().joinToString("\n") { id ->
                digest.digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            stream.write("v1\nclaimed\n$safe\n".toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

internal suspend fun runHeadlessDeadline(timeoutMillis: Long, block: suspend () -> HeadlessTaskResult): HeadlessTaskResult {
    require(timeoutMillis in 1..480_000)
    return withTimeoutOrNull(timeoutMillis) { block() }
        ?: HeadlessTaskResult(HeadlessTaskStatus.TIMED_OUT, errorCode = "deadline_exceeded")
}

private class HeadlessStop(val outcome: HeadlessTaskStatus, val code: String) : CancellationException(code)

/** Runs a fresh in-memory side conversation, retaining the normal generation and tool engines. */
class HeadlessTaskRunner internal constructor(
    private val generationLoop: GenerationLoop,
    private val toolSource: suspend (Settings, Assistant, Model, RunExecutionContext) -> List<Tool>,
    private val settings: suspend () -> Settings,
    private val journal: HeadlessRunJournal,
    private val authorize: suspend (RunExecutionContext, String, JsonElement) -> Boolean,
    private val preflight: suspend (RunExecutionContext, String) -> String?,
    private val featureEnabled: suspend (RunExecutionContext) -> Boolean,
    private val onWebContent: suspend (RunExecutionContext) -> Unit = {},
    private val budgetFor: (RunExecutionContext, Assistant) -> TokenBudgetLedger? = { _, _ -> null },
) {
    constructor(
        generationLoop: GenerationLoop,
        toolFactory: ChatToolFactory,
        settings: suspend () -> Settings,
        journal: HeadlessRunJournal,
        authorize: suspend (RunExecutionContext, String, JsonElement) -> Boolean,
        preflight: suspend (RunExecutionContext, String) -> String?,
        featureEnabled: suspend (RunExecutionContext) -> Boolean,
        onWebContent: suspend (RunExecutionContext) -> Unit = {},
        budgetFor: (RunExecutionContext, Assistant) -> TokenBudgetLedger? = { _, _ -> null },
    ) : this(generationLoop, { snapshot, assistant, model, context ->
        toolFactory.createTools(snapshot, assistant, model, workspaceCwd = context.workspaceCwd,
            conversationId = context.runId.toString(), executionContext = context)
    }, settings, journal, authorize, preflight, featureEnabled, onWebContent, budgetFor)

    suspend fun run(request: HeadlessTaskRequest, context: RunExecutionContext): HeadlessTaskResult {
        var execution = context
        if (execution.maxSteps !in 1..32 || execution.timeoutMillis !in 1..480_000) {
            return HeadlessTaskResult(HeadlessTaskStatus.BLOCKED, errorCode = "invalid_limits")
        }
        var latest = emptyList<UIMessage>()
        var steps = 0
        try {
            val result = runHeadlessDeadline(execution.timeoutMillis) {
                ensureEnabled(execution)
                if (execution.costBudget == null) {
                    val budgetOwner = settings().assistants.singleOrNull { it.id == execution.ownerAssistantId }
                        ?: stop(HeadlessTaskStatus.BLOCKED, "unknown_owner")
                    execution = execution.copy(costBudget = withContext(Dispatchers.IO) { budgetFor(execution, budgetOwner) })
                }
                withContext(execution) {
                    ensureEnabled(execution)
                    if (!journal.claim(execution.runId.toString())) stop(HeadlessTaskStatus.INDETERMINATE, "run_already_claimed")
                    val current = settings()
                    val stored = current.assistants.singleOrNull { it.id == execution.ownerAssistantId }
                        ?: stop(HeadlessTaskStatus.BLOCKED, "unknown_owner")
                    val config = execution.callerConversationConfig
                    val assistant = stored.copy(
                        chatModelId = config?.chatModelId ?: stored.chatModelId,
                        reasoningLevel = config?.reasoningLevel ?: stored.reasoningLevel,
                        enableWebSearch = config?.enableWebSearch ?: stored.enableWebSearch,
                        mcpServers = config?.mcpServers ?: stored.mcpServers,
                        workspaceId = if (config != null) config.workspaceId else stored.workspaceId,
                        enabledSkills = config?.enabledSkills ?: stored.enabledSkills,
                        systemPrompt = (request as? HeadlessTaskRequest.Prompt)?.systemPrompt ?: stored.systemPrompt,
                        presetMessages = emptyList(), autoCompressContext = false,
                    )
                    val selected = resolveSubAgentModel(current.providers, execution.modelId,
                        (assistant.chatModelId ?: current.chatModelId).toString())
                        ?: stop(HeadlessTaskStatus.BLOCKED, "unknown_or_ambiguous_model")
                    // Provider built-ins bypass client approval; use factory search tools instead.
                    val model = selected.copy(tools = emptySet())
                    val allowedTools = execution.allowedTools
                    val definitions = toolSource(current, assistant, model, execution)
                        .filter { allowedTools == null || it.name in allowedTools }
                    val tools = definitions.map { definition -> guarded(definition, execution) }
                    if (execution.webTaint.isTainted()) onWebContent(execution)
                    when (request) {
                        is HeadlessTaskRequest.Prompt -> {
                            if (request.task.isBlank() || request.task.length > 100_000) stop(HeadlessTaskStatus.BLOCKED, "invalid_task")
                            latest = listOf(UIMessage.user(request.task))
                            generationLoop.generateText(
                                settings = current, model = model, messages = latest, assistant = assistant,
                                tools = tools, maxSteps = execution.maxSteps, conversationId = execution.runId,
                                workspaceCwd = execution.workspaceCwd, tokenBudget = execution.costBudget,
                                isToolAutoApproved = { name, args ->
                                    ensureEnabled(execution)
                                    HeadlessToolPolicy.blockReason(name, args) == null &&
                                        preflight(execution, name) == null && authorize(execution, name, args)
                                },
                                onWebContentRead = { markWeb(execution) },
                                beforeModelRequest = { ensureEnabled(execution) },
                            ).collect { chunk ->
                                ensureEnabled(execution)
                                when (chunk) {
                                    is GenerationChunk.Messages -> latest = chunk.messages
                                    is GenerationChunk.RootExecutionCheckpoint -> {
                                        try {
                                            journal.checkpoint(execution.runId.toString(), chunk.messages.flatMap { it.getTools() }
                                                .filter { it.isExecuted }.map { it.toolCallId }.toSet())
                                            chunk.ack.complete(Unit)
                                        } catch (error: Throwable) {
                                            chunk.ack.completeExceptionally(error)
                                            throw error
                                        }
                                    }
                                }
                            }
                            steps = latest.count { it.role == MessageRole.ASSISTANT }
                            if (latest.any { message -> message.getTools().any { it.isPending } }) stop(HeadlessTaskStatus.BLOCKED, "approval_required")
                            if (latest.lastOrNull()?.getTools()?.isNotEmpty() == true) stop(HeadlessTaskStatus.FAILED, "step_limit")
                            if (latest.lastOrNull()?.role != MessageRole.ASSISTANT) stop(HeadlessTaskStatus.FAILED, "no_response")
                            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED,
                                output = latest.last().parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                        }
                        is HeadlessTaskRequest.DirectActions -> {
                            if (request.actions.isEmpty() || request.actions.size > execution.maxSteps) stop(HeadlessTaskStatus.BLOCKED, "invalid_actions")
                            val started = linkedSetOf<String>()
                            val outputs = mutableListOf<String>()
                            for ((index, action) in request.actions.withIndex()) {
                                val definition = tools.singleOrNull { it.name == action.name }
                                    ?: stop(HeadlessTaskStatus.BLOCKED, "tool_not_allowed")
                                checkTool(definition, action.arguments, execution)
                                val callId = "direct-$index"
                                suspend fun checkpoint() {
                                    started += callId
                                    journal.checkpoint(execution.runId.toString(), started)
                                }
                                val output = if (action.name == "root_exec") {
                                    val call = UIMessagePart.Tool(toolCallId = callId, toolName = action.name,
                                        input = action.arguments.toString(), approvalState = ToolApprovalState.Auto)
                                    executeRootToolOnce(call, persistStarted = { checkpoint() },
                                        automaticAllowed = { authorize(execution, action.name, action.arguments) }) {
                                        definition.execute(action.arguments)
                                    }
                                } else {
                                    checkpoint()
                                    definition.execute(action.arguments)
                                }
                                outputs += output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                                steps++
                            }
                            HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED, output = outputs.joinToString("\n"))
                        }
                    }
                }
            }
            return decorate(if (result.status == HeadlessTaskStatus.TIMED_OUT && execution.executionState.effectsInFlight)
                result.copy(status = HeadlessTaskStatus.INDETERMINATE, errorCode = "execution_interrupted") else result, execution, latest, steps)
        } catch (stop: HeadlessStop) {
            return decorate(HeadlessTaskResult(stop.outcome, errorCode = stop.code), execution, latest, steps)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: TokenBudgetExceededException) {
            return decorate(HeadlessTaskResult(HeadlessTaskStatus.BLOCKED, errorCode = "token_budget_exceeded"), execution, latest, steps)
        } catch (_: Exception) {
            return decorate(HeadlessTaskResult(if (execution.executionState.effectsInFlight) HeadlessTaskStatus.INDETERMINATE
                else HeadlessTaskStatus.FAILED, errorCode = "run_failed"), execution, latest, steps)
        }
    }

    private fun guarded(tool: Tool, context: RunExecutionContext): Tool = tool.copy(execute = { args ->
        checkTool(tool, args, context)
        currentCoroutineContext().ensureActive()
        context.executionState.effectsInFlight = true
        val output = try { tool.execute(args) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stop(HeadlessTaskStatus.INDETERMINATE, "tool_execution_failed")
        }
        context.executionState.effectsInFlight = false
        if (WebContentGuard.isClientReader(tool.name)) markWeb(context)
        classifyHeadlessToolFailure(tool.name, output)?.let { failure -> stop(failure.status, failure.errorCode) }
        output
    })
    private suspend fun ensureEnabled(context: RunExecutionContext) {
        if (!context.runStillAllowed()) stop(HeadlessTaskStatus.BLOCKED, "run_no_longer_allowed")
        if (!featureEnabled(context)) stop(HeadlessTaskStatus.BLOCKED, "feature_disabled")
    }
    private suspend fun checkTool(tool: Tool, args: JsonElement, context: RunExecutionContext) {
        ensureEnabled(context)
        context.costBudget?.ensureCanContinue()
        HeadlessToolPolicy.blockReason(tool.name, args)?.let { stop(HeadlessTaskStatus.BLOCKED, it) }
        preflight(context, tool.name)?.let { stop(HeadlessTaskStatus.BLOCKED, it) }
        if (tool.needsApproval(args) && !authorize(context, tool.name, args)) stop(HeadlessTaskStatus.BLOCKED, "approval_required")
    }
    private suspend fun markWeb(context: RunExecutionContext) { context.webTaint.mark(); onWebContent(context) }
    private fun stop(status: HeadlessTaskStatus, code: String): Nothing = throw HeadlessStop(status, code)
    private fun decorate(result: HeadlessTaskResult, context: RunExecutionContext, messages: List<UIMessage>, steps: Int) = result.copy(
        tokensIn = messages.sumOf { (it.usage?.promptTokens ?: 0).coerceAtLeast(0).toLong() },
        tokensOut = messages.sumOf { (it.usage?.completionTokens ?: 0).coerceAtLeast(0).toLong() },
        steps = maxOf(steps, messages.count { it.role == MessageRole.ASSISTANT }), webTainted = context.webTaint.isTainted(),
    )
}

internal data class HeadlessToolFailure(val status: HeadlessTaskStatus, val errorCode: String)

private val rootIndeterminateErrors = setOf("command_timeout", "root_cleanup_unconfirmed", "root_execution_indeterminate")
private val rootBlockedErrors = setOf("root_interaction_required", "root_approval_required", "root_not_granted", "root_command_blocked")
private val nativeEnvelopeTools = setOf(
    "workspace_read_file", "workspace_write_file", "workspace_edit_file", "workspace_shell",
    "workspace_background_start", "workspace_background_stop", "workspace_background_list", "workspace_background_output",
    "memory_tool", "recent_chats", "conversation_search", "use_skill",
)

internal fun classifyHeadlessToolResult(name: String, output: List<UIMessagePart>): HeadlessTaskStatus? =
    classifyHeadlessToolFailure(name, output)?.status

/** Interpret only registered native envelopes; reader contents and nested arbitrary JSON remain data. */
internal fun classifyHeadlessToolFailure(name: String, output: List<UIMessagePart>): HeadlessToolFailure? {
    if (localOptionForHeadlessTool(name) == null && name !in nativeEnvelopeTools) return null
    for (part in output) {
        val text = (part as? UIMessagePart.Text)?.text ?: continue
        if (text.length > 1_048_576) continue
        val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
        if (name == "workspace_shell") {
            if (value["timedOut"] == JsonPrimitive(true)) return HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, "command_timeout")
            if ((value["exitCode"] as? JsonPrimitive)?.intOrNull?.let { it != 0 } == true)
                return HeadlessToolFailure(HeadlessTaskStatus.FAILED, "tool_failed")
        }
        if (name in setOf("batch_copy", "batch_move", "batch_delete") &&
            (value["failed"] as? JsonArray)?.isNotEmpty() == true) return HeadlessToolFailure(HeadlessTaskStatus.FAILED, "tool_failed")
        val failure = value["error"]?.let { it != kotlinx.serialization.json.JsonNull && it != JsonPrimitive(false) } == true ||
            value["success"] == JsonPrimitive(false)
        if (!failure) continue
        if (name == "root_exec") {
            // Only these native machine codes are allowed into persistent history; no source/output text.
            val code = (value["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (code in rootIndeterminateErrors) return HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, code!!)
            if (code in rootBlockedErrors) return HeadlessToolFailure(HeadlessTaskStatus.BLOCKED, code!!)
        }
        if (name == "send_sms" && value["parts_total"] != null && (
                (value["parts_sent"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true ||
                (value["parts_unknown"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true ||
                value["native_error_type"] != null)) return HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, "tool_result_indeterminate")
        return HeadlessToolFailure(HeadlessTaskStatus.FAILED, "tool_failed")
    }
    return null
}
