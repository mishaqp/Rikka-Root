package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val INLINE_CODE_ANNOTATION = "markdown-inline-code"

/** Paint code chrome behind native text ranges, retaining wrap, links and selectable characters. */
@Composable
internal fun MarkdownStyledText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Visible,
    style: TextStyle = LocalTextStyle.current,
) {
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val streaming = LocalMarkdownStreaming.current
    var previousText by remember { mutableStateOf("") }
    var incomingRange by remember { mutableStateOf<IntRange?>(null) }
    val incomingAlpha = remember { Animatable(1f) }
    val fadePaint = remember { Paint() }
    LaunchedEffect(text.text, streaming) {
        incomingRange = if (streaming) appendedMarkdownRange(previousText, text.text) else null
        previousText = text.text
        if (incomingRange != null) {
            incomingAlpha.snapTo(0f)
            incomingAlpha.animateTo(1f, tween(260))
        } else {
            incomingAlpha.snapTo(1f)
        }
    }
    val ranges = remember(text) { text.getStringAnnotations(INLINE_CODE_ANNOTATION, 0, text.length) }
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    val border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    Text(
        text = text,
        inlineContent = inlineContent,
        softWrap = softWrap,
        overflow = overflow,
        style = style,
        onTextLayout = { textLayout = it },
        modifier = modifier.drawWithContent {
            textLayout?.let { layout ->
                val horizontalInset = 6.dp.toPx()
                val verticalInset = 2.dp.toPx()
                val radius = CornerRadius(6.dp.toPx())
                ranges.forEach { range ->
                    if (range.start < range.end && range.end <= layout.layoutInput.text.length) {
                        val firstLine = layout.getLineForOffset(range.start)
                        val lastLine = layout.getLineForOffset(range.end - 1)
                        for (line in firstLine..lastLine) {
                            val start = maxOf(range.start, layout.getLineStart(line))
                            val end = minOf(range.end, layout.getLineEnd(line, visibleEnd = true))
                            if (start < end) {
                                var left = Float.POSITIVE_INFINITY
                                var right = Float.NEGATIVE_INFINITY
                                for (offset in start until end) {
                                    val bounds = layout.getBoundingBox(offset)
                                    left = minOf(left, bounds.left)
                                    right = maxOf(right, bounds.right)
                                }
                                val topLeft = Offset(left - horizontalInset, layout.getLineTop(line) - verticalInset)
                                val size = Size(right - left + horizontalInset * 2, layout.getLineBottom(line) - layout.getLineTop(line) + verticalInset * 2)
                                drawRoundRect(fill, topLeft, size, radius)
                                drawRoundRect(border, topLeft, size, radius, style = Stroke(1.dp.toPx()))
                            }
                        }
                    }
                }
            }
            // Chrome is outside the masked text layer, so only glyph opacity changes.
            val maskIncomingText = streaming && incomingAlpha.value < 1f && incomingRange != null
            if (maskIncomingText) {
                val layout = textLayout
                val left = layout?.let { (0 until it.lineCount).minOfOrNull(it::getLineLeft) } ?: 0f
                val right = layout?.let { (0 until it.lineCount).maxOfOrNull(it::getLineRight) } ?: size.width
                drawContext.canvas.saveLayer(
                    Rect(minOf(0f, left), 0f, maxOf(size.width, right), size.height),
                    fadePaint,
                )
            }
            drawContent()
            // DstOut changes only the incoming glyph region, including syntax/link colors.
            // Previously visible characters retain full opacity throughout streaming updates.
            if (maskIncomingText) {
                textLayout?.let { layout ->
                    incomingRange?.let { range ->
                        for (offset in range) {
                            if (offset < layout.layoutInput.text.length) {
                                val bounds = layout.getBoundingBox(offset)
                                drawRect(
                                    color = Color.Black.copy(alpha = 1f - incomingAlpha.value),
                                    topLeft = bounds.topLeft,
                                    size = bounds.size,
                                    blendMode = BlendMode.DstOut,
                                )
                            }
                        }
                    }
                }
                drawContext.canvas.restore()
            }
        },
    )
}
