package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.rememberCodeFontFamily

/** Display options inherited by nested Markdown, tables and code blocks. */
data class MarkdownDisplayOptions(
    val enableLatexRendering: Boolean,
    val codeBlockAutoWrap: Boolean,
    val codeBlockAutoCollapse: Boolean,
    val showLineNumbers: Boolean,
    val fontSizeRatio: Float = 1f,
    val enableDollarLatex: Boolean = true,
    val codeBlockCollapseLines: Int = 10,
    val codeFontFamily: FontFamily? = null,
)

internal val LocalMarkdownDisplayOptions = staticCompositionLocalOf<MarkdownDisplayOptions?> { null }

@Composable
internal fun currentMarkdownDisplayOptions(): MarkdownDisplayOptions {
    val display = LocalSettings.current.displaySetting
    val codeFont = rememberCodeFontFamily(display)
    LocalMarkdownDisplayOptions.current?.let {
        return it.copy(codeFontFamily = it.codeFontFamily ?: codeFont)
    }
    return MarkdownDisplayOptions(
        enableLatexRendering = display.enableLatexRendering,
        codeBlockAutoWrap = display.codeBlockAutoWrap,
        codeBlockAutoCollapse = display.codeBlockAutoCollapse,
        showLineNumbers = display.showLineNumbers,
        fontSizeRatio = display.fontSizeRatio,
        enableDollarLatex = display.enableDollarLatex,
        codeBlockCollapseLines = display.codeBlockCollapseLines,
        codeFontFamily = codeFont,
    )
}

@Composable
internal fun currentMarkdownCodeFont(): FontFamily =
    currentMarkdownDisplayOptions().codeFontFamily ?: JetbrainsMono
