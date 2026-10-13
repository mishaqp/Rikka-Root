package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real rendering page uses the same numeric editor as behavior thresholds. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w320dp-h891dp-mdpi")
class InterfaceNumberKeyboardRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun doneSavesTheTypedThresholdOnceAndReleasesKeyboardFocus() = verifyDone("250", 250)

    @Test fun doneKeepsTheExistingUpperBoundAndReleasesKeyboardFocus() = verifyDone("123456789", 999)

    @Test fun doneRestoresThePreviousNumberWhenDraftIsEmptyAndReleasesKeyboardFocus() = verifyDone("", 42)

    @Test fun losingFocusStillSavesTheTypedThresholdExactlyOnce() {
        val fixture = mountRenderingPage()
        val field = compose.onNode(hasSetTextAction()).performScrollTo().performClick().assertIsFocused()
        field.performTextReplacement("250")
        compose.runOnIdle { fixture.focusManager.clearFocus() }
        field.assertIsNotFocused().assertTextEquals("250")
        compose.runOnIdle {
            assertEquals(250, fixture.settings.value.displaySetting.codeBlockCollapseLines)
            assertEquals(1, fixture.updates)
        }
    }

    private fun verifyDone(draft: String, expected: Int) {
        val fixture = mountRenderingPage()
        val field = compose.onNode(hasSetTextAction()).performScrollTo().performClick().assertIsFocused()
        field.performTextReplacement(draft)
        val hidesBeforeDone = fixture.keyboard.hides
        field.performImeAction()
        field.assertIsNotFocused().assertTextEquals(expected.toString())
        compose.runOnIdle {
            assertEquals(expected, fixture.settings.value.displaySetting.codeBlockCollapseLines)
            assertEquals("Done must not save once and then save again on blur", 1, fixture.updates)
            assertTrue("Done explicitly dismisses the software keyboard", fixture.keyboard.hides > hidesBeforeDone)
        }
    }

    private fun mountRenderingPage(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides fixture.settings.value,
                LocalDensity provides Density(1f, 1.3f),
                LocalSoftwareKeyboardController provides fixture.keyboard,
                LocalNavController provides fixture.navigator,
            ) {
                fixture.focusManager = LocalFocusManager.current
                MaterialTheme {
                    Surface {
                        InterfaceSettingsContent(InterfaceSettingsPageId.Rendering, fixture.settings.value,
                            onUpdateSettings = { transform ->
                                fixture.settings.value = transform(fixture.settings.value)
                                fixture.updates++
                            })
                    }
                }
            }
        }
        return fixture
    }

    private class Fixture {
        val settings = mutableStateOf(Settings().let {
            it.copy(displaySetting = it.displaySetting.copy(codeBlockAutoCollapse = true, codeBlockCollapseLines = 42))
        })
        val keyboard = RecordingKeyboard()
        val navigator = Navigator(mutableListOf())
        lateinit var focusManager: FocusManager
        var updates = 0
    }

    private class RecordingKeyboard : SoftwareKeyboardController {
        var hides = 0
        override fun show() = Unit
        override fun hide() { hides++ }
    }
}
