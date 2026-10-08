package me.rerere.rikkahub.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.costguards.TokenBudgetContext
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.RunWebTaint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessTaskRunner
import kotlin.uuid.Uuid

data class SubAgentCaller(
    val owner: SubAgentOwner,
    val conversationConfig: ConversationConfig? = null,
    val workspaceCwd: String? = null,
    val webTaint: RunWebTaint = RunWebTaint(),
    val allowedTools: Set<String>? = null,
)

class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val runner: HeadlessTaskRunner,
    private val settings: suspend () -> Settings,
    private val scope: CoroutineScope,
    private val enabled: suspend (Uuid) -> Boolean,
    private val concurrencyLimit: suspend (Uuid) -> Int,
) {
    sealed interface DispatchResult {
        data class Ok(val run: SubAgentRun) : DispatchResult
        data class Reject(val error: String) : DispatchResult
    }

    suspend fun dispatch(caller: SubAgentCaller, request: SubAgentRequest): DispatchResult {
        if (currentCoroutineContext()[RunExecutionContext] != null) return DispatchResult.Reject("nested_dispatch_disabled")
        validateSubAgentRequest(request)?.let { return DispatchResult.Reject(it) }
        val ownerId = runCatching { Uuid.parse(caller.owner.assistantId) }.getOrNull()
            ?: return DispatchResult.Reject("invalid_owner")
        val conversationId = runCatching { Uuid.parse(caller.owner.conversationId) }.getOrNull()
            ?: return DispatchResult.Reject("invalid_owner")
        if (!enabled(ownerId)) return DispatchResult.Reject("feature_disabled")
        val current = settings()
        val assistant = current.assistants.singleOrNull { it.id == ownerId }
            ?: return DispatchResult.Reject("unknown_owner")
        val model = resolveSubAgentModel(current.providers, request.modelId,
            (caller.conversationConfig?.chatModelId ?: assistant.chatModelId ?: current.chatModelId).toString())
            ?: return DispatchResult.Reject("unknown_or_ambiguous_model")
        val requestedTools = request.tools?.toSet()
        if (caller.allowedTools != null && requestedTools?.any { it !in caller.allowedTools } == true) {
            return DispatchResult.Reject("tool_not_allowed")
        }
        val runId = Uuid.random()
        val context = RunExecutionContext(
            runId = runId, ownerAssistantId = ownerId, origin = RunOrigin.SUB_AGENT,
            callerConversationId = conversationId, callerConversationConfig = caller.conversationConfig,
            workspaceCwd = caller.workspaceCwd, modelId = model.id.toString(),
            allowedTools = requestedTools ?: caller.allowedTools, maxSteps = request.maxTrips,
            timeoutMillis = request.timeoutSeconds * 1_000L,
            webTaint = RunWebTaint(parent = caller.webTaint),
            costBudget = currentCoroutineContext()[TokenBudgetContext]?.ledger,
        )
        val run = SubAgentRun(runId.toString(), caller.owner, request.label?.takeIf { it.isNotBlank() } ?: "Субагент",
            model.id.toString(), noResult = request.noResult)
        if (!registry.reserve(run, concurrencyLimit(ownerId))) return DispatchResult.Reject("concurrency_limit")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = runner.run(HeadlessTaskRequest.Prompt(request.task.trim(),
                    request.systemPrompt ?: "Выполни указанную задачу в отдельном контексте и верни краткий итог. Не запрашивай интерактивный ввод."), context)
                registry.finish(caller.owner, run.id) { it.copy(
                    status = SubAgentStatus.valueOf(result.status.name),
                    result = result.output?.take(65_536).takeUnless { request.noResult }, errorCode = result.errorCode,
                    tokensIn = result.tokensIn, tokensOut = result.tokensOut, tripCount = result.steps,
                ) }
            } catch (cancelled: CancellationException) {
                registry.finish(caller.owner, run.id) { it.copy(
                    status = if (context.executionState.effectsInFlight) SubAgentStatus.INDETERMINATE else SubAgentStatus.CANCELLED,
                    errorCode = if (context.executionState.effectsInFlight) "execution_interrupted" else "cancelled",
                ) }
                throw cancelled
            } catch (_: Exception) {
                registry.finish(caller.owner, run.id) { it.copy(status = SubAgentStatus.FAILED, errorCode = "run_failed") }
            }
        }
        job.invokeOnCompletion {
            registry.finish(caller.owner, run.id) { runState -> runState.copy(
                status = if (context.executionState.effectsInFlight) SubAgentStatus.INDETERMINATE else SubAgentStatus.CANCELLED,
                errorCode = "cancelled",
            ) }
        }
        registry.attach(caller.owner, run.id, job)
        job.start()
        if (!request.runInBackground) {
            try { job.join() } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { job.cancelAndJoin() }
                throw cancelled
            }
        }
        return DispatchResult.Ok(registry.get(caller.owner, run.id) ?: run)
    }
}
