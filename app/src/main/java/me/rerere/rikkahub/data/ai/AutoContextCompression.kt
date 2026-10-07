package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import kotlin.uuid.Uuid

internal data class AutoCompressionPlan(val sourceEndMessageId: Uuid, val targetTokens: Int, val cutIndex: Int)

internal object AutoContextCompression {
    private const val KEEP_RECENT = 8

    /** Stored checkpoint objects retain their id/time. No per-request synthetic summary is created. */
    fun activeMessages(messages: List<UIMessage>, limit: Int = 0): List<UIMessage> {
        val checkpoint = messages.indexOfLast { it.isContextCheckpoint }
        if (checkpoint < 0) return messages.limitContext(limit)
        return messages.subList(0, checkpoint).filter { it.role == MessageRole.SYSTEM } + messages.limitContext(limit)
    }

    // Conservative text estimate; the setting is an explicit trigger, not an advertised model capacity.
    fun estimateTokens(messages: List<UIMessage>): Long = messages.sumOf { message ->
        16L + message.parts.sumOf(::estimatePart)
    }
    private fun estimatePart(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Tool -> 16L + part.input.length / 2 + part.output.sumOf(::estimatePart)
        is UIMessagePart.Image -> 1_024L
        else -> renderPart(part).length.toLong() / 2
    }

    /** Unlike summaryAsText, includes complete tool arguments and results, without a 2000-character cut. */
    fun summaryText(messages: List<UIMessage>): String = messages.joinToString("\n\n") { message ->
        "[${message.role}]: " + message.parts.joinToString("\n", transform = ::renderPart)
    }
    private fun renderPart(part: UIMessagePart): String = when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Tool -> "Tool ${part.toolName} (${part.toolCallId}) input: ${part.input}\nResult:\n${part.output.joinToString("\n", transform = ::renderPart)}"
        is UIMessagePart.ToolCall -> "Tool ${part.toolName} (${part.toolCallId}) input: ${part.arguments}"
        is UIMessagePart.ToolResult -> "Tool ${part.toolName} (${part.toolCallId}) result: ${part.content}"
        is UIMessagePart.ServerTool -> "Server tool ${part.toolName}: ${part.input}\nResult: ${part.output}"
        is UIMessagePart.Image -> if (part.url.startsWith("data:")) "[Embedded image]" else "[Image: ${part.url}]"
        is UIMessagePart.Document -> "[Document: ${part.fileName}, ${part.url}]"
        is UIMessagePart.Audio -> "[Audio: ${part.url}]"
        is UIMessagePart.Video -> "[Video: ${part.url}]"
        is UIMessagePart.Reasoning -> part.reasoning
        else -> ""
    }

    fun plan(messages: List<UIMessage>, assistant: Assistant): AutoCompressionPlan? {
        if (!assistant.autoCompressContext) return null
        val active = activeMessages(messages)
        val threshold = assistant.autoCompressionTokenThreshold.coerceIn(2_048, 2_000_000)
        val systemTokens = assistant.systemPrompt.length.toLong() / 2
        if (estimateTokens(active) + systemTokens < threshold || active.size <= KEEP_RECENT) return null
        if (!canCompress(active)) return null
        val cut = safeCutIndex(active, active.size - KEEP_RECENT)
        val checkpoint = active.indexOfLast { it.isContextCheckpoint }.coerceAtLeast(0)
        if (cut <= checkpoint || active.subList(checkpoint, cut).none { !it.isContextCheckpoint && it.role != MessageRole.SYSTEM }) return null
        val target = (threshold / 8).coerceIn(256, 2_000)
        val systems = active.take(cut).filter { it.role == MessageRole.SYSTEM }
        // Never repeatedly rebuild a cache prefix when the required recent tail itself is too large.
        if (estimateTokens(systems + active.drop(cut)) + target + systemTokens > threshold * 60L / 100) return null
        return AutoCompressionPlan(active[cut - 1].id, target, cut)
    }

    fun canCompress(messages: List<UIMessage>): Boolean {
        if (messages.any { message -> message.parts.any { part ->
            (part is UIMessagePart.Tool && !part.isExecuted) || (part is UIMessagePart.ServerTool && !part.isFinished)
        } }) return false
        val calls = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.ToolCall>().map { it.toolCallId }.toSet()
        val results = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.ToolResult>().map { it.toolCallId }.toSet()
        return calls == results
    }

    fun safeCutIndex(messages: List<UIMessage>, proposed: Int): Int {
        var cut = proposed.coerceIn(0, messages.size)
        val calls = mutableMapOf<String, Int>()
        messages.forEachIndexed { index, message -> message.parts.filterIsInstance<UIMessagePart.ToolCall>().forEach { calls[it.toolCallId] = index } }
        while (true) {
            val crossing = messages.drop(cut).flatMap { it.parts }.filterIsInstance<UIMessagePart.ToolResult>()
                .mapNotNull { calls[it.toolCallId] }.filter { it < cut }.minOrNull()
            if (crossing == null) return cut
            cut = crossing
        }
    }

    /** Tail edits/branch changes/deletions keep earlier checkpoints; edits to their source invalidate them. */
    fun retainCheckpoints(before: List<MessageNode>, after: List<MessageNode>): List<MessageNode> {
        val common = minOf(before.size, after.size)
        val changed = (0 until common).firstOrNull { before[it].id != after[it].id || before[it].currentMessage != after[it].currentMessage }
            ?: common
        return after.filterIndexed { index, node -> !node.currentMessage.isContextCheckpoint || index <= changed }
    }

    /** One lookup map per chunk avoids an O(stored nodes × generated messages) nested scan. */
    fun mergeGenerated(conversation: Conversation, messages: List<UIMessage>, regenerationNodeId: Uuid? = null): Conversation {
        val original = conversation.messageNodes
        val nodes = original.toMutableList()
        val locations = HashMap<Uuid, Pair<Int, Int>>()
        nodes.forEachIndexed { i, node -> node.messages.forEachIndexed { j, stored -> locations.putIfAbsent(stored.id, i to j) } }
        var insertion = if (regenerationNodeId == null) nodes.size else nodes.indexOfFirst { it.id == regenerationNodeId }
            .also { check(it >= 0) { "Regeneration target was removed" } }
        var changed = Int.MAX_VALUE
        messages.forEach { message ->
            val location = locations[message.id]
            if (location != null) {
                val (i, j) = location
                val node = nodes[i]
                val replacement = message.copy(modelSnapshot = message.modelSnapshot ?: node.messages[j].modelSnapshot)
                if (node.messages[j] != replacement) {
                    nodes[i] = node.copy(messages = node.messages.toMutableList().apply { this[j] = replacement })
                    changed = minOf(changed, i)
                }
                if (regenerationNodeId != null && i >= insertion) insertion = i + 1
            } else if (!message.isContextCheckpoint) {
                if (insertion < nodes.size) {
                    val node = nodes[insertion]
                    nodes[insertion] = node.copy(messages = node.messages + message, selectIndex = node.messages.size)
                    locations[message.id] = insertion to node.messages.size
                } else {
                    nodes += message.toMessageNode()
                    locations[message.id] = nodes.lastIndex to 0
                }
                changed = minOf(changed, insertion)
                insertion++
            }
        }
        val lastCheckpoint = original.indexOfLast { it.currentMessage.isContextCheckpoint }
        return conversation.copy(messageNodes = if (changed < lastCheckpoint) retainCheckpoints(original, nodes) else nodes)
    }
}
