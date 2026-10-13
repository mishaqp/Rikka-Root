package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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

    @Test fun legacyBackupsInheritCombinedControlsAndKeepTheExistingVisualDefaults() {
        val display = restore("""{"displaySetting":{"showUserAvatar":true,"showDateTimeInMessage":true,"autoCollapseToolSteps":false}}""").displaySetting
        assertNull(display.showUserName)
        assertNull(display.showUserTimestamp)
        assertNull(display.showAssistantTimestamp)
        assertNull(display.collapseThinkingSteps)
        assertNull(display.userMessageCollapseChars)
        assertNull(display.newChatOnLaunch)
        assertNull(display.newChatOnAssistantSwitch)
        assertNull(display.appLanguageTag)
        assertEquals(10, display.codeBlockCollapseLines)
        assertEquals(ChatMessageBackgroundStyle.DEFAULT, display.chatMessageBackgroundStyle)
        assertTrue(display.showThinkingCards)
        assertTrue(display.showToolCards)
        assertTrue(display.assistantBubbleFitContent)
        assertTrue(display.keepScreenOnDuringGeneration)
        assertTrue(display.hapticsEnabled)
        assertFalse(display.hapticsOnDrawer)
        assertFalse(display.hapticsOnSwitch)
        assertFalse(display.hapticsOnCardTap)
        assertFalse(display.hapticsOnListItemTap)
        assertFalse(display.chatBackgroundEnabled)
        assertFalse(display.glassTheme)
        assertFalse(display.usePureBackground)
        assertFalse(display.useLayeredSurfaces)
        assertFalse(display.useLayeredSheetTiles)
        assertEquals(ChatFontFamily.DEFAULT, display.appFontFamily)
        assertEquals(ChatFontFamily.DEFAULT, display.codeFontFamily)
    }

    @Test fun independentOverridesAndEveryNewCustomizationGroupSurviveFullBackups() {
        val style = ChatBubbleStyleOverrides(backgroundArgbLight = 0xFFFFFFFFL, backgroundArgbDark = 0xFF102030L,
            borderArgbLight = 0xFF123456L, borderArgbDark = 0xFF654321L, textArgbLight = 0xFF111111L,
            textArgbDark = 0xFFEFEFEFL, borderWidth = 2.1f, borderOpacity = .4f, cornerRadius = 23f,
            blurSigma = 18f, frostedOpacity = .5f, solidOpacity = .8f)
        val display = DisplaySetting(
            showUserName = false, showUserTimestamp = true, showAssistantTimestamp = false,
            showDateTimeInMessage = true, showProviderInChatMessage = true, useAssistantAvatarInHeader = false,
            showThinkingCards = false, showToolCards = false, collapseThinkingSteps = false,
            userMessageCollapseChars = 750, codeBlockCollapseLines = 33,
            enableDollarLatex = false, enableUserMarkdown = false, enableReasoningMarkdown = false,
            enableAssistantMarkdown = false, regenerateDeleteTrailingMessages = true,
            forkKeepMessageVersions = false, keepThinkingAndToolCardsWhenEditingAssistant = false,
            showAppUpdates = false, keepScreenOnDuringGeneration = false, messageNavButtonsMode = MessageNavButtonsMode.ALWAYS,
            showChatListDate = false, keepSidebarOpenOnAssistantTap = true, keepSidebarOpenOnTopicTap = true,
            keepAssistantListExpandedOnSidebarClose = true, newChatOnAssistantSwitch = false,
            newChatAfterDelete = false, newChatOnLaunch = false, autoScrollIdleSeconds = 12,
            hapticsEnabled = false, hapticsOnDrawer = true, hapticsOnSwitch = true,
            hapticsOnListItemTap = true, hapticsOnCardTap = true,
            chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID,
            userChatBubbleStyleOverrides = style, assistantChatBubbleStyleOverrides = style.copy(cornerRadius = 9f),
            assistantBubbleFitContent = false, assistantBubbleSplitParagraphs = true,
            chatBackgroundEnabled = true, chatBackgroundImagePath = "file:///data/user/0/app/files/upload/wall.jpg",
            chatBackgroundMaskStrength = 1.4f, chatInputBackgroundOpacityLight = .6f, chatInputBackgroundOpacityDark = .3f,
            usePureBackground = true, useLayeredSurfaces = true, useLayeredSheetTiles = true,
            glassTheme = true, glassEconomy = true, glassPreviousMessageStyle = MessageStyleBackup(
                ChatMessageBackgroundStyle.FROSTED, style, style.copy(borderWidth = .2f), true, false),
            appFontFamily = ChatFontFamily.CUSTOM, appCustomFontPath = "fonts/app.ttf", appCustomFontName = "App Face",
            codeFontFamily = ChatFontFamily.CUSTOM, codeCustomFontPath = "fonts/code.ttf", codeCustomFontName = "Code Face",
            appLanguageTag = "zh-Hant",
        )
        val exported = JsonInstant.encodeToString(Settings(displaySetting = display))
        assertTrue(exported.contains("\"chatMessageBackgroundStyle\":\"solid\""))
        assertTrue(exported.contains("\"messageNavButtonsMode\":\"always\""))
        assertEquals(display, restore(exported).displaySetting)
        assertEquals(display, JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(display)))
    }
}
