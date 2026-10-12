package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDisplaySerializationTest {
    private fun restore(json: String) = JsonInstant.decodeFromString<Settings>(SettingsJsonMigrator.migrate(json))

    @Test fun legacyBackupsKeepAvatarDefaultAndGainNewMessageDefaults() {
        for (backup in listOf("{}", """{"displaySetting":{}}""")) {
            val display = restore(backup).displaySetting
            assertTrue(display.showUserAvatar)
            assertTrue(display.collapseLongUserMessages)
            assertEquals(9, display.userMessageCollapseLines)
            assertTrue(display.showUserMessageActions)
            assertTrue(display.showToolSummary)
            assertFalse(display.hideToolResultImages)
            assertTrue(display.autoCollapseToolSteps)
            assertFalse(display.confirmAssistantRegeneration)
            assertTrue(display.insertSuggestionOnly)
            assertTrue(display.showProducedFiles)
        }
    }

    @Test fun explicitLegacyBackupChoicesArePreserved() {
        for (avatar in listOf(true, false)) {
            val display = restore("""{"displaySetting":{"showUserAvatar":$avatar,"showThinkingContent":false,"autoCloseThinking":false,"bubbleOpacity":0.4}}""").displaySetting
            assertEquals(avatar, display.showUserAvatar)
            assertFalse(display.showThinkingContent)
            assertFalse(display.autoCloseThinking)
            assertEquals(0.4f, display.bubbleOpacity)
        }
    }

    @Test fun freshSettingsRemainFreshAfterExportAndImport() {
        val original = Settings()
        assertFalse(restore(JsonInstant.encodeToString(original)).displaySetting.showUserAvatar)
    }

    @Test fun everyMessageOptionSurvivesDisplayAndFullBackupRoundTrips() {
        val display = DisplaySetting(
            showUserAvatar = true, showAssistantBubble = true, bubbleOpacity = 0.3f,
            showModelIcon = false, showModelName = false, showDateTimeInMessage = true,
            showTokenUsage = false, showThinkingContent = false, autoCloseThinking = false,
            collapseLongUserMessages = false, userMessageCollapseLines = 15, showUserMessageActions = false,
            showToolSummary = false, hideToolResultImages = true, autoCollapseToolSteps = false,
            confirmAssistantRegeneration = true, insertSuggestionOnly = false, showProducedFiles = false,
        )
        assertEquals(display, JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(display)))
        assertEquals(display, restore(JsonInstant.encodeToString(Settings(displaySetting = display))).displaySetting)
    }

    @Test fun backupMigrationIsIdempotentAndPreservesUnknownDisplayProperties() {
        val backup = """{"displaySetting":{"futureOption":"keep"},"futureRoot":17}"""
        val once = SettingsJsonMigrator.migrate(backup)
        assertEquals(once, SettingsJsonMigrator.migrate(once))
        assertTrue(once.contains("\"futureOption\":\"keep\""))
        assertTrue(once.contains("\"futureRoot\":17"))
    }
}
