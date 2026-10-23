package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.screenshot.ScreenshotThemeMode
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w320dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatLanguageSelectionRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private var previousFontScale = 1f

    @Before fun saveFontScale() { previousFontScale = RuntimeEnvironment.getFontScale() }
    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(previousFontScale) }

    @Test fun `Russian can be selected as a translation language`() {
        val selected = mutableListOf<Locale>()
        showDialog(selected)

        capture("language-light-top")

        select("🇷🇺 Русский")

        assertEquals(listOf("ru"), selected.map { it.language })
    }

    @Test fun `simplified Chinese has the correct flag and still selects China Chinese`() {
        val selected = mutableListOf<Locale>()
        showDialog(selected)

        select("🇨🇳 Упрощённый китайский")

        assertEquals(listOf(Locale.SIMPLIFIED_CHINESE), selected)
    }

    @Test fun `traditional Chinese is readable in Russian and still selects Taiwan Chinese`() {
        val selected = mutableListOf<Locale>()
        showDialog(selected)

        select("🇨🇳 Традиционный китайский")

        assertEquals(listOf(Locale.TRADITIONAL_CHINESE), selected)
    }

    @Test fun `clear translation remains reachable on a narrow screen with larger text`() {
        // ModalBottomSheet has its own Android window; it also needs the system font scale.
        RuntimeEnvironment.setFontScale(1.3f)
        var clears = 0
        val selected = mutableListOf<Locale>()
        compose.setContent {
            ScreenshotTheme(mode = ScreenshotThemeMode.Dark, fontScale = 1.3f) {
                LanguageSelectionDialog(onLanguageSelected = selected::add,
                    onClearTranslation = { clears++ }, onDismissRequest = {})
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Очистить перевод"))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Очистить перевод").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1.3f, layouts.single().layoutInput.density.fontScale, 0.001f)
        capture("language-dark-large-bottom")
        compose.onNodeWithText("Очистить перевод").assertIsDisplayed().performClick()

        assertEquals(1, clears)
        assertEquals(emptyList<Locale>(), selected)
    }

    private fun showDialog(selected: MutableList<Locale>) {
        compose.setContent {
            ScreenshotTheme {
                LanguageSelectionDialog(onLanguageSelected = selected::add, onDismissRequest = {})
            }
        }
    }

    private fun select(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        val title = RuntimeEnvironment.getApplication().getString(R.string.translation_language_selection_title)
        val sheet = compose.onNode(isRoot() and hasAnyDescendant(hasText(title)))
        val directory = File("build/outputs/roborazzi/chat-ui-polish").apply { mkdirs() }
        val warmup = File(directory, "$name-warmup.png")
        val options = RoborazziOptions(taskType = RoborazziTaskType.Record)
        try {
            sheet.captureRoboImage(warmup.absolutePath, roborazziOptions = options)
            compose.waitForIdle()
            sheet.captureRoboImage(File(directory, "$name.png").absolutePath, roborazziOptions = options)
        } finally {
            warmup.delete()
        }
    }
}
