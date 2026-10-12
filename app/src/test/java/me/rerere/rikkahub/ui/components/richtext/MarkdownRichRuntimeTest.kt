package me.rerere.rikkahub.ui.components.richtext

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownRichRuntimeTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()
    private val defaultOptions = MarkdownDisplayOptions(false, false, true, false)

    @Test fun savedFontAndRatioReachNativeAndHtmlTablesAndInlineCode() {
        val ratio = 1.4f
        val native = "| Заголовок | Второй |\n|---|---|\n| Значение | Данные |\n\nПример `код`."
        val html = "<table><thead><tr><th>Заголовок</th><th>Второй</th></tr></thead><tbody><tr><td>Значение</td><td>Данные</td></tr></tbody></table><p>Пример <code>код</code>.</p>"
        val content = mutableStateOf(native)
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalSettings provides ScreenshotFixtures.settings.copy(
                    displaySetting = ScreenshotFixtures.settings.displaySetting.copy(fontSizeRatio = ratio),
                )) {
                    ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = 15.5.sp * ratio, lineHeight = 21.7.sp * ratio)) {
                        Surface(Modifier.width(width.dp)) {
                            key(content.value) { MarkdownBlock(content.value, displayOptions = defaultOptions.copy(fontSizeRatio = ratio)) }
                        }
                    }
                }
            }
        }
        fun layouts(text: String): List<TextLayoutResult> {
            val layouts = mutableListOf<TextLayoutResult>()
            val nodes = compose.onAllNodesWithText(text)
            // DataTable subcomposes natural-size and placed cells. Verify both real renderers.
            nodes.fetchSemanticsNodes().indices.forEach { index ->
                nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            }
            assertTrue("$text must expose a rendered text layout", layouts.isNotEmpty())
            return layouts
        }
        fun assertTypography() {
            layouts("Заголовок").forEach { layout ->
                assertEquals(FontFamily.Serif, layout.layoutInput.style.fontFamily)
                assertEquals(13f * ratio, layout.layoutInput.style.fontSize.value, 0.001f)
            }
            layouts("Значение").forEach { layout ->
                assertEquals(FontFamily.Serif, layout.layoutInput.style.fontFamily)
                assertEquals(13.5f * ratio, layout.layoutInput.style.fontSize.value, 0.001f)
            }
            layouts("Пример код.").forEach { layout ->
                val inline = layout.layoutInput
                assertEquals(FontFamily.Serif, inline.style.fontFamily)
                val codeStyle = inline.text.spanStyles.single { it.item.fontFamily == JetbrainsMono }.item
                assertEquals(13f * ratio, codeStyle.fontSize.value, 0.001f)
            }
        }
        assertTypography()
        compose.runOnIdle { content.value = html }
        compose.waitForIdle()
        assertTypography()
    }

    @Test fun incomingFadeKeepsExistingGlyphsAndCodeChromeVisible() {
        val content = mutableStateOf("a")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Surface(Modifier.width(width.dp)) {
                    CompositionLocalProvider(LocalMarkdownStreaming provides true) {
                        MarkdownStyledText(
                            text = buildAnnotatedString {
                                pushStringAnnotation(INLINE_CODE_ANNOTATION, "")
                                append(content.value)
                                pop()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("streaming_text"),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        val before = compose.onNodeWithTag("streaming_text").captureToImage().toPixelMap()
        val oldLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("streaming_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(oldLayouts) }
        val oldBounds = oldLayouts.single().getBoundingBox(0)
        compose.runOnIdle { content.value = "ab" }
        // runOnIdle synchronizes before its action. Flush this action's snapshot
        // notification before advancing the paused clock, then finish Android drawing.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val incomingLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("streaming_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(incomingLayouts) }
        assertEquals("The incoming snapshot must contain the updated rendered text", "ab", incomingLayouts.single().layoutInput.text.text)
        val after = compose.onNodeWithTag("streaming_text").captureToImage().toPixelMap()
        for (y in (oldBounds.top + 3f).toInt().coerceAtLeast(0) until (oldBounds.bottom - 3f).toInt().coerceAtMost(before.height)) {
            for (x in (oldBounds.left + 3f).toInt().coerceAtLeast(0) until (oldBounds.right - 3f).toInt().coerceAtMost(before.width)) {
                assertEquals("Existing glyph and its chip must stay visible at $x,$y", before[x, y], after[x, y])
            }
        }
        compose.mainClock.advanceTimeBy(300)
        val complete = compose.onNodeWithTag("streaming_text").captureToImage().toPixelMap()
        val newLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("streaming_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(newLayouts) }
        val newBounds = newLayouts.single().getBoundingBox(1)
        var incomingGlyphChanged = false
        for (y in newBounds.top.toInt().coerceAtLeast(0) until newBounds.bottom.toInt().coerceAtMost(after.height)) {
            for (x in newBounds.left.toInt().coerceAtLeast(0) until newBounds.right.toInt().coerceAtMost(after.width)) {
                if (after[x, y] != complete[x, y]) incomingGlyphChanged = true
            }
        }
        assertTrue("Incoming glyphs must fade into view while the existing glyph remains stable", incomingGlyphChanged)
        // The chip has a 3dp painted inset. Sample its flat fill outside the glyph mask,
        // rather than glyph-box top pixels which may contain antialiased ascenders.
        val fillX = (newBounds.right + 1f).toInt().coerceIn(0, after.width - 1)
        val fillY = newBounds.center.y.toInt().coerceIn(0, after.height - 1)
        assertTrue("The fill sample must be outside the incoming glyph mask", fillX >= newBounds.right)
        assertEquals("The incoming code chip fill must not fade with its glyph", complete[fillX, fillY], after[fillX, fillY])
    }

    @Test fun codeHeaderReversiblyExpandsWithoutRemovingCopyAndSave() {
        val code = (1..12).joinToString("\n") { "val item$it = $it" }
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Surface(Modifier.width(width.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownDisplayOptions provides defaultOptions) {
                        HighlightCodeBlock(code, "kotlin")
                    }
                }
            }
        }
        compose.onNodeWithText(code).assertDoesNotExist()
        compose.onNodeWithTag("markdown_code_header_toggle").assertHasClickAction().performClick()
        compose.onNodeWithText(code).assertExists()
        compose.onNodeWithTag("markdown_code_header_toggle").performClick()
        compose.onNodeWithText(code).assertDoesNotExist()
    }

    @Test fun lineNumberAndWrappingOptionsReachTheProductionCodeRenderer() {
        val code = "long_variable_name_".repeat(16)
        val options = mutableStateOf(defaultOptions.copy(codeBlockAutoCollapse = false))
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Surface(Modifier.width(width.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownDisplayOptions provides options.value) {
                        HighlightCodeBlock(code, "plaintext")
                    }
                }
            }
        }
        fun textLayout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(code).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        assertEquals(1, textLayout().lineCount)
        compose.runOnIdle { options.value = options.value.copy(showLineNumbers = true) }
        compose.onNodeWithText("1").assertExists()
        assertEquals(1, textLayout().lineCount)
        compose.runOnIdle { options.value = options.value.copy(codeBlockAutoWrap = true) }
        assertTrue(textLayout().lineCount > 1)
        compose.onNodeWithText("1").assertExists()
        compose.runOnIdle { options.value = options.value.copy(showLineNumbers = false) }
        compose.onNodeWithText("1").assertDoesNotExist()
        assertTrue(textLayout().lineCount > 1)
    }

    @Test fun nativeAndHtmlTablesKeepCopyAndCsvControls() {
        val native = "| Тип | Ссылка |\n|---|---|\n| Документ | [API](https://example.com) |"
        val html = "<table><thead><tr><th>HTML тип</th><th>Ссылка</th></tr></thead><tbody><tr><td>Документ</td><td><a href=\"https://example.com\">API</a></td></tr></tbody></table>"
        val content = mutableStateOf(native)
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Surface(Modifier.width(width.dp)) {
                    MarkdownBlock(content.value, displayOptions = defaultOptions)
                }
            }
        }
        compose.onNodeWithTag("markdown_table_csv").assertHasClickAction()
        compose.onNodeWithTag("markdown_table_copy").performClick()
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(native, clipboard.primaryClip!!.getItemAt(0).text.toString())
            content.value = html
        }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("HTML тип")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("markdown_table_csv").assertHasClickAction()
        compose.onNodeWithTag("markdown_table_copy").performClick()
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.contains("<table>"))
            assertTrue(copied.contains("https://example.com"))
        }
    }

    @Test fun inlineCodeKeepsItsNativeCharactersAndWrapsInBothRenderers() {
        val token = "long_snake_case_".repeat(14)
        val content = mutableStateOf("Начало `$token` конец")
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Surface(Modifier.width(width.dp)) {
                    Column { key(content.value) { MarkdownBlock(content.value, displayOptions = defaultOptions) } }
                }
            }
        }
        fun assertNativeText() {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Начало $token конец").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("Начало $token конец", layout.layoutInput.text.text)
            assertEquals(1, layout.layoutInput.text.getStringAnnotations(INLINE_CODE_ANNOTATION, 0, layout.layoutInput.text.length).size)
            assertTrue(layout.lineCount > 1)
        }
        assertNativeText()
        compose.runOnIdle { content.value = "<p>Начало <code>$token</code> конец</p>" }
        compose.waitForIdle()
        assertNativeText()
    }

    @Test fun inlineCodeChromeUsesThreePixelSideInsetInAstAndHtml() {
        val content = mutableStateOf("prefix `code` suffix")
        compose.setContent {
            ScreenshotTheme(fontScale = fontScale) {
                Surface(Modifier.width(width.dp)) {
                    Column { key(content.value) { MarkdownBlock(content.value, displayOptions = defaultOptions) } }
                }
            }
        }
        fun assertChrome() {
            val node = compose.onNodeWithText("prefix code suffix")
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val code = layout.layoutInput.text.getStringAnnotations(INLINE_CODE_ANNOTATION, 0, layout.layoutInput.text.length).single()
            val lastGlyph = layout.getBoundingBox(code.end - 1)
            val pixels = node.captureToImage().toPixelMap()
            val y = lastGlyph.center.y.toInt().coerceIn(0, pixels.height - 1)
            val inside = (lastGlyph.right + 2f).toInt().coerceIn(0, pixels.width - 1)
            val outside = (lastGlyph.right + 5f).toInt().coerceIn(0, pixels.width - 1)
            assertTrue("Code chrome should stop within 3dp of its last glyph", pixels[inside, y] != pixels[outside, y])
        }
        assertChrome()
        compose.runOnIdle { content.value = "<p>prefix <code>code</code> suffix</p>" }
        compose.waitForIdle()
        assertChrome()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp-font{1}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf(320, 1f), arrayOf(320, 1.3f), arrayOf(411, 1f), arrayOf(411, 1.3f))
    }
}
