package me.rerere.rikkahub.workflow.execution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.ai.tools.RunWebTaint
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.workflow.condition.ConditionEvaluator
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.trigger.TriggerFireCallback
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 12 — workflow execution engine. The single entry point for any workflow fire.
 *
 * Lifecycle of a fire (matches `headless = true` semantics from cron jobs):
 *  1. Lookup workflow + verify enabled.
 *  2. Cooldown check — `lastRunAtMs + cooldownSeconds` against now.
 *  3. Daily-cap check — counted fires (SUCCESS+FAILED) for today's local date.
 *  4. Build [WorkflowContext] — lazy on location for sunset/sunrise conditions.
 *  5. Evaluate conditions; AND-combined.
 *  6. Resolve the original owner and require its Workflows switch to remain enabled.
 *     Root never adopts a different assistant or its tool permissions.
 *  7. Execute action sequence via [DirectModeActionRunner] — every action HARDLINE-checked.
 *  8. Persist run row, projected last-run state, daily counter, trim history.
 *
 * Concurrency: per-workflow mutex so two near-simultaneous fires (e.g. WiFi flicker) can't
 * race on the daily counter. Cross-workflow execution stays parallel.
 *
 * Approval semantics: HARDLINE applies in workflow context. Tool factories that set
 * `needsApproval = { true }` would normally pop a prompt — workflows are headless and the
 * pre-authorisation is the workflow_create approval the user already granted. So the
 * action runner delegates to Root's guarded HeadlessTaskRunner. Current feature switches,
 * owner, Android grants, mandatory confirmations and HARDLINE are checked for every action.
 *
 * The `Workflows` toggle gates authoring and the owner's headless executions in Root.
 * Trigger dispatch also checks the workflow's own live enabled flag.
 */
class WorkflowEngine(
    private val repository: WorkflowRepository,
    private val settingsStore: SettingsStore,
    private val contextProvider: ContextProvider,
    private val actionRunner: WorkflowActionRunner,
) {

    private val perWorkflowLocks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()

    private suspend fun lockFor(id: String): Mutex = locksMutex.withLock {
        perWorkflowLocks.getOrPut(id) { Mutex() }
    }

    /**
     * Drop the lock entry for a deleted workflow. Wired from
     * [me.rerere.rikkahub.workflow.repository.WorkflowRepository.deleteCascading] so the
     * lock map can't grow unbounded across heavy LLM-driven create/delete churn.
     */
    suspend fun forgetWorkflow(id: String) {
        locksMutex.withLock { perWorkflowLocks.remove(id) }
    }

    /**
     * Trigger callback target. The registry hands every fire here. [matchSpec] is the
     * variant that fired — used for diagnostics; the workflow's own [WorkflowDefinition.trigger]
     * is the source of truth for its semantics.
     */
    val triggerCallback = TriggerFireCallback { workflowId, _ -> fire(workflowId) }

    /**
     * Fire a workflow. Resolves cooldown / daily cap / conditions, then runs the action
     * sequence. Returns the resulting status — useful for `workflow_run` synchronous tool
     * call, ignored by the trigger callback path.
     */
    suspend fun fire(workflowId: String): FireOutcome = withContext(Dispatchers.IO) {
        val lock = lockFor(workflowId)
        lock.withLock { fireLocked(workflowId) }
    }

    private suspend fun fireLocked(workflowId: String): FireOutcome {
        val firedAtMs = System.currentTimeMillis()
        val started = System.nanoTime()
        val loaded = repository.getById(workflowId)
            ?: return FireOutcome(WorkflowRunStatus.FAILED, "workflow_not_found", "")
        val def = loaded.definition
        val entity = loaded.entity

        if (!entity.enabled) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DISABLED, null, "")
        }

        // Trigger runtime pre-flight — surface "this trigger needs setup" as an explicit
        // FAILED row in history so the user sees WHY the workflow doesn't fire instead of
        // just "Never run". The audit found these were silently dying:
        //  - geofence triggers without ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION
        //  - notification_received without notification listener bound
        //  - app_launched / app_closed without accessibility service running
        triggerRuntimeCheck(def.trigger)?.let { reason ->
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED, reason, "")
        }

        // Cooldown gate. NOTE: must use `lastActualFireAtMs` (most-recent SUCCESS/FAILED
        // from history) — NOT `entity.lastRunAtMs`, which gets overwritten on every
        // attempt INCLUDING skips. Using the projected column would let SKIPPED_COOLDOWN
        // fires push the cooldown window forward indefinitely; the cooldown could never
        // be satisfied by waiting.
        val lastActualFireMs = if (def.cooldownSeconds > 0) repository.lastActualFireAtMs(workflowId) else null
        if (CooldownGate.isWithinCooldown(def.cooldownSeconds, lastActualFireMs, firedAtMs)) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_COOLDOWN, null, "")
        }

        // Daily-cap gate
        if (def.maxRunsPerDay != null) {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            val countedToday = if (entity.runsTodayDate == today) entity.runsTodayCount else 0
            if (countedToday >= def.maxRunsPerDay) {
                return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DAILY_CAP, null, "")
            }
        }

        // Mandatory Root permission adaptation: a workflow never adopts another owner.
        val settings = settingsStore.settingsFlow.first()
        val authoringAssistant = settings.assistants.singleOrNull {
            it.id.toString() == def.authoringAssistantId
        }
        if (authoringAssistant == null ||
            me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Workflows !in authoringAssistant.localTools) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                "Владелец сценария отсутствует или выключил функцию «Сценарии».", "")
        }

        WorkflowAvailability.conditionReason(def.conditions)?.let { reason ->
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED, reason, "")
        }

        // Conditions
        if (def.conditions.isNotEmpty()) {
            val ctx = contextProvider.snapshot(needsLocation = ConditionEvaluator.needsLocation(def.conditions))
            val cr = ConditionEvaluator.evaluateAll(def.conditions, ctx)
            if (cr is ConditionEvaluator.Result.FailedAt) {
                return persistAndReturn(
                    workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_CONDITIONS,
                    "condition[${cr.index}] failed: ${cr.reason}", "",
                )
            }
        }

        // Execute the action sequence. ActionRunner enforces per-action timeout + HARDLINE.
        val result = actionRunner.run(def)
        val status = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
        return persistAndReturn(workflowId, firedAtMs, started, status, result.error, result.summary)
    }

    /**
     * Pre-flight check for trigger types that depend on runtime state (a permission, a
     * service binding, Play Services availability). Returns null if the trigger can fire,
     * or a stable error code otherwise — the engine then records the fire as FAILED with
     * that reason and the user sees a clear "missing setup" message in workflow_get history.
     */
    private fun triggerRuntimeCheck(trigger: me.rerere.rikkahub.workflow.model.TriggerSpec): String? {
        WorkflowAvailability.triggerReason(trigger)?.let { return it }
        val ctx = org.koin.java.KoinJavaComponent.getKoin().get<android.content.Context>()
        return WorkflowAvailability.runtimeReason(ctx, trigger)
    }

    private suspend fun persistAndReturn(
        workflowId: String,
        firedAtMs: Long,
        startedNanos: Long,
        status: WorkflowRunStatus,
        error: String?,
        summary: String,
    ): FireOutcome {
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
        runCatching {
            repository.recordFire(
                workflowId = workflowId,
                firedAtMs = firedAtMs,
                status = status,
                durationMs = durationMs,
                errorMessage = error,
            )
        }.onFailure { Log.w(TAG, "recordFire failed for $workflowId", it) }
        return FireOutcome(status, error, summary)
    }

    companion object { private const val TAG = "WorkflowEngine" }

    data class FireOutcome(
        val status: WorkflowRunStatus,
        val error: String?,
        val summary: String,
    )
}

