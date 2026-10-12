package me.rerere.rikkahub.ui.components.ai

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.rikkahub.data.model.Conversation

/** Compact presentation of a fresh invocation; stopping uses the existing generation callback. */
@Composable
fun ChatRunningToolStrip(
    conversation: Conversation,
    loading: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatRunningToolStrip(
        observation = rememberRunningToolObservation(conversation, loading),
        onStop = onStop,
        modifier = modifier,
    )
}

/** Shared by the composer strip and message timeline on ChatPage. */
@Composable
internal fun rememberRunningToolObservation(
    conversation: Conversation,
    loading: Boolean,
): RunningToolObservation {
    var observation by remember(conversation.id) { mutableStateOf(RunningToolObservation()) }
    val currentObservation = remember(observation, conversation, loading) {
        observeRunningTools(observation, conversation, loading, SystemClock.elapsedRealtime())
    }
    LaunchedEffect(currentObservation) { observation = currentObservation }
    return currentObservation
}

@Composable
internal fun ChatRunningToolStrip(
    observation: RunningToolObservation,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Project the current snapshot before the effect records it, so completion or a branch
    // switch cannot render the previous invocation for a frame.
    val presentation = observation.presentation ?: return
    var nowMillis by remember(presentation.toolCallId, presentation.observedAtMillis) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(presentation.toolCallId, presentation.observedAtMillis) {
        while (true) {
            nowMillis = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    RunningToolStripContent(
        presentation = presentation,
        elapsedLabel = runningToolElapsed(presentation.observedAtMillis, nowMillis),
        onStop = onStop,
        modifier = modifier,
    )
}

@Composable
internal fun RunningToolStripContent(
    presentation: RunningToolPresentation,
    elapsedLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag("running-tool-bar"),
        color = colors.surfaceContainerHigh.copy(alpha = 1f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RunningToolTerminalIcon()
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = presentation.command,
                    modifier = Modifier.testTag("running-tool-bar-command"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface.copy(alpha = 1f),
                )
                Text(
                    text = presentation.outputLabel,
                    modifier = Modifier.testTag("running-tool-bar-tail"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant.copy(alpha = 1f),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (presentation.otherActiveCount > 0) "$elapsedLabel · +${presentation.otherActiveCount}" else elapsedLabel,
                modifier = Modifier.semantics { contentDescription = "С момента появления вызова: $elapsedLabel" },
                maxLines = 1,
                style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = colors.primary.copy(alpha = 1f),
            )
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp).testTag("running-tool-bar-stop")) {
                    Icon(
                        imageVector = HugeIcons.StopCircle,
                        contentDescription = "Остановить генерацию",
                        modifier = Modifier.size(17.dp),
                        tint = colors.error.copy(alpha = 1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningToolTerminalIcon() {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "running-tool-dot")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "running-tool-dot-alpha",
    )
    Box(Modifier.size(24.dp)) {
        Box(
            modifier = Modifier.size(24.dp).background(colors.primaryContainer.copy(alpha = 1f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.ComputerTerminal01,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = colors.primary.copy(alpha = 1f),
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(7.dp).alpha(alpha)
                .background(colors.primary.copy(alpha = 1f), CircleShape)
                .border(1.dp, colors.surfaceContainerHigh.copy(alpha = 1f), CircleShape)
        )
    }
}
