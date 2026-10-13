package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackgroundPresentationTest {
    @Test fun oldDefaultsKeepWallpaperAndGlassDisabledAndRespectAssistantArtwork() {
        val defaults = resolveChatBackground(Assistant(), DisplaySetting())
        assertNull(defaults.image)
        assertFalse(defaults.gradient)
        val saved = resolveChatBackground(Assistant(background = "content://saved/image", backgroundOpacity = .4f), DisplaySetting())
        assertEquals("content://saved/image", saved.image)
        assertEquals(.4f, saved.imageOpacity, .001f)
    }

    @Test fun globalWallpaperRequiresOptInAndCannotReplaceConversationAssistantArtwork() {
        val display = DisplaySetting(chatBackgroundImagePath = "/wallpaper.png")
        assertNull(resolveChatBackground(Assistant(), display).image)
        val enabled = display.copy(chatBackgroundEnabled = true)
        assertEquals("/wallpaper.png", resolveChatBackground(Assistant(), enabled).image)
        assertEquals("/assistant.png", resolveChatBackground(Assistant(background = "/assistant.png"), enabled).image)
    }

    @Test fun maskStrengthZeroKeepsUnmodifiedWallpaperAndTwoScalesMoruMask() {
        val assistant = Assistant(background = "https://example.com/image.png")
        val raw = resolveChatBackground(assistant, DisplaySetting(chatBackgroundMaskStrength = 0f))
        assertEquals(0f, raw.topMaskAlpha, .001f)
        assertEquals(0f, raw.bottomMaskAlpha, .001f)
        assertEquals(0f, raw.shadowAlpha, .001f)
        val maximum = resolveChatBackground(assistant, DisplaySetting(chatBackgroundMaskStrength = 9f))
        assertEquals(.4f, maximum.topMaskAlpha, .001f)
        assertEquals(1f, maximum.bottomMaskAlpha, .001f)
        assertEquals(.08f, maximum.shadowAlpha, .001f)
        val desktop = resolveChatBackground(assistant, DisplaySetting(), bigScreen = true)
        assertEquals(.08f, desktop.topMaskAlpha, .001f)
        assertEquals(.36f, desktop.bottomMaskAlpha, .001f)
    }

    @Test fun gradientRetainsItsExplicitPriorityAndGlassUsesItWithoutWallpaper() {
        assertTrue(resolveChatBackground(Assistant(useGradientBackground = true, background = "/saved.png"), DisplaySetting()).gradient)
        assertTrue(resolveChatBackground(Assistant(), DisplaySetting(glassTheme = true)).gradient)
        assertFalse(resolveChatBackground(Assistant(background = "/saved.png"), DisplaySetting(glassTheme = true)).gradient)
        assertFalse(resolveChatBackground(Assistant(), DisplaySetting(glassEconomy = true)).gradient)
    }
}
