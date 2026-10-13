package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.ChatService.ApprovalScope
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.pages.chat.ChatPendingToolPresentation
import kotlin.math.roundToInt
import kotlin.time.DurationUnit

/** Draws one timeline card for the ordered ThinkingBlock handed over by groupMessageParts(). */
@Composable
internal fun ChatToolTimeline(
    block: MessagePartBlock.ThinkingBlock,
    messageKey: String,
    blockOrdinal: Int,
    options: ToolDisplayOptions,
    fontSizeRatio: Float,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    pendingTools: List<ChatPendingToolPresentation>,
    activeToolCallId: String? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: ApprovalScope, toolName: String) -> Job)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val pendingIds = pendingTools.mapTo(mutableSetOf()) { it.tool.toolCallId }
    val presentation = remember(block, options, loading, messageKey, blockOrdinal, activeToolCallId, pendingIds) {
        presentTimeline(block.steps, options, loading, messageKey, blockOrdinal, activeToolCallId, pendingIds)
    }
    if (presentation.steps.isEmpty()) return
    val summary = if (presentation.canFoldToSummary) {
        val duration = presentation.duration
        if (duration == null) pluralStringResource(R.plurals.chat_timeline_processed_steps,
            presentation.steps.size, presentation.steps.size)
        else {
            val seconds = duration.toDouble(DurationUnit.SECONDS)
            val value = if (seconds < 10) String.format(java.util.Locale.ROOT, "%.1f", seconds)
                else seconds.roundToInt().toString()
            stringResource(R.string.chat_timeline_processed_seconds, value)
        }
    } else null

    Box(Modifier.testTag("chat_message_tool_card")) {
    ChainOfThought(
        modifier = Modifier.animateContentSize(animationSpec = tween(300)),
        steps = presentation.steps,
        stateKey = "$messageKey:$blockOrdinal",
        timelineStyle = true,
        fontSizeRatio = fontSizeRatio,
        collapsedVisibleIndices = presentation.collapsedVisibleIndices,
        summaryLabel = summary,
        autoCollapseSummary = options.collapseThinkingSteps ?: options.autoCollapseSteps,
        collapsedAdaptiveWidth = block.steps.all { it is ThinkingStep.ReasoningStep },
        cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) { item ->
        key(item.key) {
            when (val step = item.step) {
                is ThinkingStep.ReasoningStep -> ChatMessageReasoningStep(
                    reasoning = step.reasoning,
                    model = model,
                    assistant = assistant,
                    collapsedAdaptiveWidth = block.steps.all { it is ThinkingStep.ReasoningStep },
                )
                is ThinkingStep.ToolStep -> {
                    val pending = pendingTools.firstOrNull { it.tool == step.tool }
                    key(pending?.renderKey ?: item.key) {
                        ChatMessageToolStep(
                            tool = step.tool,
                            loading = item.status == TimelineStatus.Running ||
                                (loading && !step.tool.isPending && step.tool.output.isEmpty()),
                            status = item.status,
                            options = options,
                            fontSizeRatio = fontSizeRatio,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                        )
                    }
                }
                is ThinkingStep.ServerToolStep -> ChatMessageServerToolStep(step.tool, item.status, fontSizeRatio)
            }
        }
    }
    }
}
