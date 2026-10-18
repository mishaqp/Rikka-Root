package me.rerere.rikkahub.data.ai

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentException
import me.rerere.ai.core.prepareArguments
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.StreamChunk
import me.rerere.rikkahub.costguards.TokenBudgetContext
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.costguards.TokenBudgetTracker
import me.rerere.rikkahub.costguards.TokenBudgetPersistenceException
import me.rerere.rikkahub.costguards.TokenBudgetExceededException
import me.rerere.rikkahub.costguards.boundedCustomBodies
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.root.executeRootToolOnce
import me.rerere.rikkahub.root.awaitRootCheckpoint
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy.canResumeAutomatic
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.ToolExecutionAbortedException
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.WebContentGuard
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.reliability.SecretRedactor
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private val PROTECTED_SHELL_TOOLS = setOf(
    "root_exec", "ssh_exec", "ssh_exec_saved", "termux_run_command", "termux_session_start", "termux_session_send",
)
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    // A tool may start only after the collector has awaited its durable Room write.
    data class RootExecutionCheckpoint(
        val messages: List<UIMessage>,
        val ack: CompletableDeferred<Unit>,
    ) : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = ToolRuntimeLimits.maxToolSteps,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        onWebContentRead: suspend () -> Unit = {},
        onAutoCompress: suspend (List<UIMessage>) -> List<UIMessage>? = { null },
        isToolAutoApproved: suspend (toolName: String, input: JsonElement) -> Boolean = { _, _ -> false },
        tokenBudget: TokenBudgetLedger? = null,
        prepareToolApproval: (String, JsonElement) -> JsonElement = { _, input -> input },
        beforeModelRequest: suspend () -> Unit = {},
        onStepStarted: (suspend () -> Unit)? = null,
        onLimitReached: (suspend (String) -> Unit)? = null,
        // «Я ГЛУПЫЙ» in an interactive chat: the user chose not to be asked, including for most
        // mandatory confirmations. Protected shell commands, HARDLINE and ask_user still apply.
        // Nullable and last, like the other optional hooks, so positional mocks keep matching.
        approveEverything: (suspend () -> Boolean)? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        val inheritedBudget = currentCoroutineContext()[TokenBudgetContext]?.ledger
        val budget = tokenBudget ?: inheritedBudget
            ?: if (assistant.localTools.contains(LocalToolOption.CostGuards)) TokenBudgetLedger(
                enabled = true, softCap = assistant.tokenBudgetSoftCap,
                hardCap = assistant.tokenBudgetHardCap, initialMessages = messages,
            ) else null
        budget?.ensureCanContinue()
        var messages: List<UIMessage> = messages
        if (WebContentGuard.hasWebContent(messages)) onWebContentRead()

        suspend fun needsApproval(tool: UIMessagePart.Tool, definition: Tool): Boolean {
            val args = tool.inputAsJson()
            // A question to the user is never an approval; it always waits for the answer.
            if (tool.toolName == "ask_user") return true
            val mandatory = ToolPermissionPolicy.mandatoryConfirmation(tool.toolName, args)
            // Protected shell commands (remount, SELinux, bootloader, formatting) always ask.
            if (mandatory && tool.toolName in PROTECTED_SHELL_TOOLS) return true
            // Read the mode only when a prompt would otherwise be shown.
            if (!mandatory && !definition.needsApproval(args)) return false
            if (approveEverything?.invoke() == true) return false
            // These exceptions cannot be bypassed by chat grants or Always.
            if (mandatory) return true
            return !isToolAutoApproved(tool.toolName, args)
        }

        fun toolError(code: String, message: String): List<UIMessagePart> = listOf(
            UIMessagePart.Text(buildJsonObject { put("error", code); put("message", message) }.toString())
        )
        fun hardlineMessage(reason: String): String {
            val label = if (reason.startsWith("hardline:")) reason else "hardline: $reason"
            return "Команда заблокирована защитой ($label). Выберите безопасное действие."
        }
        fun validateArguments(tool: UIMessagePart.Tool): UIMessagePart.Tool {
            if (tool.isExecuted) return tool
            val arguments = runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }.getOrNull()
            if (arguments !is JsonObject) return tool.copy(output = toolError(
                "invalid_tool_arguments", "Некорректные аргументы инструмента: ожидается корректный JSON-объект. Исправьте параметры и повторите вызов."
            ))
            return try {
                val canonical = tools.find { it.name == tool.toolName }?.prepareArguments(arguments) ?: arguments
                tool.copy(input = canonical.toString())
            } catch (error: ToolArgumentException) {
                tool.copy(output = toolError("invalid_tool_arguments", error.message.orEmpty()))
            }
        }

        // Older stored cards may share an ID. An ambiguous approval must never transfer
        // to another call; completed evidence stays attached to its original part.
        val duplicateUnexecutedIds = messages.flatMap { it.getTools() }.filterNot { it.isExecuted }
            .groupingBy { it.toolCallId }.eachCount().filterValues { it > 1 }.keys
        val repaired = messages.map { message ->
            val calls = message.getTools()
            val ambiguous = calls.groupingBy { it.toolCallId }.eachCount().filter { (id, count) -> id.isBlank() || count > 1 }.keys
            fun affected(call: UIMessagePart.Tool) = call.toolCallId in ambiguous ||
                (!call.isExecuted && call.toolCallId in duplicateUnexecutedIds)
            if (calls.none(::affected)) message else {
                val used = calls.filterNot(::affected).mapTo(mutableSetOf()) { it.toolCallId }
                message.copy(parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Tool || !affected(part)) part else {
                        var id: String
                        do { id = "call_${Uuid.random()}" } while (!used.add(id))
                        part.copy(toolCallId = id, output = if (part.isExecuted) part.output else toolError(
                            "duplicate_tool_call_id", "Сохранённые вызовы имеют неоднозначный идентификатор. Проверьте результат и запросите новый вызов с отдельным подтверждением."
                        ))
                    }
                })
            }
        }
        if (repaired != messages) { messages = repaired; emit(GenerationChunk.Messages(messages)) }

        val turnStartMs = SystemClock.elapsedRealtime()
        val turnBudgetMs = ToolRuntimeLimits.turnBudgetMs
        var responseFinished = false
        var waitingForApproval = false
        var limitReason: String? = null
        for (stepIndex in 0 until minOf(maxSteps, ToolRuntimeLimits.maxToolSteps)) {
            if (SystemClock.elapsedRealtime() - turnStartMs >= turnBudgetMs) {
                limitReason = "time_limit"
                break
            }
            budget?.ensureCanContinue()
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // Also repair malformed calls restored from older versions before any approval check.
            messages.lastOrNull()?.let { last ->
                val checked = last.copy(parts = last.parts.map { part ->
                    if (part is UIMessagePart.Tool) validateArguments(part) else part
                })
                if (checked != last) {
                    messages = messages.dropLast(1) + checked
                    emit(GenerationChunk.Messages(messages))
                }
            }
            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution || canResumeAutomatic(it)
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            if (pendingTools.isEmpty() && messages.lastOrNull()?.getTools()?.any { it.isPending } == true) {
                waitingForApproval = true
                break
            }
            onStepStarted?.invoke()

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                if (AutoContextCompression.plan(messages, assistant) != null) {
                    // Flush buffered tool results before the service inserts a stored checkpoint.
                    awaitRootCheckpoint { ack -> emit(GenerationChunk.RootExecutionCheckpoint(messages, ack)) }
                    val compressed = if (budget != null) withContext(TokenBudgetContext(budget)) {
                        budget.ensureCanContinue()
                        onAutoCompress(messages)
                    } else onAutoCompress(messages)
                    compressed?.let { messages = it }
                    budget?.ensureCanContinue()
                }
                try {
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        messages = messages,
                        onUpdateMessages = {
                            messages = it.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                            if (WebContentGuard.hasWebContent(messages)) onWebContentRead()
                            emit(
                                GenerationChunk.Messages(
                                    messages.visualTransforms(
                                        transformers = outputTransformers,
                                        context = context,
                                        model = model,
                                        assistant = assistant,
                                        settings = settings
                                    )
                                )
                            )
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = tools,
                        memories = memories ?: emptyList(),
                        stream = assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationId = conversationId,
                        workspaceCwd = workspaceCwd,
                        tokenBudget = budget,
                        beforeModelRequest = beforeModelRequest,
                    )
                } catch (error: TokenBudgetExceededException) {
                    // flowOn can discard buffered Messages when the producer fails. Await the
                    // collector's durable write before stopping a response at the token cap.
                    val checkpoint = messages.visualTransforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    )
                    awaitRootCheckpoint { ack -> emit(GenerationChunk.RootExecutionCheckpoint(checkpoint, ack)) }
                    throw error
                }
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val toolCalls = messages.last().getTools().filter { !it.isExecuted }
                if (toolCalls.isEmpty()) {
                    // no tool calls, break
                    responseFinished = true
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = ArrayList<UIMessagePart.Tool>(toolCalls.size)
                for (original in toolCalls) {
                    val tool = validateArguments(original)
                    val toolDef = tools.find { it.name == tool.toolName }
                    val hardlineReason = HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                    val updated = when {
                        tool.isExecuted -> tool
                        hardlineReason != null -> {
                            Log.w(TAG, "hardline-blocked ${tool.toolName}: $hardlineReason")
                            tool.copy(output = toolError("hardline_blocked", hardlineMessage(hardlineReason)))
                        }
                        // Tool needs approval and state is Auto -> set to Pending
                        tool.approvalState is ToolApprovalState.Auto && toolDef != null && needsApproval(tool, toolDef) -> {
                            hasPendingApproval = true
                            tool.copy(
                                input = if (tool.toolName in setOf("manage_app_settings", "manage_assistants"))
                                    prepareToolApproval(tool.toolName, tool.inputAsJson()).toString() else tool.input,
                                approvalState = ToolApprovalState.Pending,
                            )
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                    updatedTools.add(updated)
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != toolCalls) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval && updatedTools.none(::canResumeAutomatic)) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    waitingForApproval = true
                    break
                }

                // Automatic permission-controlled siblings may run while another tool waits for approval.
                toolsToProcess = updatedTools.filter { !it.isExecuted && (!hasPendingApproval || canResumeAutomatic(it)) }
                // Invalid arguments and safety blocks are already tool results for the next request.
                if (toolsToProcess.isEmpty() && updatedTools.any { it.isExecuted }) continue
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution || canResumeAutomatic(it) }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                budget?.ensureCanContinue()
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive(if (reason.isBlank()) "Пользователь отклонил выполнение. Повторный вызов возможен после нового подтверждения."
                                                else "Пользователь отклонил выполнение. Причина: $reason")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        val resumeHardlineReason = HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                        if (resumeHardlineReason != null) {
                            executedTools += tool.copy(output = listOf(UIMessagePart.Text(
                                json.encodeToString(buildJsonObject {
                                    put("error", JsonPrimitive(hardlineMessage(resumeHardlineReason)))
                                })
                            )))
                            return@forEach
                        }
                        if (tools.none { it.name == tool.toolName }) {
                            executedTools += tool.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
                                put("error", "tool_not_found")
                                put("detail", "Инструмент '${tool.toolName}' недоступен в этом ответе; после изменения MCP начните новый ответ.")
                                put("tools_available_this_turn", tools.joinToString(", ") { it.name }.take(1500))
                            }.toString())))
                            return@forEach
                        }
                        runCatching {
                            val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Инструмент недоступен. Начните новый ответ после изменения настроек инструментов.")
                            val parsedArgs = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Некорректные аргументы инструмента. Проверьте формат JSON и типы значений.")
                            }
                            if (tool.approvalState == ToolApprovalState.Auto && needsApproval(tool, toolDef)
                            ) {
                                // Live revocation must also cover resumed/mixed batches.
                                executedTools += tool.copy(
                                    input = if (tool.toolName in setOf("manage_app_settings", "manage_assistants"))
                                        prepareToolApproval(tool.toolName, parsedArgs).toString() else tool.input,
                                    approvalState = ToolApprovalState.Pending,
                                )
                                return@forEach
                            }
                            // Automatic settings changes still need the runtime-built preview it verifies.
                            val args = if (tool.toolName in setOf("manage_app_settings", "manage_assistants") && tool.approvalState == ToolApprovalState.Auto)
                                prepareToolApproval(tool.toolName, parsedArgs) else parsedArgs
                            Log.i(TAG, "generateText: executing tool ${toolDef.name}")
                            suspend fun persistStarted(started: UIMessagePart.Tool) {
                                val last = messages.last()
                                val checkpoint = messages.dropLast(1) + last.copy(parts = last.parts.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == started.toolCallId) started else part
                                })
                                awaitRootCheckpoint { ack ->
                                    emit(GenerationChunk.RootExecutionCheckpoint(checkpoint, ack))
                                }
                                messages = checkpoint
                            }
                            suspend fun executeWithBudget(): List<UIMessagePart> {
                                budget?.ensureCanContinue()
                                val remainingMs = turnBudgetMs - (SystemClock.elapsedRealtime() - turnStartMs)
                                val cancelled = listOf(UIMessagePart.Text(buildJsonObject {
                                    put("error", "tool_cancelled_wall_clock")
                                    put("message", "Истёк общий лимит времени на инструменты этого ответа.")
                                }.toString()))
                                if (remainingMs <= 0) return cancelled
                                return withTimeoutOrNull(remainingMs) {
                                    if (budget == null) toolDef.execute(args) else
                                        withContext(TokenBudgetContext(budget)) {
                                            budget.ensureCanContinue()
                                            toolDef.execute(args)
                                        }
                                } ?: cancelled
                            }
                            budget?.ensureCanContinue()
                            val result = if (tool.toolName == "root_exec") {
                                executeRootToolOnce(tool, persistStarted = ::persistStarted,
                                    automaticAllowed = { !needsApproval(tool, toolDef) }) { executeWithBudget() }
                            } else {
                                persistStarted(tool.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
                                    put("error", "tool_execution_indeterminate")
                                    put("checkpointed_before_launch", true)
                                    put("reason", "Выполнение инструмента было прервано; результат неизвестен. Проверьте, что успело выполниться, перед новым вызовом. Этот вызов автоматически не повторяется.")
                                }.toString()))))
                                currentCoroutineContext().ensureActive()
                                check(tool.approvalState == ToolApprovalState.Approved || !needsApproval(tool, toolDef)) {
                                    "Автоматическое разрешение отозвано до запуска. Подтвердите новый вызов инструмента."
                                }
                                executeWithBudget()
                            }
                            // Apply the guard before the next sibling, not after the entire batch.
                            if (WebContentGuard.isClientReader(tool.toolName)) onWebContentRead()
                            val hasShellAccess = tools.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool, result, hasShellAccess)
                                    .ifEmpty { listOf(UIMessagePart.Text("Инструмент выполнен без вывода.")) }
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is ToolExecutionAbortedException) throw it
                            if (it is CancellationException) currentCoroutineContext().ensureActive()
                            if (it is TokenBudgetExceededException || it is TokenBudgetPersistenceException) throw it
                            // Root input may contain credentials; neither exception details nor arguments belong in logs.
                            if (tool.toolName != "root_exec") it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                if (tool.toolName == "root_exec") {
                                                    put("error", "root_execution_indeterminate")
                                                    put("reason", "Не удалось подтвердить результат root-команды. Проверьте устройство перед новым вызовом. Эта команда автоматически не повторяется.")
                                                } else if (it is TimeoutCancellationException) {
                                                    put("error", "tool_timeout")
                                                    put("message", "Превышено время ожидания инструмента. Проверьте результат перед повторным вызовом.")
                                                } else if (it is CancellationException) {
                                                    put("error", "tool_cancelled")
                                                    put("message", "Выполнение инструмента отменено. Проверьте результат перед повторным вызовом.")
                                                } else {
                                                    put("error", me.rerere.rikkahub.data.ai.tools.local.deviceToolFailureReason(it))
                                                    put("raw_error", SecretRedactor.redact(it.message ?: it.javaClass.name))
                                                }
                                                put("detail", it.javaClass.name)
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                waitingForApproval = true
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
            if (messages.last().getTools().any { it.isPending }) {
                waitingForApproval = true
                break
            }
        }
        if (!responseFinished && !waitingForApproval) {
            val reason = limitReason ?: if (SystemClock.elapsedRealtime() - turnStartMs >= turnBudgetMs)
                "time_limit" else "step_limit"
            onLimitReached?.invoke(reason)
            val notice = UIMessagePart.Text(if (reason == "time_limit")
                "Достигнут лимит времени ответа. Выполнение остановлено. Проверьте результаты инструментов перед продолжением."
            else "Достигнут лимит шагов ответа. Выполнение остановлено. Проверьте результаты инструментов перед продолжением.",
                metadata = generationNoticeMetadata())
            val last = messages.lastOrNull()
            messages = if (last?.role == MessageRole.ASSISTANT)
                messages.dropLast(1) + last.copy(parts = last.parts + notice)
            else messages + UIMessage.assistant("").copy(parts = listOf(notice))
            emit(GenerationChunk.Messages(messages))
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        tokenBudget: TokenBudgetLedger? = null,
        beforeModelRequest: suspend () -> Unit = {},
    ) {
        val rawMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(AutoContextCompression.activeMessages(messages,
                if (assistant.autoCompressContext) 0 else assistant.contextMessageLimit))
        }
        suspend fun transformInput() = rawMessages.transforms(
            transformers = transformers, context = context, model = model, assistant = assistant,
            settings = settings, processingStatus = processingStatus, workspaceCwd = workspaceCwd,
        )
        // OCR runs before the main reservation and must inherit this exact explicit ledger.
        // Keep the context limited to transforms: onUpdateMessages emits into the outer Flow.
        val internalMessages = modelVisibleMessages(if (tokenBudget != null) withContext(TokenBudgetContext(tokenBudget)) {
            transformInput()
        } else transformInput())

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = (conversationId ?: Uuid.random()).toString(),
        )
        // Approximate transformed text/tool prompt, not a provider tokenizer or image bill.
        // Actual late usage is authoritative; reservations serialize competing child requests.
        val estimatedInput = internalMessages.fold(0L) { count, message ->
            TokenBudgetTracker.saturatedAdd(count, message.toText().toByteArray().size.toLong() / 3 + 16)
        }.let { textTokens -> tools.fold(textTokens) { count, tool ->
            TokenBudgetTracker.saturatedAdd(count,
                (tool.name.length.toLong() + tool.description.length + tool.parameters().toString().length) / 3 + 16)
        } }
        fun requestParams(reservation: TokenBudgetLedger.Reservation?): TextGenerationParams {
            if (reservation == null) return params
            val bounded = params.copy(maxTokens = reservation.maxTokens)
            return if (tokenBudget?.enabled == true && tokenBudget.hardCap != null) bounded.copy(
                customBody = boundedCustomBodies(bounded.customBody, reservation.maxTokens)) else bounded
        }
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                val responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                var retryCount = 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    val reservation = tokenBudget?.reserve(estimatedInput, params.maxTokens)
                    try {
                        currentCoroutineContext().ensureActive()
                        tokenBudget?.ensureCanContinue()
                        beforeModelRequest()
                        currentCoroutineContext().ensureActive()
                        tokenBudget?.ensureCanContinue()
                        reservation?.markSubmitted()
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = requestParams(reservation)
                        ).collect { chunk ->
                            try {
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                if (chunk is StreamChunk.Usage) reservation?.observe(chunk.usage)
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages)
                                tokenBudget?.ensureCanContinue()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        reservation?.markCompleted()
                        messages = attemptMessages
                        break
                    } catch (error: Throwable) {
                        // Charge a launched request lacking usage before deciding whether a retry fits.
                        val primary = try {
                            currentCoroutineContext().ensureActive()
                            error
                        } catch (cancelled: CancellationException) { cancelled }
                        val cleanupSucceeded = reservation?.closePreserving(primary) ?: true
                        if (primary is CancellationException || !cleanupSucceeded) throw primary
                        tokenBudget?.ensureCanContinue()
                        if (primary is StreamChunkHandlingException) {
                            throw primary.cause ?: primary
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = primary,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    } finally {
                        reservation?.close()
                    }
                }
            } else {
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry,
                ) {
                    val reservation = tokenBudget?.reserve(estimatedInput, params.maxTokens)
                    try {
                        currentCoroutineContext().ensureActive()
                        tokenBudget?.ensureCanContinue()
                        beforeModelRequest()
                        currentCoroutineContext().ensureActive()
                        tokenBudget?.ensureCanContinue()
                        reservation?.markSubmitted()
                        providerImpl.generateText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = requestParams(reservation),
                        ).also {
                            reservation?.observe(it.usage)
                            reservation?.markCompleted()
                        }
                    } catch (error: Throwable) {
                        val primary = try {
                            currentCoroutineContext().ensureActive()
                            error
                        } catch (cancelled: CancellationException) { cancelled }
                        val cleanupSucceeded = reservation?.closePreserving(primary) ?: true
                        if (primary is CancellationException || !cleanupSucceeded) throw primary
                        tokenBudget?.ensureCanContinue()
                        throw primary
                    } finally {
                        reservation?.close()
                    }
                }
                messages = messages.handleTextGenerationResult(result = result, model = model)
                onUpdateMessages(messages)
                tokenBudget?.ensureCanContinue()
            }
        } finally {
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        if (error is CancellationException) throw error
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (!enabled || error is TokenBudgetPersistenceException || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES), type=${error.javaClass.simpleName}",
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
        openDirectory: ((java.nio.file.Path) -> java.nio.file.DirectoryStream<java.nio.file.Path>)? = null,
        openWriter: ((java.nio.file.Path) -> java.io.Writer)? = null,
    ): List<UIMessagePart> {
        if (hasShellAccess && tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output

        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        // Without a shell the model cannot read a saved file: the message keeps the full result
        // for the UI, and boundToolOutputsForModel shortens only the copy sent to the provider.
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        val fullText = textParts.joinToString("\n") { it.text }
        var previewEnd = minOf(fullText.length, TOOL_OUTPUT_PREVIEW_CHARS)
        if (previewEnd < fullText.length && previewEnd > 0 &&
            fullText[previewEnd - 1].isHighSurrogate() && fullText[previewEnd].isLowSurrogate()
        ) previewEnd--
        val preview = fullText.substring(0, previewEnd)

        // This directory is mounted into the workspace. Pin writes to its opened inode;
        // a pathname check alone cannot prevent a concurrent parent-directory replacement.
        val fileName = "${Uuid.random()}.txt"
        try {
            val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            val directoryPath = outputDir.toPath()
            fun liveDirectoryKey(): Any {
                val attributes = java.nio.file.Files.readAttributes(
                    directoryPath, java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
                if (!attributes.isDirectory || attributes.isSymbolicLink ||
                    outputDir.canonicalFile.parentFile != context.filesDir.canonicalFile
                ) throw IOException("Каталог архива вывода недоступен.")
                return attributes.fileKey() ?: throw IOException("Не удалось проверить каталог архива вывода.")
            }
            val expectedKey = liveDirectoryKey()
            (openDirectory?.invoke(directoryPath) ?: java.nio.file.Files.newDirectoryStream(directoryPath)).use { directory ->
                if (directory !is java.nio.file.SecureDirectoryStream<java.nio.file.Path>) {
                    throw IOException("Безопасная запись архива вывода недоступна.")
                }
                val openedKey = directory.getFileAttributeView(
                    java.nio.file.attribute.BasicFileAttributeView::class.java,
                )?.readAttributes()?.fileKey()
                    ?: throw IOException("Не удалось проверить открытый каталог архива вывода.")
                if (openedKey != expectedKey || liveDirectoryKey() != openedKey) {
                    throw IOException("Каталог архива вывода изменён.")
                }
                val options = setOf<java.nio.file.OpenOption>(
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
                directory.newByteChannel(File(fileName).toPath(), options).use { channel ->
                    java.nio.channels.Channels.newWriter(channel, Charsets.UTF_8.newEncoder(), -1).use { writer ->
                        writer.write(fullText)
                    }
                }
                if (liveDirectoryKey() != openedKey) throw IOException("Каталог архива вывода изменён.")
            }
        } catch (_: IOException) {
            Log.w(TAG, "Не удалось записать архив вывода инструмента; полный результат сохранён в сообщении.")
            return output
        } catch (_: UnsupportedOperationException) {
            Log.w(TAG, "Безопасная запись архива вывода недоступна; полный результат сохранён в сообщении.")
            return output
        }

        return listOf(
            UIMessagePart.Text(buildJsonObject {
                put("path", "/tool_outputs/$fileName")
                put("preview", preview)
                put("preview_chars", preview.length)
                put("truncated", true)
                put("total_chars", fullText.length)
                put("message", "Показан фрагмент результата. Полный исходный вывод сохранён по пути path; используйте доступный инструмент shell для чтения или поиска в файле.")
            }.toString())
        ) + nonTextParts
    }

}

private const val GENERATION_NOTICE_KEY = "rikka_generation_notice"

private fun generationNoticeMetadata() = buildJsonObject {
    put(GENERATION_NOTICE_KEY, JsonPrimitive(true))
}

private fun UIMessagePart.isGenerationNotice(): Boolean =
    this is UIMessagePart.Text && (metadata?.get(GENERATION_NOTICE_KEY) as? JsonPrimitive)?.booleanOrNull == true

/**
 * The provider copy of the history: app notices for the user (step or time limit) are not the
 * model's own words, and tool results are bounded.
 */
internal fun modelVisibleMessages(messages: List<UIMessage>): List<UIMessage> = boundToolOutputsForModel(
    repeatedToolWarnings(messages).mapNotNull { message ->
        if (message.parts.none { it.isGenerationNotice() }) return@mapNotNull message
        message.copy(parts = message.parts.filterNot { it.isGenerationNotice() }).takeIf { it.parts.isNotEmpty() }
    }
)

private fun repeatedToolWarnings(messages: List<UIMessage>): List<UIMessage> {
    val turnStart = messages.indexOfLast { it.role == MessageRole.USER }
    if (turnStart < 0) return messages
    val seen = mutableSetOf<Pair<String, JsonElement>>()
    return messages.mapIndexed { index, message ->
        if (index <= turnStart) message else message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || !part.isExecuted) part else {
                val arguments = runCatching { Json.parseToJsonElement(part.input.ifBlank { "{}" }) }.getOrNull()
                if (arguments == null || seen.add(part.toolName to stableToolArguments(arguments))) part
                // Put the notice first so the existing result cap cannot clip it away.
                else part.copy(output = listOf(UIMessagePart.Text(
                    "Предупреждение: повторный вызов этого инструмента с теми же параметрами в текущем ходе. Проверьте предыдущий результат; при необходимости измените параметры или выберите другой способ."
                )) + part.output)
            }
        })
    }
}

