package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/** A stable Markdown subtree, measured at its real width and clipped to a scaled line viewport. */
@Composable
internal fun CollapsibleUserMessageText(
    messageKey: String,
    options: UserTextDisplayOptions,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var naturalHeight by remember(messageKey) { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer(cacheSize = 1)
    val lineMetrics = textMeasurer.measure("Ag", style = LocalTextStyle.current, maxLines = 1)
    val lineHeightPx = lineMetrics.getLineBottom(0)
    val viewport = presentUserTextViewport(naturalHeight, lineHeightPx, options.visibleLines, options.enabled, expanded)
    val collapsedDescription = stringResource(R.string.chat_message_collapsed)
    val expandedDescription = stringResource(R.string.chat_message_expanded)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Layout(
            modifier = Modifier
                .testTag("chat_message_user_collapse")
                .semantics { stateDescription = if (options.enabled && !expanded && viewport.overflowing) collapsedDescription else expandedDescription }
                .animateContentSize(tween(200))
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (viewport.fadeBottom) {
                        drawRect(brush = Brush.verticalGradient(0f to Color.White, .72f to Color.White,
                            1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    }
                },
            content = {
                Box(Modifier.onSizeChanged { naturalHeight = it.height }) { content() }
            },
        ) { measurables, constraints ->
            val child = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val measured = presentUserTextViewport(child.height, lineHeightPx, options.visibleLines, options.enabled, expanded)
            layout(constraints.constrainWidth(child.width), constraints.constrainHeight(measured.viewportHeight)) {
                child.placeRelative(0, 0)
            }
        }
        if (options.enabled && viewport.overflowing) {
            Text(
                text = stringResource(if (expanded) R.string.chat_message_collapse else R.string.chat_message_expand),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .testTag("chat_message_user_collapse_toggle")
                    .semantics { stateDescription = if (expanded) expandedDescription else collapsedDescription }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(4.dp),
            )
        }
    }
}
