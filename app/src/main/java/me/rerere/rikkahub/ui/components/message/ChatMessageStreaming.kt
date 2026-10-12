package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.LocalMarkdownStreaming
import kotlin.math.PI
import kotlin.math.sin

/** Only incoming glyphs fade inside the rich renderer; existing content keeps full opacity. */
@Composable
internal fun ChatStreamingText(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMarkdownStreaming provides enabled) {
        Box(Modifier.testTag("chat_message_streaming_text")
            .animateContentSize(tween(260))) { content() }
    }
}

@Composable
internal fun ChatWaitingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Chat waiting")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "Waiting dots phase")
    val color = MaterialTheme.colorScheme.primary
    val waitingDescription = stringResource(R.string.chat_message_waiting)
    Canvas(modifier.testTag("chat_message_waiting_dots")
        .semantics {
            contentDescription = waitingDescription
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }
        .size(width = 39.dp, height = 16.dp)) {
        val dotSize = 9.dp.toPx()
        val spacing = 6.dp.toPx()
        repeat(3) { index ->
            val wave = ((sin((phase - index * .22f) * 2 * PI) + 1) / 2).toFloat()
            drawCircle(color.copy(alpha = .45f + .45f * wave), radius = dotSize / 2 * (.85f + .15f * wave),
                center = Offset(index * (dotSize + spacing) + dotSize / 2, size.height / 2))
        }
    }
}