private fun stableToolArguments(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { stableToolArguments(it.value) })
    is JsonArray -> JsonArray(value.map(::stableToolArguments))
    is JsonPrimitive -> if (value.isString || value.contentOrNull == null || value.booleanOrNull != null) value else
        runCatching { Json.parseToJsonElement(java.math.BigDecimal(value.content).stripTrailingZeros().toString()) }.getOrDefault(value)
}

/**
 * Bounds every tool result in the provider request. Stored messages keep the full output, so
 * UI cards still parse complete JSON and a later request can bound it again.
 */
internal fun boundToolOutputsForModel(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    if (message.parts.none { it is UIMessagePart.Tool && it.output.textLength() > MAX_TOOL_OUTPUT_CHARS }) return@map message
    message.copy(parts = message.parts.map { part ->
        if (part !is UIMessagePart.Tool || part.output.textLength() <= MAX_TOOL_OUTPUT_CHARS) part
        else part.copy(output = boundedToolOutput(part.toolName, part.output))
    })
}

private fun List<UIMessagePart>.textLength(): Int = filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }

private fun boundedToolOutput(toolName: String, output: List<UIMessagePart>): List<UIMessagePart> {
    val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val nonTextParts = output.filter { it !is UIMessagePart.Text }
    if (toolName == "search_web") {
        boundedSearchOutput(text, MAX_TOOL_OUTPUT_CHARS)?.let { return listOf(UIMessagePart.Text(it)) + nonTextParts }
    }
    return listOf(UIMessagePart.Text(boundedToolOutputEnvelope(text))) + nonTextParts
}

/** The serialized envelope, including escaping and guidance, fits the existing provider budget. */
private fun boundedToolOutputEnvelope(text: String): String {
    fun render(prefixLength: Int): String {
        val end = if (prefixLength > 0 && prefixLength < text.length &&
            text[prefixLength - 1].isHighSurrogate() && text[prefixLength].isLowSurrogate()) prefixLength - 1 else prefixLength
        return buildJsonObject {
            put("truncated", true)
            put("total_chars", text.length)
            put("preview", text.substring(0, end))
            put("message", "Вывод инструмента сокращён; preview содержит только начало. Полный исходный результат сохранён в истории чата. Для дальнейшего чтения уточните поддерживаемые фильтры или диапазон. Не повторяйте изменяющие данные действия автоматически ради полного вывода.")
        }.toString()
    }
    var low = 0
    var high = minOf(text.length, MAX_TOOL_OUTPUT_CHARS)
    var result = render(0)
    while (low <= high) {
        val middle = low + (high - low) / 2
        val candidate = render(middle)
        if (candidate.length <= MAX_TOOL_OUTPUT_CHARS) {
            result = candidate
            low = middle + 1
        } else high = middle - 1
    }
    return result
}
