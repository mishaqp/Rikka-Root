package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

/** Generic clock boundary for deterministic UI rendering and screenshots. */
internal val LocalChatTimelineClock = staticCompositionLocalOf<Clock> { Clock.System }

internal fun reasoningElapsed(reasoning: UIMessagePart.Reasoning, clock: Clock = Clock.System): Duration =
    ((reasoning.finishedAt ?: clock.now()) - reasoning.createdAt).coerceAtLeast(Duration.ZERO)

internal fun nextReasoningCardState(
    current: ReasoningCardState,
    loading: Boolean,
    showThinkingContent: Boolean,
    autoCloseThinking: Boolean,
): ReasoningCardState = if (loading) {
    when {
        current == ReasoningCardState.Preview && !showThinkingContent -> ReasoningCardState.Collapsed
        current == ReasoningCardState.Collapsed && showThinkingContent -> ReasoningCardState.Preview
        else -> current
    }
} else {
    if (current.expanded) {
        if (autoCloseThinking) ReasoningCardState.Collapsed else ReasoningCardState.Expanded
    } else current
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean, showThinkingContent: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded
            else if (showThinkingContent) ReasoningCardState.Preview else ReasoningCardState.Collapsed
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

@Composable
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val clock = LocalChatTimelineClock.current
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = reasoningElapsed(reasoning, clock)
        )
    }

    LaunchedEffect(reasoning.reasoning, loading, settings.displaySetting.showThinkingContent,
        settings.displaySetting.autoCloseThinking) {
        state.expandState = nextReasoningCardState(
            state.expandState, loading,
            settings.displaySetting.showThinkingContent,
            settings.displaySetting.autoCloseThinking,
        )
    }

    LaunchedEffect(loading, state.expandState) {
        if (loading && state.expandState == ReasoningCardState.Preview) {
            snapshotFlow { scrollState.maxValue }.collectLatest { scrollState.animateScrollTo(it) }
        }
    }

    LaunchedEffect(reasoning.createdAt, reasoning.finishedAt, clock) {
        if (loading) {
            while (isActive) {
                state.duration = reasoningElapsed(reasoning, clock)
                delay(100)
            }
        } else {
            state.duration = reasoningElapsed(reasoning, clock)
        }
    }

    return state to loading
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    loading: Boolean,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio
    val reasoningTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily,
        fontSize = (12.5f * fontSizeRatio).sp,
        lineHeight = (16.5f * fontSizeRatio).sp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .then(if (scrollState.maxValue > 0) Modifier.graphicsLayer { alpha = 0.99f }
                            .drawWithCache {
                            val topFade = 12.dp.toPx()
                            val bottomFade = 28.dp.toPx()
                            val height = size.height.coerceAtLeast(1f)
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (topFade / height).coerceIn(0f, 1f) to Color.Black,
                                    (1 - bottomFade / height).coerceIn(0f, 1f) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            } else Modifier)
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                        .testTag("chat_reasoning_preview")
                } else {
                    contentModifier.testTag("chat_reasoning_expanded")
                }
            }
    ) {
        val reasoningContent = @Composable {
            val text = reasoning.reasoning.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
            )
            MessageTextContent(text = text,
                markdown = LocalSettings.current.displaySetting.enableReasoningMarkdown,
                style = reasoningTextStyle, modifier = Modifier.fillMaxSize())
        }
        // 流式生成期间不启用 SelectionContainer，避免 selectable 列表并发修改导致的
        // ConcurrentModificationException（详见 ChatMessage.kt 文本块同样处理）。
        if (loading) {
            reasoningContent()
        } else {
            SelectionContainer {
                reasoningContent()
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val (state, loading) = rememberReasoningState(reasoning)
    val showThinkingContent = LocalSettings.current.displaySetting.showThinkingContent
    val fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio
    val chatFontFamily = LocalTextStyle.current.fontFamily

    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, loading, showThinkingContent) },
        testTag = "chat_reasoning_step",
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            Text(
                text = stringResource(R.string.chat_reasoning_files_thinking),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily,
                    fontSize = (13f * fontSizeRatio).sp),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading).testTag("chat_reasoning_label"),
            )
        },
        extra = {
            Text(
                text = stringResource(R.string.chat_reasoning_files_seconds,
                    state.duration.toDouble(DurationUnit.SECONDS).toFloat()),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily,
                    fontSize = (13f * fontSizeRatio).sp),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
            )
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                expandState = state.expandState,
                scrollState = state.scrollState,
                loading = loading,
            )
        },
    )
}
