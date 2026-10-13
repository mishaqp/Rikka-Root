package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.ChatMessageBackgroundStyle
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.screenshot.UiCustomizationScreenshotBoundaries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageStyleRoleLabelsLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val boundaries = UiCustomizationScreenshotBoundaries()

    @Before fun installOfflineImages() { boundaries.install(); compose.registerIdlingResource(boundaries.imagesIdle) }
    @After fun uninstallOfflineImages() { compose.unregisterIdlingResource(boundaries.imagesIdle); boundaries.close() }

    @Test fun compactRoleLabelsRemainCompleteSingleLinesWhileBothSelectionsWork() {
        val settings = mutableStateOf(ScreenshotFixtures.settings.copy(displaySetting =
            ScreenshotFixtures.settings.displaySetting.copy(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED)))
        compose.setContent {
            ScreenshotTheme(fontScale = 1.3f) {
                Surface(Modifier.requiredSize(320.dp, 891.dp)) {
                    InterfaceSettingsContent(InterfaceSettingsPageId.MessageStyle, settings.value,
                        onUpdateSettings = { settings.value = it(settings.value) })
                }
            }
        }
        fun verifyLabel(text: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("Role '$text' must fit on one complete line", 1, layout.lineCount)
            assertFalse("Role label must not clip horizontally", layout.didOverflowWidth)
            assertFalse("Role label must not clip vertically", layout.didOverflowHeight)
            assertFalse("Role label must not elide characters", layout.isLineEllipsized(0))
            assertEquals(text.length, layout.getLineEnd(0))
        }
        verifyLabel("Пользователь")
        verifyLabel("Ассистент")
        compose.onNodeWithText("Ассистент").assertIsSelected()
        compose.onNodeWithText("Пользователь").performClick().assertIsSelected()
        verifyLabel("Пользователь")
        verifyLabel("Ассистент")
        compose.onNodeWithText("Ассистент").performClick().assertIsSelected()
        verifyLabel("Пользователь")
    }
}