/**
 * Cooldown decision in isolation so the (load-bearing) gate logic can be unit-tested
 * without spinning up Room + the engine. The rule: use the most-recent SUCCESS/FAILED
 * fire time, not the workflow row's projected lastRunAtMs (the projected column is
 * bumped on every attempt — including skips — so it can't be the cooldown anchor).
 */
internal object CooldownGate {
    fun isWithinCooldown(cooldownSeconds: Int, lastActualFireMs: Long?, nowMs: Long): Boolean {
        if (cooldownSeconds <= 0) return false
        if (lastActualFireMs == null) return false
        return nowMs < lastActualFireMs + cooldownSeconds * 1000L
    }
}

/**
 * Sequential action runner — wraps [me.rerere.rikkahub.service.DirectModeActionRunner]'s
 * core logic but on the workflow side, since direct-mode's own runner takes a slightly
 * different action shape. Same HARDLINE-then-execute semantics.
 *
 * Per-action timeout is the action's [WorkflowAction.timeoutSeconds] field; default 60s.
 */
class WorkflowActionRunner(
    private val runAction: suspend (WorkflowDefinition, WorkflowAction, RunWebTaint) -> HeadlessTaskResult,
) {

    data class RunResult(val success: Boolean, val error: String?, val summary: String)

    suspend fun run(definition: WorkflowDefinition): RunResult {
        val actions = definition.actions
        // Imported/legacy definitions may have no creator chat. Preserve the web-content
        // approval boundary between their separate headless action contexts in this fire.
        val webTaint = RunWebTaint()
        val outputs = mutableListOf<String>()
        for ((idx, action) in actions.withIndex()) {
            val hardlineReason = HeadlessToolPolicy.blockReason(action.tool, action.args)
            if (hardlineReason != null) {
                logSafe("workflow hardline-blocked action $idx tool=${action.tool}: $hardlineReason")
                return RunResult(success = false,
                    error = "action $idx: hardline:$hardlineReason",
                    summary = outputs.joinToString("\n"))
            }
            val out = try {
                withTimeoutOrNull(action.timeoutSeconds * 1000L) { runAction(definition, action, webTaint) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Don't swallow cancellation — re-throw so structured concurrency can
                // unwind the fire (e.g. the engine scope is cancelled on shutdown). The
                // generic catch below would otherwise turn it into a spurious FAILED row.
                throw c
            } catch (t: Throwable) {
                logSafe("workflow action $idx tool=${action.tool} threw: ${t::class.simpleName}")
                return RunResult(false,
                    "action $idx: ${t::class.simpleName}",
                    outputs.joinToString("\n"))
            }
            if (out == null) {
                return RunResult(false,
                    "action $idx: ${action.tool} exceeded ${action.timeoutSeconds}s",
                    outputs.joinToString("\n"))
            }
            if (out.status != HeadlessTaskStatus.SUCCEEDED) {
                return RunResult(false, "action $idx: ${out.errorCode ?: out.status.name}", outputs.joinToString("\n"))
            }
            // The normal headless runner applies fresh permissions, private side-context and HARDLINE.
            val text = out.output.orEmpty()
            outputs += "[$idx] ${action.tool}: ${text.take(200)}"
        }
        return RunResult(true, null, outputs.joinToString("\n").take(2000))
    }

    /**
     * Wrap [Log.w] in a guard so JVM unit tests (where android.util.Log is unmocked)
     * don't crash before the runner can return its actual result.
     */
    private fun logSafe(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    companion object { private const val TAG = "WorkflowActionRunner" }
}
