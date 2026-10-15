package me.rerere.rikkahub.ui.components.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.ui.components.message.hasExecutionCheckpoint
import kotlin.uuid.Uuid

internal data class RunningToolPresentation(
    val toolCallId: String,
    val toolName: String,
    val command: String,
    val latestOutputLine: String?,
    val observedAtMillis: Long,
    val otherActiveCount: Int,
) {
    val outputLabel: String get() = latestOutputLine ?: "Ожидание вывода"
}

/**
 * Ephemeral UI state, never written into a message or used to decide tool execution.
 *
 * Approved/automatic calls are only queued. GenerationLoop publishes a recovery checkpoint
 * before launching each client tool, then replaces every sibling's output together at batch
 * completion. Older checkpoint outputs therefore cannot be counted as parallel executions.
 * A checkpoint already present when the page opens, generation resumes, or the branch changes
 * is not evidence of a new invocation. Only a fresh checkpoint or a newly generated message
 * can activate this strip. The timer measures time since that observation, not process uptime.
 */
internal data class RunningToolObservation(
    val conversationId: Uuid? = null,
    val nodeIds: List<Uuid> = emptyList(),
    val nodeMessageIds: Map<Uuid, Set<Uuid>> = emptyMap(),
    val selectedMessageKeys: Set<String> = emptySet(),
    val checkpointKeys: Set<RunningToolCallKey> = emptySet(),
    val activeClientKey: RunningToolCallKey? = null,
    val clientObservedAtMillis: Long? = null,
    val wasLoading: Boolean = false,
    val presentation: RunningToolPresentation? = null,
)

internal data class RunningToolCallKey(val messageKey: String, val toolCallId: String)

internal fun observeRunningTools(
    previous: RunningToolObservation,
    conversation: Conversation,
    loading: Boolean,
    observedAtMillis: Long,
): RunningToolObservation {
    val nodeIds = conversation.messageNodes.map { it.id }
    val nodeMessageIds = if (conversation.historyLoadState == HistoryLoadState.COMPLETE)
        conversation.messageNodes.associate { it.id to it.messages.mapTo(linkedSetOf()) { message -> message.id } }
    else emptyMap()
    val sameConversation = previous.conversationId == conversation.id
    val selectedMessageKeys = linkedSetOf<String>()
    val freshEligibleMessageKeys = linkedSetOf<String>()
    val checkpoints = linkedMapOf<RunningToolCallKey, UIMessagePart.Tool>()
    if (conversation.historyLoadState == HistoryLoadState.COMPLETE) conversation.messageNodes.forEach { node ->
        val message = node.messages.getOrNull(node.selectIndex) ?: return@forEach
        val messageKey = "${node.id}/${node.selectIndex}/${message.id}"
        selectedMessageKeys += messageKey
        // Regenerating an earlier reply keeps later nodes; inspect every selected message.
        // Conflation can also skip the empty tool call before a new message's checkpoint.
        val sameSelectedMessage = sameConversation && messageKey in previous.selectedMessageKeys
        val newlyGeneratedMessage = sameConversation && previous.nodeMessageIds.isNotEmpty() &&
            (previous.nodeMessageIds[node.id]?.let { message.id !in it } ?: (
                nodeIds.size > previous.nodeIds.size && nodeIds.take(previous.nodeIds.size) == previous.nodeIds
            ))
        if (sameSelectedMessage || newlyGeneratedMessage) freshEligibleMessageKeys += messageKey
        if (message.role == MessageRole.ASSISTANT && !message.isContextCheckpoint) message.parts.forEach { part ->
            if (part is UIMessagePart.Tool && part.hasExecutionCheckpoint())
                checkpoints[RunningToolCallKey(messageKey, part.toolCallId)] = part
        }
    }
    val freshClientKey = if (loading) checkpoints.keys.lastOrNull {
        it.messageKey in freshEligibleMessageKeys && it !in previous.checkpointKeys
    }
    else null
    val activeClientKey = if (loading) freshClientKey ?: previous.activeClientKey?.takeIf {
        sameConversation && previous.wasLoading && it in checkpoints && it.messageKey in selectedMessageKeys
    }
    else null
    val activeClient = checkpoints[activeClientKey]
    val clientObservedAt = activeClientKey?.let {
        if (freshClientKey != null) observedAtMillis else previous.clientObservedAtMillis ?: observedAtMillis
    }
    // Retain the latest invocation across every client tool before applying the command scope.
    // A newer browser/MCP/etc. checkpoint must suppress an older completed shell sentinel.
    val presentation = activeClient?.takeIf { it.isForegroundCommandTool() }?.let { tool ->
        RunningToolPresentation(
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            command = runningToolCommand(tool.toolName, tool.inputAsJson()),
            // A recovery checkpoint is control data, never stdout or a live output tail.
            // The current client runtimes publish actual results only after completion.
            latestOutputLine = null,
            observedAtMillis = clientObservedAt!!,
            // Foreground client commands execute sequentially; recovery sentinels and provider
            // tools do not prove parallel command processes.
            otherActiveCount = 0,
        )
    }

    return RunningToolObservation(
        conversationId = conversation.id,
        nodeIds = nodeIds,
        nodeMessageIds = nodeMessageIds,
        selectedMessageKeys = selectedMessageKeys,
        checkpointKeys = checkpoints.keys.toSet(),
        activeClientKey = activeClientKey,
        clientObservedAtMillis = clientObservedAt,
        wasLoading = loading,
        presentation = presentation,
    )
}

private val foregroundCommandTools = setOf(
    "root_exec", "termux_run_command", "ssh_exec", "ssh_exec_saved", "workspace_shell",
)

private fun UIMessagePart.Tool.isForegroundCommandTool(): Boolean {
    if (toolName !in foregroundCommandTools) return false
    if (toolName == "termux_run_command" || toolName == "ssh_exec" || toolName == "ssh_exec_saved") {
        val arguments = inputAsJson() as? JsonObject
        fun enabled(key: String) = (arguments?.get(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        if (enabled("background")) return false
        if (toolName == "termux_run_command" && enabled("interactive")) return false
    }
    return true
}

private fun runningToolCommand(toolName: String, input: JsonElement?): String {
    val arguments = input as? JsonObject
    fun string(key: String) = (arguments?.get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    val command = when (toolName) {
        "termux_run_command" -> {
            string("command") ?: string("executable")?.let { executable ->
                val argv = (arguments?.get("arguments") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.orEmpty()
                (listOf(executable) + argv).joinToString(" ")
            }
        }
        else -> string("command")
    }
    return command?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: when (toolName) {
        "root_exec" -> "Команда root"
        "termux_run_command" -> "Команда Termux"
        "ssh_exec", "ssh_exec_saved" -> "Команда SSH"
        "workspace_shell" -> "Команда в рабочей среде"
        else -> toolName
    }
}

internal fun runningToolElapsed(observedAtMillis: Long, nowMillis: Long): String {
    val seconds = (nowMillis - observedAtMillis).coerceAtLeast(0) / 1_000
    return if (seconds < 60) "$seconds с" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
