package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Hash
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import kotlin.math.roundToInt

internal enum class TokenCountKind { Prompt, Cached, Completion, Total }

internal sealed interface TokenUsageStatistic {
    data class Count(val kind: TokenCountKind, val tokens: Int) : TokenUsageStatistic
    data class Speed(val tokensPerSecond: Double) : TokenUsageStatistic
    data class Duration(val seconds: Double) : TokenUsageStatistic
}

/** Provider omissions remain omissions; timing is useful only for a positive interval. */
internal fun tokenUsageStatistics(
    usage: TokenUsage,
    durationMillis: Long? = null,
): List<TokenUsageStatistic> = buildList {
    if (usage.promptTokens > 0) {
        add(TokenUsageStatistic.Count(TokenCountKind.Prompt, usage.promptTokens))
        if (usage.cachedTokens > 0) {
            add(TokenUsageStatistic.Count(TokenCountKind.Cached, usage.cachedTokens))
        }
    }
    if (usage.completionTokens > 0) {
        add(TokenUsageStatistic.Count(TokenCountKind.Completion, usage.completionTokens))
    }
    if (isEmpty()) {
        add(TokenUsageStatistic.Count(TokenCountKind.Total, usage.totalTokens))
    }
    if (durationMillis != null && durationMillis > 0) {
        val seconds = durationMillis / 1_000.0
        if (usage.completionTokens > 0) {
            add(TokenUsageStatistic.Speed(usage.completionTokens / seconds))
        }
        add(TokenUsageStatistic.Duration(seconds))
    }
}

internal fun formatTokenStatisticDecimal(value: Double): String = BigDecimal(value)
    .setScale(1, RoundingMode.HALF_UP)
    .toPlainString()

/** Matches Moru's compact counts, including its binary-double rounding at one decimal. */
internal fun compactTokenCount(tokens: Int): String {
    return when {
        tokens < 1_000 -> tokens.toString()
        tokens < 1_000_000 -> {
            val thousands = tokens / 1_000.0
            "${if (thousands < 100) formatTokenStatisticDecimal(thousands).removeSuffix(".0") else thousands.roundToInt()}k"
        }
        else -> "${formatTokenStatisticDecimal(tokens / 1_000_000.0).removeSuffix(".0")}M"
    }
}

@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
) {
    val usage = message.usage
    if (!LocalSettings.current.displaySetting.showTokenUsage || usage == null) return

    val durationMillis = message.finishedAt?.let { finishedAt ->
        Duration.between(message.createdAt.toJavaLocalDateTime(), finishedAt.toJavaLocalDateTime()).toMillis()
    }
    val statistics = tokenUsageStatistics(usage, durationMillis)
    val descriptions = statistics.map { tokenUsageDescription(it) }

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(
        color = color,
        fontSize = 11.5.sp,
        fontFeatureSettings = "tnum",
    )) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier
                    .padding(start = 4.dp, top = 6.dp)
                    .testTag("chat_message_tokens")
                    .clearAndSetSemantics { contentDescription = descriptions.joinToString(", ") },
            ) {
                statistics.forEach { statistic ->
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = when (statistic) {
                                    is TokenUsageStatistic.Count -> when (statistic.kind) {
                                        TokenCountKind.Prompt -> HugeIcons.Upload02
                                        TokenCountKind.Cached -> HugeIcons.Database02
                                        TokenCountKind.Completion -> HugeIcons.Download04
                                        TokenCountKind.Total -> HugeIcons.Hash
                                    }
                                    is TokenUsageStatistic.Speed -> HugeIcons.Zap
                                    is TokenUsageStatistic.Duration -> HugeIcons.Clock02
                                },
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        content = { Text(tokenUsageValue(statistic)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun tokenUsageValue(statistic: TokenUsageStatistic): String = when (statistic) {
    is TokenUsageStatistic.Count -> compactTokenCount(statistic.tokens)
    is TokenUsageStatistic.Speed -> stringResource(
        R.string.chat_message_token_speed_value,
        formatTokenStatisticDecimal(statistic.tokensPerSecond),
    )
    is TokenUsageStatistic.Duration -> stringResource(
        R.string.chat_message_token_duration_value,
        formatTokenStatisticDecimal(statistic.seconds),
    )
}

@Composable
private fun tokenUsageDescription(statistic: TokenUsageStatistic): String = when (statistic) {
    is TokenUsageStatistic.Count -> stringResource(
        when (statistic.kind) {
            TokenCountKind.Prompt -> R.string.chat_message_token_prompt_description
            TokenCountKind.Cached -> R.string.chat_message_token_cached_description
            TokenCountKind.Completion -> R.string.chat_message_token_completion_description
            TokenCountKind.Total -> R.string.chat_message_token_total_description
        },
        statistic.tokens,
    )
    is TokenUsageStatistic.Speed -> stringResource(R.string.chat_message_token_speed_description, tokenUsageValue(statistic))
    is TokenUsageStatistic.Duration -> stringResource(R.string.chat_message_token_duration_description, tokenUsageValue(statistic))
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        icon()
        content()
    }
}
