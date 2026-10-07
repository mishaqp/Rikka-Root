package me.rerere.rikkahub.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/** Approval provenance is trusted coroutine context, never model-controlled JSON. */
internal class RootInvocation(
    val automatic: Boolean,
    val automaticAllowed: (suspend () -> Boolean)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RootInvocation>
}

/** Checkpoint before launch: an interrupted call must not regain permission on resume. */
internal suspend fun executeRootToolOnce(
    tool: UIMessagePart.Tool,
    persistStarted: suspend (UIMessagePart.Tool) -> Unit,
    automaticAllowed: suspend () -> Boolean = { false },
    execute: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> {
    suspend fun authorised() = tool.approvalState == ToolApprovalState.Approved ||
        (tool.approvalState == ToolApprovalState.Auto && automaticAllowed())
    check(tool.toolName == "root_exec" && !tool.isExecuted && authorised()) {
        "Root execution requires a fresh approved call or current automatic permission."
    }
    val started = tool.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
        put("error", "root_execution_indeterminate")
        put("reason", "This root command was checkpointed before launch. If execution was interrupted, its effects are unknown. It will not run again automatically. Review the device before requesting a new command.")
    }.toString())))
    // The checkpoint emission must await the collector's ordered durable-write acknowledgment.
    persistStarted(started)
    currentCoroutineContext().ensureActive()
    check(authorised()) { "Automatic root permission was revoked before launch. Request a new command with approval." }
    return withContext(RootInvocation(automatic = tool.approvalState == ToolApprovalState.Auto, automaticAllowed)) { execute() }
}

/** Only root uses this path; checkpointed calls remain terminal. */
internal fun canResumeRootTool(tool: UIMessagePart.Tool): Boolean =
    tool.toolName == "root_exec" && !tool.isExecuted && tool.approvalState == ToolApprovalState.Auto

/** A buffered Flow emission alone does not prove that the conversation was saved. */
internal suspend fun awaitRootCheckpoint(emit: suspend (CompletableDeferred<Unit>) -> Unit) {
    val ack = CompletableDeferred<Unit>()
    emit(ack)
    ack.await()
}

internal suspend fun persistRootCheckpoint(ack: CompletableDeferred<Unit>, persist: suspend () -> Unit) {
    try {
        persist()
        ack.complete(Unit)
    } catch (error: Throwable) {
        ack.completeExceptionally(error)
        throw error
    }
}
