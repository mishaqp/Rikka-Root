package me.rerere.rikkahub.ui.screenshot

import me.rerere.rikkahub.ui.pages.setting.InterfaceSettingsPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the review matrix requested for every transferred screen. */
class OvernightScreenshotCoverageTest {
    @Test fun interfaceCoversFourThemesTwoFontScalesAndTwoWidths() {
        val cases = UiCustomizationScreenshotsTest.cases()
        val scenes = cases.map { it[0].toString() }.toSet()
        val sceneData = cases.map { it[0] as UiCustomizationScreenshotScene }.toSet()
        assertEquals("All twenty production interface pages are represented", 20,
            sceneData.mapNotNull { it.page }.toSet().size)
        assertEquals(InterfaceSettingsPageId.entries.toSet(), sceneData.mapNotNull { it.page }.toSet())
        assertEquals("Both ends of every long settings page stay covered", 7, sceneData.count { it.bottom })
        assertEquals(DrawerScreenshotMode.entries.toSet(), sceneData.mapNotNull { it.drawer }.toSet())
        assertTrue("Search and both scrolled header backgrounds remain covered", scenes.containsAll(
            setOf("settings_search", "chat_header_scrolled", "chat_wallpaper_header_scrolled")))
        assertEquals("Every interface/drawer scene needs 4 themes × 2 fonts × 2 widths",
            scenes.size * 16, cases.size)
        assertEquals(ScreenshotThemeMode.entries.toSet(), cases.map { it[1] }.toSet())
        assertEquals(setOf(1f, 1.3f), cases.map { it[2] }.toSet())
        assertEquals(setOf(320, 411), cases.map { it[3] }.toSet())
        scenes.forEach { scene ->
            assertEquals("No repeated or missing parameter tuple for $scene", 16,
                cases.filter { it[0].toString() == scene }.map { it.drop(1) }.toSet().size)
        }
    }

    @Test fun chatCoversFourThemesTwoFontScalesAndTwoWidths() {
        val cases = ChatBeforeScreenshotsTest.cases()
        assertEquals("Every production chat scenario needs 4 themes × 2 fonts × 2 widths", 16, cases.size)
        assertEquals(ScreenshotThemeMode.entries.toSet(), cases.map { it[0] }.toSet())
        assertEquals(setOf(1f, 1.3f), cases.map { it[1] }.toSet())
        assertEquals(setOf(320, 411), cases.map { it[2] }.toSet())
        assertEquals("Every parameter tuple is unique", 16, cases.map { it.toList() }.toSet().size)
    }
}
