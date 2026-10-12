package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.extendColors

/** A ring is meaningful only when the existing registry knows this model's context window. */
@Composable
internal fun ContextUsageRing(usage: ContextUsagePresentation, modifier: Modifier = Modifier) {
    val description = if (usage.windowTokens == null) {
        stringResource(R.string.chat_composer_context_usage, usage.promptTokens)
    } else {
        stringResource(
            R.string.chat_composer_context_usage_known,
            usage.promptTokens,
            usage.windowTokens,
            usage.percentage ?: 0,
        )
    }
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val color = when (usage.level) {
        ContextUsageLevel.Normal -> MaterialTheme.colorScheme.primary
        ContextUsageLevel.Warning -> MaterialTheme.extendColors.orange6
        ContextUsageLevel.Full -> MaterialTheme.colorScheme.error
    }
    val filledFraction by animateFloatAsState(
        targetValue = usage.filledFraction ?: 0f,
        animationSpec = tween(220, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)),
        label = "context_usage_fill",
    )
    LaunchedEffect(tooltipState.isVisible) {
        if (tooltipState.isVisible) {
            delay(4_000)
            tooltipState.dismiss()
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = tooltipState,
        enableUserInput = false,
    ) {
        Box(
            modifier = modifier
                .height(32.dp)
                .widthIn(min = 32.dp)
                .testTag("chat_context_usage")
                .semantics { contentDescription = description }
                .clickable(role = Role.Button) {
                    scope.launch { tooltipState.show() }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (usage.windowTokens == null) {
                Text(
                    text = usage.compactTokens,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                Canvas(Modifier.size(18.dp).testTag("chat_context_usage_ring")) {
                    val stroke = 2.5.dp.toPx()
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val arcOffset = Offset(stroke / 2, stroke / 2)
                    val arcStroke = Stroke(width = stroke, cap = StrokeCap.Round)
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcOffset,
                        size = arcSize,
                        style = arcStroke,
                    )
                    if (filledFraction > 0f) {
                        drawArc(
                            color = color,
                            startAngle = -90f,
                            sweepAngle = 360f * filledFraction,
                            useCenter = false,
                            topLeft = arcOffset,
                            size = arcSize,
                            style = arcStroke,
                        )
                    }
                }
            }
        }
    }
}
