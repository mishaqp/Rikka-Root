package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.data.datastore.ChatBubbleStyleOverrides
import me.rerere.rikkahub.data.datastore.ChatMessageBackgroundStyle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticAction
import me.rerere.rikkahub.ui.hooks.rememberAppHapticFeedback
import me.rerere.rikkahub.ui.modifier.chatBackdropBlur
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** A background-only capture avoids recursively blurring the bubble itself. */
val LocalChatMessageHazeState = staticCompositionLocalOf<HazeState?> { null }

data class ResolvedMessageBubbleStyle(
    val background: Color,
    val border: Color,
    val text: Color,
    val borderWidth: Float,
    val radius: Float,
    val blurSigma: Float,
)

fun resolveMessageBubbleStyle(
    surface: MessageTextSurface,
    colors: ColorScheme,
    dark: Boolean,
    settings: DisplaySetting,
    opacity: Float = settings.bubbleOpacity,
): ResolvedMessageBubbleStyle {
    val style = settings.chatMessageBackgroundStyle
    if (style == ChatMessageBackgroundStyle.DEFAULT || surface == MessageTextSurface.NONE) {
        return ResolvedMessageBubbleStyle(messageSurfaceColor(surface, colors, dark, opacity),
            Color.Transparent, Color.Unspecified, 0f, 16f, 0f)
    }
    val overrides = if (surface == MessageTextSurface.USER) settings.userChatBubbleStyleOverrides
        else settings.assistantChatBubbleStyleOverrides
    return resolveCustomMessageBubbleStyle(colors, dark, style, overrides, opacity, settings.glassEconomy)
}

private fun resolveCustomMessageBubbleStyle(
    colors: ColorScheme,
    dark: Boolean,
    style: ChatMessageBackgroundStyle,
    overrides: ChatBubbleStyleOverrides,
    opacity: Float,
    economy: Boolean,
): ResolvedMessageBubbleStyle {
    val frosted = style == ChatMessageBackgroundStyle.FROSTED
    val background = if (dark) overrides.backgroundArgbDark else overrides.backgroundArgbLight
    val border = if (dark) overrides.borderArgbDark else overrides.borderArgbLight
    val text = if (dark) overrides.textArgbDark else overrides.textArgbLight
    val alpha = (if (frosted) overrides.frostedOpacity ?: .66f else overrides.solidOpacity ?: 1f).coerceIn(0f, 1f)
    return ResolvedMessageBubbleStyle(
        background = (background?.let(::Color) ?: colors.surfaceContainerHigh)
            .copy(alpha = alpha * opacity.coerceIn(0f, 1f)),
        border = (border?.let(::Color) ?: colors.outlineVariant)
            .copy(alpha = (overrides.borderOpacity ?: if (frosted) .14f else .16f).coerceIn(0f, 1f)),
        text = text?.let(::Color) ?: colors.onSurface,
        borderWidth = (overrides.borderWidth ?: .8f).coerceIn(0f, 3f),
        radius = (overrides.cornerRadius ?: 16f).coerceIn(0f, 28f),
        blurSigma = if (frosted && !economy) (overrides.blurSigma ?: 14f).coerceIn(0f, 30f) else 0f,
    )
}

/** Moru's role tint is opaque before applying the user's explicit opacity preference. */
@Composable
internal fun ChatMessageSurface(
    surface: MessageTextSurface,
    opacity: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (surface == MessageTextSurface.NONE) {
        Box(modifier = modifier) { content() }
        return
    }
    val style = resolveMessageBubbleStyle(surface, MaterialTheme.colorScheme, LocalDarkMode.current,
        LocalSettings.current.displaySetting, opacity)
    val haptics = rememberAppHapticFeedback(HapticAction.CARD)
    val bubbleModifier = modifier.testTag(if (surface == MessageTextSurface.USER)
        "chat_message_user_bubble" else "chat_message_assistant_bubble")
    val shape = RoundedCornerShape(style.radius.dp)
    val paintedModifier = bubbleModifier.clip(shape).chatBackdropBlur(LocalChatMessageHazeState.current,
        style.blurSigma > 0f, style.blurSigma)
    val border = if (style.borderWidth > 0f) BorderStroke(style.borderWidth.dp, style.border) else null
    val contentColor = if (style.text == Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.text
    val body = @Composable {
        // MarkdownBlock contributes another 4dp on each horizontal edge.
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) { content() }
    }
    if (onClick == null) {
        Surface(modifier = paintedModifier, shape = shape, color = style.background,
            contentColor = contentColor, border = border) { body() }
    } else {
        Surface(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        }, modifier = paintedModifier, shape = shape, color = style.background,
            contentColor = contentColor, border = border) { body() }
    }
}

internal fun messageSurfaceColor(
    surface: MessageTextSurface,
    colors: ColorScheme,
    dark: Boolean,
    opacity: Float,
): Color {
    val baseColor = when (surface) {
        MessageTextSurface.USER -> colors.primary.copy(alpha = if (dark) .15f else .08f)
            .compositeOver(colors.background.copy(alpha = 1f))
        MessageTextSurface.ASSISTANT -> colors.surfaceContainerHigh.copy(alpha = 1f)
        MessageTextSurface.NONE -> return Color.Transparent
    }
    return baseColor.copy(alpha = opacity.coerceIn(0f, 1f))
}
