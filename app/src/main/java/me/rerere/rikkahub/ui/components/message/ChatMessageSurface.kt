package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalDarkMode

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
    val color = messageSurfaceColor(surface, MaterialTheme.colorScheme, LocalDarkMode.current, opacity)
    val bubbleModifier = modifier.testTag(if (surface == MessageTextSurface.USER)
        "chat_message_user_bubble" else "chat_message_assistant_bubble")
    val shape = RoundedCornerShape(16.dp)
    val body = @Composable {
        // MarkdownBlock contributes another 4dp on each horizontal edge.
        Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) { content() }
    }
    if (onClick == null) {
        Surface(modifier = bubbleModifier, shape = shape, color = color) { body() }
    } else {
        Surface(onClick = onClick, modifier = bubbleModifier, shape = shape, color = color) { body() }
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
