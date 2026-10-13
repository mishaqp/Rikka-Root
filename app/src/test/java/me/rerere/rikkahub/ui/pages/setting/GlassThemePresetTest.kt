package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class GlassThemePresetTest {
    @Test fun `glass can restore the exact previous message style after an app restart or backup import`() {
        val original = DisplaySetting(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID,
            assistantBubbleFitContent = false, assistantBubbleSplitParagraphs = true,
            userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(backgroundArgbLight = 0xFF445566L, solidOpacity = .4f),
            assistantChatBubbleStyleOverrides = ChatBubbleStyleOverrides(borderWidth = 2f, cornerRadius = 22f))
        val enabled = original.withGlassTheme(true, 0xFF0066AAL, 0xFF77AADDL)
        assertTrue(enabled.glassTheme)
        assertEquals(ChatMessageBackgroundStyle.FROSTED, enabled.chatMessageBackgroundStyle)
        assertEquals(.72f, enabled.userChatBubbleStyleOverrides.frostedOpacity)
        assertEquals(.34f, enabled.assistantChatBubbleStyleOverrides.frostedOpacity)
        assertEquals(enabled, enabled.withGlassTheme(true, 0xFF000000L, 0xFFFFFFFFL))
        val imported = JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(enabled))
        assertEquals(original, imported.withGlassTheme(false, 0, 0))
    }

    @Test fun `a missing restoration record preserves editable current style`() {
        val current = DisplaySetting(glassTheme = true, chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED,
            assistantChatBubbleStyleOverrides = ChatBubbleStyleOverrides(cornerRadius = 7f))
        assertEquals(current.copy(glassTheme = false), current.withGlassTheme(false, 0, 0))
    }
}
