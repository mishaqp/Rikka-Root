package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val ChatListBottomFadeHeight = 48.dp

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

/** The bottom fade finishes at the input edge, leaving no list pixels beneath the input. */
internal fun chatListEdgeFadeGeometry(
    height: Float,
    topInset: Float,
    bottomInset: Float,
    topTail: Float,
    bottomTail: Float,
): ChatListEdgeFadeGeometry {
    val bottomEdge = height.coerceAtLeast(0f) - bottomInset.coerceIn(0f, height.coerceAtLeast(0f))
    return ChatListEdgeFadeGeometry(
        topStart = topInset.coerceIn(0f, height.coerceAtLeast(0f)),
        topEnd = topInset.coerceIn(0f, height.coerceAtLeast(0f)) + topTail.coerceAtLeast(1f),
        bottomStart = bottomEdge - bottomTail.coerceAtLeast(1f),
        bottomEnd = bottomEdge,
    )
}

/** DstIn removes only the list's pixels; the wallpaper and controls keep their original colours. */
internal fun Modifier.chatListEdgeFade(topInset: Dp, bottomInset: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val geometry = chatListEdgeFadeGeometry(
                size.height, topInset.toPx(), bottomInset.toPx(), 24 * density, ChatListBottomFadeHeight.toPx(),
            )
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
