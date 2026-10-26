package me.rerere.rikkahub.ui.theme

import android.app.Application
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
            val previewColors = effectiveDisplayColors(settingsBaseColorScheme(context, first, dark), dark, false, display)
            val productionColors = effectiveDisplayColors(platform, dark, false, display)
            val preview = previewColors.colorScheme
            val production = productionColors.colorScheme
            assertEquals(production.primary, preview.primary)
            assertEquals(production.surfaceContainerHigh, preview.surfaceContainerHigh)
            assertEquals(production.surfaceContainer, preview.surfaceContainer)
            assertEquals(productionColors.sheetTileContainer, previewColors.sheetTileContainer)
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

    @Test fun `sheet tiles do not recolor surfaces outside sheets`() {
        for (dark in listOf(false, true)) {
            val base = PresetThemes.first().getColorScheme(dark)
            for (layeredSurfaces in listOf(false, true)) {
                val withoutSheetTiles = effectiveDisplayColorScheme(base, dark, false, DisplaySetting(
                    useLayeredSurfaces = layeredSurfaces, useLayeredSheetTiles = false,
                ))
                val withSheetTiles = effectiveDisplayColorScheme(base, dark, false, DisplaySetting(
                    useLayeredSurfaces = layeredSurfaces, useLayeredSheetTiles = true,
                ))
                assertEquals(withoutSheetTiles.background, withSheetTiles.background)
                assertEquals(withoutSheetTiles.surface, withSheetTiles.surface)
                assertEquals(withoutSheetTiles.surfaceContainerLow, withSheetTiles.surfaceContainerLow)
                assertEquals(withoutSheetTiles.surfaceContainer, withSheetTiles.surfaceContainer)
                assertEquals(withoutSheetTiles.surfaceContainerHigh, withSheetTiles.surfaceContainerHigh)
            }
        }
    }

    @Test fun `all four combinations keep sheet and page roles independent`() {
        for (dark in listOf(false, true)) {
            val base = PresetThemes.first().getColorScheme(dark)
            for (layeredSurfaces in listOf(false, true)) {
                val sheetOff = effectiveDisplayColors(base, dark, false, DisplaySetting(
                    useLayeredSurfaces = layeredSurfaces, useLayeredSheetTiles = false,
                ))
                val sheetOn = effectiveDisplayColors(base, dark, false, DisplaySetting(
                    useLayeredSurfaces = layeredSurfaces, useLayeredSheetTiles = true,
                ))
                assertNull(sheetOff.sheetTileContainer)
                assertNotNull(sheetOn.sheetTileContainer)
                assertNotEquals(sheetOn.colorScheme.surface, sheetOn.sheetTileContainer)
                assertEquals(sheetOff.colorScheme.primary, sheetOn.colorScheme.primary)
                assertEquals(sheetOff.colorScheme.primaryContainer, sheetOn.colorScheme.primaryContainer)
                assertEquals(sheetOff.colorScheme.surfaceContainer, sheetOn.colorScheme.surfaceContainer)
            }
            for (layeredSheetTiles in listOf(false, true)) {
                val surfacesOff = effectiveDisplayColors(base, dark, false, DisplaySetting(
                    useLayeredSurfaces = false, useLayeredSheetTiles = layeredSheetTiles,
                ))
                val surfacesOn = effectiveDisplayColors(base, dark, false, DisplaySetting(
                    useLayeredSurfaces = true, useLayeredSheetTiles = layeredSheetTiles,
                ))
                assertNotEquals(surfacesOff.colorScheme.surfaceContainerLow, surfacesOn.colorScheme.surfaceContainerLow)
                assertNotEquals(surfacesOff.colorScheme.surfaceContainerHigh, surfacesOn.colorScheme.surfaceContainerHigh)
                assertEquals(surfacesOff.colorScheme.primary, surfacesOn.colorScheme.primary)
                if (layeredSheetTiles) {
                    assertNotNull(surfacesOff.sheetTileContainer)
                    assertNotNull(surfacesOn.sheetTileContainer)
                } else {
                    assertNull(surfacesOff.sheetTileContainer)
                    assertNull(surfacesOn.sheetTileContainer)
                }
            }
        }
    }

    @Test fun `AMOLED and pure backgrounds keep priority in every combination`() {
        for (layeredSurfaces in listOf(false, true)) {
            for (layeredSheetTiles in listOf(false, true)) {
                val display = DisplaySetting(usePureBackground = true,
                    useLayeredSurfaces = layeredSurfaces, useLayeredSheetTiles = layeredSheetTiles)
                val dark = effectiveDisplayColors(PresetThemes.first().getColorScheme(true), true, true, display)
                assertEquals(Color.Black, dark.colorScheme.background)
                assertEquals(Color.Black, dark.colorScheme.surface)
                val light = effectiveDisplayColors(PresetThemes.first().getColorScheme(false), false, false, display)
                assertEquals(Color.White, light.colorScheme.background)
                assertEquals(Color.White, light.colorScheme.surface)
            }
        }
    }

    @Test fun `changed surfaces retain text contrast and custom accent colors`() {
        val context = RuntimeEnvironment.getApplication()
        val custom = CustomTheme(id = "contrast-custom", name = "Custom",
            primaryColorArgb = 0xFF00695CL, secondaryColorArgb = 0xFF4A148CL, tertiaryColorArgb = 0xFF880E4FL)
        for (darkMode in listOf(false, true)) {
            val bases = PresetThemes.map { it.getColorScheme(darkMode) } +
                (if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) +
                custom.generateColorScheme(darkMode)
            for (base in bases) {
                for (layeredSurfaces in listOf(false, true)) {
                    for (layeredSheetTiles in listOf(false, true)) {
                        val colors = effectiveDisplayColors(base, darkMode, false, DisplaySetting(
                            useLayeredSurfaces = layeredSurfaces, useLayeredSheetTiles = layeredSheetTiles,
                        ))
                        assertContrast(colors.colorScheme.onSurface,
                            colors.sheetTileContainer ?: colors.colorScheme.surface)
                        assertContrast(colors.colorScheme.onSurface, colors.colorScheme.surfaceContainerLow)
                        assertContrast(colors.colorScheme.onSurface, colors.colorScheme.surfaceContainerHigh)
                        assertContrast(colors.colorScheme.onPrimaryContainer, colors.colorScheme.primaryContainer)
                        assertEquals(base.primary, colors.colorScheme.primary)
                        assertEquals(base.secondary, colors.colorScheme.secondary)
                        assertEquals(base.tertiary, colors.colorScheme.tertiary)
                    }
                }
            }
        }
    }

    private fun assertContrast(text: Color, background: Color) {
        val textLuminance = text.luminance().toDouble() + .05
        val backgroundLuminance = background.luminance().toDouble() + .05
        val ratio = if (textLuminance > backgroundLuminance) {
            textLuminance / backgroundLuminance
        } else {
            backgroundLuminance / textLuminance
        }
        assertTrue("Text contrast must remain at least 4.5:1, actual=$ratio", ratio >= 4.5)
    }
}
