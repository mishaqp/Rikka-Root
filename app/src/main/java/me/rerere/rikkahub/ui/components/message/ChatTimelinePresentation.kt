package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.time.Duration
import kotlin.time.Instant

/** Presentation-only state. It never changes the stored message or tool execution. */
enum class TimelineStatus {
    Running, Waiting, AwaitingApproval, AwaitingAnswer, Answered, Denied,
    ResultReceived, Succeeded, Failed, Blocked, Indeterminate,
}

internal data class TimelineStepPresentation(
    val step: ThinkingStep,
    val key: String,
    val status: TimelineStatus,
    val mustStayVisible: Boolean,
)

internal data class TimelinePresentation(
    val steps: List<TimelineStepPresentation>,
    val collapsedVisibleIndices: List<Int>,
    val hiddenCount: Int,
    val canFoldToSummary: Boolean,
    val duration: Duration?,
)

internal fun presentTimeline(
    steps: List<ThinkingStep>,
    options: ToolDisplayOptions,
    loading: Boolean,
    messageKey: String,
    blockOrdinal: Int,
    activeToolCallId: String? = null,
): TimelinePresentation {
    val activeIdIsUnique = activeToolCallId != null && steps.count {
        it is ThinkingStep.ToolStep && it.tool.toolCallId == activeToolCallId
    } == 1
    val projected = steps.mapIndexed { index, step ->
        val status = when (step) {
            is ThinkingStep.ReasoningStep -> if (step.reasoning.finishedAt == null) TimelineStatus.Running else TimelineStatus.ResultReceived
            is ThinkingStep.ToolStep -> if (loading && activeIdIsUnique && step.tool.toolCallId == activeToolCallId &&
                step.tool.hasExecutionCheckpoint()) TimelineStatus.Running else presentToolStatus(step.tool, loading)
            is ThinkingStep.ServerToolStep -> when (step.tool.status) {
                ServerToolStatus.IN_PROGRESS -> TimelineStatus.Running
                ServerToolStatus.COMPLETED -> TimelineStatus.ResultReceived
                ServerToolStatus.FAILED -> TimelineStatus.Failed
            }
        }
        val id = when (step) {
            is ThinkingStep.ReasoningStep -> step.reasoning.createdAt.toString()
            is ThinkingStep.ToolStep -> step.tool.toolCallId
            is ThinkingStep.ServerToolStep -> step.tool.toolCallId
        }
        TimelineStepPresentation(
            step = step,
            key = "$messageKey:$blockOrdinal:$index:$id",
            status = status,
            mustStayVisible = when (step) {
                is ThinkingStep.ToolStep -> (step.tool.toolName == "ask_user" && status == TimelineStatus.AwaitingAnswer) ||
                    status in setOf(TimelineStatus.AwaitingApproval, TimelineStatus.Denied,
                        TimelineStatus.Failed, TimelineStatus.Blocked, TimelineStatus.Indeterminate,
                        TimelineStatus.Waiting, TimelineStatus.Running)
                is ThinkingStep.ReasoningStep -> status == TimelineStatus.Running || !options.autoCloseThinking
                is ThinkingStep.ServerToolStep -> status in setOf(TimelineStatus.Running, TimelineStatus.Failed)
            },
        )
    }
    val visible = if (options.autoCollapseSteps && steps.size > 2) {
        projected.indices.filter { index -> projected[index].mustStayVisible || index >= steps.size - 2 }
    } else projected.indices.toList()
    val safeToFold = projected.all { item ->
        item.status in setOf(TimelineStatus.ResultReceived, TimelineStatus.Succeeded, TimelineStatus.Answered) &&
            !item.mustStayVisible
    }
    val reasoningCanClose = options.autoCloseThinking || steps.none { it is ThinkingStep.ReasoningStep }
    return TimelinePresentation(
        steps = projected,
        collapsedVisibleIndices = visible,
        hiddenCount = steps.size - visible.size,
        canFoldToSummary = options.showSummary && !loading &&
            safeToFold && reasoningCanClose && steps.isNotEmpty(),
        duration = actualTimelineDuration(steps),
    )
}

