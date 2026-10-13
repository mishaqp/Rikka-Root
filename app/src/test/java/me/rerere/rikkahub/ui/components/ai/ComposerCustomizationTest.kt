package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import org.junit.Assert.*
import org.junit.Test

class ComposerCustomizationTest {
    @Test fun savedOpacityIsIndependentInBothThemesAndClampsAtPaintBoundary() {
        val settings = DisplaySetting(chatInputBackgroundOpacityLight = .3f, chatInputBackgroundOpacityDark = .8f)
        assertEquals(.3f, presentComposerSurface(settings, false, false).opacity)
        assertEquals(.8f, presentComposerSurface(settings, true, false).opacity)
        assertEquals(1f, presentComposerSurface(settings.copy(chatInputBackgroundOpacityDark = 5f), true, false).opacity)
        assertEquals(0f, presentComposerSurface(settings.copy(chatInputBackgroundOpacityLight = -1f), false, false).opacity)
    }

    @Test fun economyRemovesBackdropCaptureWhileKeepingTintAndGenerationScreenChoice() {
        val glass = DisplaySetting(glassTheme = true, keepScreenOnDuringGeneration = true)
        val active = presentComposerSurface(glass, false, true)
        val economy = presentComposerSurface(glass.copy(glassEconomy = true), false, true)
        assertTrue(active.blur)
        assertFalse(economy.blur)
        assertEquals(active.opacity, economy.opacity)
        assertTrue(active.keepScreenOn)
        assertFalse(presentComposerSurface(glass, false, false).keepScreenOn)
        assertFalse(presentComposerSurface(glass.copy(keepScreenOnDuringGeneration = false), false, true).keepScreenOn)
    }

    @Test fun legacyEffectPickerSelectsDistinctBlurAndGlassAndEconomyDisablesBoth() {
        val settings = DisplaySetting(enableBlurEffect = true)
        val blur = presentComposerSurface(settings, false, false)
        val glass = presentComposerSurface(settings.copy(backgroundEffectType = BackgroundEffectType.GLASS), false, false)
        assertTrue(blur.blur)
        assertFalse(blur.glass)
        assertFalse(glass.blur)
        assertTrue(glass.glass)
        val economy = presentComposerSurface(settings.copy(backgroundEffectType = BackgroundEffectType.GLASS, glassEconomy = true), false, false)
        assertFalse(economy.blur)
        assertFalse(economy.glass)
        assertEquals(glass.opacity, economy.opacity)
    }
}
