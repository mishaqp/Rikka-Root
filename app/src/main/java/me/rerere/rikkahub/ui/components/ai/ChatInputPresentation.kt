package me.rerere.rikkahub.ui.components.ai

/** Presentation only; generation, queueing and editing stay with the existing callbacks. */
internal data class ChatInputPresentation(
    val showStop: Boolean,
    val sendEnabled: Boolean,
)

internal fun chatInputPresentation(loading: Boolean, empty: Boolean): ChatInputPresentation =
    ChatInputPresentation(
        showStop = loading && empty,
        sendEnabled = loading || !empty,
    )

/** The last provider-reported prompt count is known; the model context window is not. */
internal data class ChatContextUsagePresentation(val promptTokens: Int)

internal fun chatContextUsagePresentation(promptTokens: Int?): ChatContextUsagePresentation? =
    promptTokens?.takeIf { it >= 0 }?.let(::ChatContextUsagePresentation)
