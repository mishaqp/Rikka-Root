package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class InterfaceSwitchAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labeledRowExposesItsStateAsOneSwitchAndBothClickTargetsUpdateOnce() {
        val checked = mutableStateOf(true)
        var updates = 0
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    Surface {
                        InterfaceSwitchRow(R.string.ui_display_settings_page_enable_math_title,
                            checked.value, "math") { checked.value = it; updates++ }
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("ui_math")
        row.assertIsOn()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertCountEquals(1)
        row.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(1, updates) }
        row.performTouchInput { click(Offset(width - 40f, height / 2f)) }.assertIsOn()
        compose.runOnIdle { assertEquals(2, updates) }
        compose.runOnIdle { checked.value = false }
        row.assertIsOff()
    }
}
