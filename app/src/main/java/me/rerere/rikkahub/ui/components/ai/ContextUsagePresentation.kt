package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.registry.ModelRegistry
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ContextUsageLevel { Normal, Warning, Full }

/** Only provider-reported usage and registry capacity are used; the draft is never estimated. */
internal data class ContextUsagePresentation(
    val promptTokens: Int,
    val windowTokens: Int?,
) {
    val fraction: Double? get() = windowTokens?.let { promptTokens.toDouble() / it }
    val filledFraction: Float? get() = fraction?.coerceIn(0.0, 1.0)?.toFloat()
    val percentage: Int? get() = fraction?.let { (it * 100).roundToInt() }
    val level: ContextUsageLevel get() = when {
        (fraction ?: 0.0) >= 1.0 -> ContextUsageLevel.Full
        (fraction ?: 0.0) >= 0.85 -> ContextUsageLevel.Warning
        else -> ContextUsageLevel.Normal
    }
    val compactTokens: String get() = when {
        promptTokens < 1_000 -> promptTokens.toString()
        promptTokens < 100_000 -> "${decimal(promptTokens / 1_000.0)}K"
        promptTokens < 1_000_000 -> "${(promptTokens / 1_000.0).roundToInt()}K"
        else -> "${decimal(promptTokens / 1_000_000.0)}M"
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
}

internal fun contextUsagePresentation(
    promptTokens: Int?,
    modelId: String? = null,
): ContextUsagePresentation? = promptTokens?.takeIf { it >= 0 }?.let { usage ->
    ContextUsagePresentation(
        promptTokens = usage,
        windowTokens = modelId?.let { ModelRegistry.MODEL_CONTEXT_LENGTH.getData(it) }?.takeIf { it > 0 },
    )
}