/** No client-tool timestamps are stored in UIMessagePart.Tool, so mixed blocks use count fallback. */
private fun actualTimelineDuration(steps: List<ThinkingStep>): Duration? {
    if (steps.isEmpty() || steps.any { it !is ThinkingStep.ReasoningStep }) return null
    var first: Instant? = null
    var last: Instant? = null
    for (step in steps) {
        val reasoning = (step as ThinkingStep.ReasoningStep).reasoning
        val finished = reasoning.finishedAt ?: return null
        if (finished < reasoning.createdAt) return null
        if (first == null || reasoning.createdAt < first) first = reasoning.createdAt
        if (last == null || finished > last) last = finished
    }
    return last!! - first!!
}

internal fun presentToolStatus(tool: UIMessagePart.Tool, loading: Boolean): TimelineStatus {
    when (tool.approvalState) {
        is ToolApprovalState.Denied -> return TimelineStatus.Denied
        is ToolApprovalState.Answered -> if (tool.output.isEmpty()) return TimelineStatus.Answered
        ToolApprovalState.Pending -> if (tool.output.isEmpty()) return if (tool.toolName == "ask_user")
            TimelineStatus.AwaitingAnswer else TimelineStatus.AwaitingApproval
        else -> Unit
    }
    if (tool.hasExecutionCheckpoint()) return TimelineStatus.Indeterminate
    if (tool.output.isEmpty()) return TimelineStatus.Waiting
    val json = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }?.let { runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        ?: return TimelineStatus.ResultReceived
    val errorValue = json["error"]
    val error = errorValue as? JsonPrimitive
    val errorName = error?.contentOrNull
    if (errorName == "root_execution_indeterminate" || errorName == "root_cleanup_unconfirmed" ||
        errorName == "command_timeout" || errorName == "tool_execution_indeterminate") return TimelineStatus.Indeterminate
    if (errorName in setOf("root_interaction_required", "root_approval_required", "root_not_granted", "root_command_blocked"))
        return TimelineStatus.Blocked
    if (errorValue != null && errorValue != JsonNull && error?.booleanOrNull != false) return TimelineStatus.Failed
    if ((json["success"] as? JsonPrimitive)?.booleanOrNull == false) return TimelineStatus.Failed
    if (tool.toolName == "workspace_shell") {
        if ((json["timedOut"] as? JsonPrimitive)?.booleanOrNull == true) return TimelineStatus.Indeterminate
        val exit = (json["exitCode"] as? JsonPrimitive)?.intOrNull
        if (exit != null && exit != 0) return TimelineStatus.Failed
    }
    // These command tools report the process exit independently of transport success.
    if (tool.toolName in setOf("root_exec", "termux_run_command", "ssh_exec", "ssh_exec_saved")) {
        val exit = (json["exit_code"] as? JsonPrimitive)?.intOrNull
        if (exit != null && exit != 0) return TimelineStatus.Failed
    }
    if ((json["failed"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true) return TimelineStatus.Failed
    return if ((json["success"] as? JsonPrimitive)?.booleanOrNull == true) TimelineStatus.Succeeded
    else TimelineStatus.ResultReceived
}

internal fun UIMessagePart.Tool.hasExecutionCheckpoint(): Boolean {
    if (approvalState != ToolApprovalState.Approved && approvalState != ToolApprovalState.Auto) return false
    val text = (output.singleOrNull() as? UIMessagePart.Text)?.text ?: return false
    val expected = if (toolName == "root_exec") "root_execution_indeterminate" else "tool_execution_indeterminate"
    if (!text.startsWith("{\"error\":\"$expected\"")) return false
    val json = runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
    return (json["error"] as? JsonPrimitive)?.contentOrNull == expected &&
        (json["reason"] as? JsonPrimitive)?.contentOrNull?.contains("checkpointed before launch") == true
}
