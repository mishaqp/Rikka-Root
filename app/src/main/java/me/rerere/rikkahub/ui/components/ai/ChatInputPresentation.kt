package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.BackgroundEffectType

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

internal data class ComposerSurfacePresentation(val opacity: Float, val blur: Boolean, val glass: Boolean, val keepScreenOn: Boolean)

internal fun presentComposerSurface(settings: DisplaySetting, dark: Boolean, loading: Boolean): ComposerSurfacePresentation {
    val opacity = (if (dark) settings.chatInputBackgroundOpacityDark else settings.chatInputBackgroundOpacityLight)
        .coerceIn(0f, 1f)
    val frosted = settings.glassTheme || settings.enableBlurEffect
    val glass = settings.enableBlurEffect && settings.backgroundEffectType == BackgroundEffectType.GLASS
    return ComposerSurfacePresentation(
        opacity = opacity * if (frosted) .66f else 1f,
        blur = frosted && !settings.glassEconomy && !glass,
        glass = glass && !settings.glassEconomy,
        keepScreenOn = loading && settings.keepScreenOnDuringGeneration,
    )
}
