package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InterfaceNavigationRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowThemeRowKeepsReadableLabelWithComposableSelection() =
        verifyCompactLabel(R.string.ui_display_settings_page_theme_settings_title, "Настройки темы", true)

    @Test fun narrowFontRowKeepsReadableLabelWithTextSelection() =
        verifyCompactLabel(R.string.ui_fonts_chat, "Шрифт сообщений", false)

    private fun verifyCompactLabel(title: Int, label: String, composableDetail: Boolean) {
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(), LocalDensity provides Density(1f, 1.3f)) {
                MaterialTheme {
                    // The 320dp settings page reserves 16dp on each side.
                    Column(Modifier.width(288.dp)) {
                        InterfaceNavigationRow(title, HugeIcons.PaintBoard,
                            detail = if (composableDetail) null else "Системный по умолчанию",
                            detailContent = if (composableDetail) ({ Text("Сакура Розовый", style = MaterialTheme.typography.bodyLarge) }) else null) { clicks++ }
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("A narrow row must keep '$label' readable rather than one letter per line: ${layout.lineCount}",
            layout.lineCount <= 2)
        assertTrue("Full label must fit vertically", !layout.didOverflowHeight)
        val detail = if (composableDetail) "Сакура Розовый" else "Системный по умолчанию"
        val labelBounds = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val detailBounds = compose.onNodeWithText(detail, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Label and selection must stay inside the 320dp page", labelBounds.left >= 0f &&
            labelBounds.right <= 288f && detailBounds.left >= 0f && detailBounds.right <= 288f)
        assertTrue("The compact selection must not overlap the readable label", detailBounds.top >= labelBounds.bottom)
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals("Compact row retains its real navigation callback", 1, clicks) }
    }
}
