package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.ui.context.LocalSettings

/** Display options inherited by nested Markdown, tables and code blocks. */
data class MarkdownDisplayOptions(
    val enableLatexRendering: Boolean,
    val codeBlockAutoWrap: Boolean,
    val codeBlockAutoCollapse: Boolean,
    val showLineNumbers: Boolean,
    val fontSizeRatio: Float = 1f,
)

internal val LocalMarkdownDisplayOptions = staticCompositionLocalOf<MarkdownDisplayOptions?> { null }

@Composable
internal fun currentMarkdownDisplayOptions(): MarkdownDisplayOptions {
    LocalMarkdownDisplayOptions.current?.let { return it }
    val display = LocalSettings.current.displaySetting
    return MarkdownDisplayOptions(
        enableLatexRendering = display.enableLatexRendering,
        codeBlockAutoWrap = display.codeBlockAutoWrap,
        codeBlockAutoCollapse = display.codeBlockAutoCollapse,
        showLineNumbers = display.showLineNumbers,
        fontSizeRatio = display.fontSizeRatio,
    )
}
