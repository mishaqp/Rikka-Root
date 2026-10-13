package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

internal data class ChatListEdgeFadeGeometry(
    val topStart: Float,
    val topEnd: Float,
    val bottomStart: Float,
    val bottomEnd: Float,
) {
    fun alphaAt(y: Float): Float =
        ((y - topStart) / (topEnd - topStart)).coerceIn(0f, 1f) *
            ((bottomEnd - y) / (bottomEnd - bottomStart)).coerceIn(0f, 1f)
}

/** Insets reserve content space, while the viewport itself continues behind both overlays. */
internal fun chatListEdgeFadeGeometry(
    height: Float,
    topInset: Float,
    bottomInset: Float,
    topTail: Float,
    bottomTail: Float,
): ChatListEdgeFadeGeometry {
    // Moru's bottom overlay ends at the viewport bottom, including the composer.
    // Keep its fade inside the measured overlay so the last visible reply/actions
    // retain full contrast, even when the compact composer is shorter than 180dp.
    val overlayHeight = bottomInset.coerceIn(0f, height.coerceAtLeast(0f))
    val bottomFadeHeight = minOf(bottomTail.coerceAtLeast(1f), overlayHeight.coerceAtLeast(1f))
    return ChatListEdgeFadeGeometry(
        topStart = topInset.coerceIn(0f, height.coerceAtLeast(0f)),
        topEnd = topInset.coerceIn(0f, height.coerceAtLeast(0f)) + topTail.coerceAtLeast(1f),
        bottomStart = height - bottomFadeHeight,
        bottomEnd = height,
    )
}

/** DstIn removes only the list's pixels; the wallpaper and controls keep their original colours. */
internal fun Modifier.chatListEdgeFade(topInset: Dp, bottomInset: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val geometry = chatListEdgeFadeGeometry(size.height, topInset.toPx(), bottomInset.toPx(), 24 * density, 180 * density)
            val top = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White),
                startY = geometry.topStart, endY = geometry.topEnd,
            )
            val bottom = Brush.verticalGradient(
                colors = listOf(Color.White, Color.Transparent),
                startY = geometry.bottomStart, endY = geometry.bottomEnd,
            )
            onDrawWithContent {
                drawContent()
                drawRect(top, blendMode = BlendMode.DstIn)
                drawRect(bottom, blendMode = BlendMode.DstIn)
            }
        }
