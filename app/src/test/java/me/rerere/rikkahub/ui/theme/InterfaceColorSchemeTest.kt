package me.rerere.rikkahub.ui.theme

import android.app.Application
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InterfaceColorSchemeTest {
    @Test fun `dynamic palette ignores the selected preset and editors use the same effective roles`() {
        val context = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            val platform = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            val first = Settings(dynamicColor = true, themeId = PresetThemes.first().id)
            val second = first.copy(themeId = PresetThemes.last().id)
            assertEquals(platform.primary, settingsBaseColorScheme(context, first, dark).primary)
            assertEquals(platform.primary, settingsBaseColorScheme(context, second, dark).primary)
            assertEquals(platform.surface, settingsBaseColorScheme(context, second, dark).surface)
            val display = DisplaySetting(usePureBackground = true, useLayeredSurfaces = true, useLayeredSheetTiles = true)
            val preview = effectiveDisplayColorScheme(settingsBaseColorScheme(context, first, dark), dark, false, display)
            val production = effectiveDisplayColorScheme(platform, dark, false, display)
            assertEquals(production.primary, preview.primary)
            assertEquals(production.surfaceContainerHigh, preview.surfaceContainerHigh)
            assertEquals(production.surfaceContainer, preview.surfaceContainer)
            assertEquals(if (dark) Color.Black else Color.White, preview.surface)
            assertEquals(preview.surface, preview.background)
            assertNotEquals(platform.surfaceContainerHigh, preview.surfaceContainerHigh)
        }
    }

    @Test fun `AMOLED keeps black background while layered cards remain distinct`() {
        val base = PresetThemes.first().getColorScheme(true)
        val effective = effectiveDisplayColorScheme(base, true, true, DisplaySetting(useLayeredSurfaces = true))
        assertEquals(Color.Black, effective.background)
        assertEquals(Color.Black, effective.surface)
        assertNotEquals(Color.Black, effective.surfaceContainerHigh)
        assertEquals(base.primary, effective.primary)
    }
}
