package me.rerere.rikkahub.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/** Checkpoint before launch: an interrupted approved call must not regain permission on resume. */
internal suspend fun executeRootToolOnce(
    tool: UIMessagePart.Tool,
    persistStarted: suspend (UIMessagePart.Tool) -> Unit,
    execute: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> {
    check(tool.toolName == "root_exec" && tool.approvalState == ToolApprovalState.Approved && !tool.isExecuted) {
        "Root execution requires a fresh approved call."
    }
    val started = tool.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
        put("error", "root_execution_indeterminate")
        put("reason", "This approved root command was checkpointed before launch. If execution was interrupted, its effects are unknown. It will not run again automatically. Review the device before requesting a new command and approval.")
    }.toString())))
    // The checkpoint emission must await the collector's ordered durable-write acknowledgment.
    persistStarted(started)
    currentCoroutineContext().ensureActive()
    return execute()
}

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
