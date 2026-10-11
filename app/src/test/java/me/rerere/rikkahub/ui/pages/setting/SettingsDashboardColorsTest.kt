package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDashboardColorsTest {
    @Test fun `AMOLED icon circles stay muted even with bright theme containers`() {
        val scheme = darkColorScheme(
            surface = Color.Black,
            primaryContainer = Color.White,
            secondaryContainer = Color.White,
            tertiaryContainer = Color.White,
        )
        SettingsSectionTone.entries.forEach { tone ->
            val (background, foreground) = settingsToneColors(scheme, tone, dark = true)
            assertTrue(tone.name, background.luminance() < .08f)
            assertTrue(tone.name, contrast(background, foreground) >= 3f)
        }
    }

    @Test fun `light theme icon circles have readable icons`() {
        SettingsSectionTone.entries.forEach { tone ->
            val (background, foreground) = settingsToneColors(lightColorScheme(), tone, dark = false)
            assertTrue(tone.name, contrast(background, foreground) >= 3f)
        }
    }

    @Test fun `dynamic section colors follow changed theme accents`() {
        val blue = lightColorScheme(primary = Color(0xFF2449A5))
        val purple = blue.copy(primary = Color(0xFF72339A))
        val first = settingsToneColors(blue, SettingsSectionTone.Primary, dark = false)
        val second = settingsToneColors(purple, SettingsSectionTone.Primary, dark = false)
        assertNotEquals(first.first, second.first)
        assertNotEquals(first.second, second.second)
    }

    private fun contrast(first: Color, second: Color): Float {
        val high = maxOf(first.luminance(), second.luminance())
        val low = minOf(first.luminance(), second.luminance())
        return (high + .05f) / (low + .05f)
    }
}
