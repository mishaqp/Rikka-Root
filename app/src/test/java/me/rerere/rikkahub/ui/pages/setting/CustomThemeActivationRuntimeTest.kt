package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.settingsBaseColorScheme
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomThemeActivationRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creatingAThemeAppliesItsPaletteWhenDynamicColorsWereEnabled() {
        val fixture = mountThemePage()
        compose.onNodeWithTag("ui_theme_create").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(string(R.string.setting_theme_page_theme_name)))
            .performTextReplacement("Новая тема")
        compose.onNode(hasClickAction() and hasText(string(R.string.setting_theme_page_save)))
            .assertIsEnabled().performClick()
        compose.runOnIdle { verifyAppliedTheme(fixture, "Новая тема") }
    }

    @Test fun importingAThemeAppliesItsPaletteWhenDynamicColorsWereEnabled() {
        val fixture = mountThemePage()
        val imported = CustomTheme(id = "backup-theme", name = "Импортированная тема", primaryColorArgb = 0xFF00695CL)
        compose.onNodeWithTag("ui_theme_import").performScrollTo().performClick()
        renderImportDialogUntilIdle()
        val field = compose.onNode(hasSetTextAction() and hasText("JSON") and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertIsEnabled()
        field.performClick()
        renderImportDialogUntilIdle()
        field.performTextReplacement(JsonInstant.encodeToString(imported))
        renderImportDialogUntilIdle()
        val confirm = compose.onNode(hasClickAction() and hasText(string(R.string.setting_theme_page_import_theme)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertIsEnabled()
        val bounds = confirm.fetchSemanticsNode().boundsInRoot
        val decor = importDialogDecor()
        org.junit.Assert.assertTrue("Import remains inside its visible window", bounds.left >= 0f &&
            bounds.top >= 0f && bounds.right <= decor.width && bounds.bottom <= decor.height)
        confirm.performClick()
        compose.runOnIdle {
            verifyAppliedTheme(fixture, imported.name)
            val theme = fixture.settings.value.customThemes.last()
            assertNotEquals("Import must retain its existing collision-safe fresh id", imported.id, theme.id)
            assertEquals(imported.copy(id = theme.id), theme)
        }
    }

    private fun verifyAppliedTheme(fixture: Fixture, name: String) {
        val actual = fixture.settings.value
        val theme = actual.customThemes.last()
        assertEquals(name, theme.name)
        assertEquals(theme.id, actual.themeId)
        assertFalse("Selecting the saved/imported custom theme must apply it instead of Android dynamic colors", actual.dynamicColor)
        assertEquals("Existing custom themes and unrelated settings stay unchanged", fixture.initial.copy(
            customThemes = fixture.initial.customThemes + theme, themeId = theme.id, dynamicColor = false), actual)
        assertEquals(theme.generateColorScheme(false).primary,
            settingsBaseColorScheme(RuntimeEnvironment.getApplication(), actual, false).primary)
        assertEquals(theme.generateColorScheme(true).primary,
            settingsBaseColorScheme(RuntimeEnvironment.getApplication(), actual, true).primary)
        assertEquals("The dialog saves once", 1, fixture.updates)
    }

    private fun mountThemePage(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides fixture.settings.value,
                    LocalToaster provides rememberToasterState()) {
                    Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                        SettingThemePage(InterfacePageState(fixture.settings.value, updateSettings = { transform ->
                            fixture.settings.value = transform(fixture.settings.value)
                            fixture.updates++
                        }, navigate = {}), showLargeHeading = false)
                    }
                }
            }
        }
        return fixture
    }

    private class Fixture {
        val initial = Settings(dynamicColor = true, developerMode = true,
            customThemes = listOf(CustomTheme(id = "existing", name = "Прежняя тема", primaryColorArgb = 0xFFAA5500L)))
        val settings = mutableStateOf(initial)
        var updates = 0
    }

    private fun string(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /**
     * Robolectric omits window draw passes: multiline text leaves a pending relayout after focus.
     * Render the real window for each requested frame, including its finite label animation.
     * An unsettled layout fails within 5 seconds; the ordinary Compose idle check still follows.
     */
    private fun renderImportDialogUntilIdle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread { findImportDialogDecor() != null }
        }
        compose.mainClock.advanceTimeUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            compose.runOnUiThread {
                drawDialogFrame(importDialogDecor())
                !compose.hasPendingWork()
            }
        }
        compose.waitForIdle()
    }

    private fun importDialogDecor(): View = checkNotNull(findImportDialogDecor())

    private fun drawDialogFrame(decor: View) {
        val width = decor.width.takeIf { it > 0 } ?: decor.resources.displayMetrics.widthPixels
        val availableHeight = decor.resources.displayMetrics.heightPixels
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun findImportDialogDecor(): View? {
        fun containsDialogLayout(view: View): Boolean =
            view.javaClass.name == "androidx.compose.ui.window.DialogLayout" ||
                (view is ViewGroup && (0 until view.childCount).any { containsDialogLayout(view.getChildAt(it)) })
        return WindowInspector.getGlobalWindowViews().singleOrNull { it.isAttachedToWindow && containsDialogLayout(it) }
    }

}
