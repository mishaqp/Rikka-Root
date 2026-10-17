package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.ChatBubbleStyleOverrides
import me.rerere.rikkahub.data.datastore.ChatMessageBackgroundStyle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class AppThemePreferencesTest {
    @Test fun `glass preset approval discloses appearance changes in both directions`() = runBlocking {
        val theme = appThemePreferences(RuntimeEnvironment.getApplication())
        var settings = Settings(dynamicColor = false, displaySetting = DisplaySetting(
            chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID,
            userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(solidOpacity = .4f),
        ))
        val engine = AppSettingsEngine({ settings }, { settings = it(settings); settings }, theme)
        for (enabled in listOf(true, false)) {
            val preview = engine.previewSet("theme.glassTheme", JsonPrimitive(enabled))
            val changes = preview.toJson()["changes"] as? JsonArray
            assertNotNull("Glass preset must disclose its full side effects", changes)
            val keys = changes!!.map { it.jsonObject["key"]!!.jsonPrimitive.content }
            assertTrue(keys.contains("display.chatMessageBackgroundStyle"))
            assertTrue(keys.any { it.startsWith("display.userChatBubbleStyleOverrides") })
            assertTrue("Actual appearance changes must be visible in approval", preview.description.contains("Стиль сообщений"))
            assertEquals("ok", engine.applyApproved(preview)["status"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `glass approval does not overwrite an appearance edited after preview`() = runBlocking {
        val theme = appThemePreferences(RuntimeEnvironment.getApplication())
        var settings = Settings(dynamicColor = false, displaySetting = DisplaySetting(
            chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID,
        ))
        var writes = 0
        val engine = AppSettingsEngine({ settings }, { settings = it(settings); writes++; settings }, theme)
        val preview = engine.previewSet("theme.glassTheme", JsonPrimitive(true))
        settings = settings.copy(displaySetting = settings.displaySetting.copy(
            chatMessageBackgroundStyle = ChatMessageBackgroundStyle.DEFAULT,
        ))
        assertEquals("stale_preview", engine.applyApproved(preview)["code"]?.jsonPrimitive?.content)
        assertEquals(0, writes)
        assertFalse(settings.displaySetting.glassTheme)
    }

    @Test fun `dark mode and AMOLED use original preferences and preserve unrelated keys`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        original.edit().clear().putString("colorMode", "LIGHT").putBoolean("amoledDark", false)
            .putString("unrelated", "keep").commit()
        val theme = appThemePreferences(context)
        val settings = Settings(dynamicColor = false)
        val engine = AppSettingsEngine({ settings }, { error("theme preference wrote SettingsStore") }, theme)
        val preview = previewAppSettingsChange(settings, "theme.colorMode", JsonPrimitive("DARK"), theme)
        assertEquals(JsonPrimitive("LIGHT"), preview.before)
        assertTrue(preview.description.contains("Светлая"))
        assertTrue(preview.description.contains("Тёмная"))
        assertEquals("ok", engine.set("theme.colorMode", JsonPrimitive("DARK"), preview.before)["status"]!!.jsonPrimitive.content)
        assertEquals("DARK", original.getString("colorMode", null))
        assertEquals(ColorMode.DARK, theme.colorMode())
        assertEquals("ok", engine.set("theme.amoledDark", JsonPrimitive(true), JsonPrimitive(false))["status"]!!.jsonPrimitive.content)
        assertTrue(original.getBoolean("amoledDark", false))
        assertEquals("keep", original.getString("unrelated", null))
        assertEquals(setOf("colorMode", "amoledDark", "unrelated"), original.all.keys)
        assertEquals("invalid_choice", engine.set("theme.colorMode", JsonPrimitive("injected"), JsonPrimitive("DARK"))["code"]!!.jsonPrimitive.content)
        assertEquals("ok", engine.set("theme.colorMode", JsonPrimitive("SYSTEM"), JsonPrimitive("DARK"))["status"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", original.getString("colorMode", null))
        val exposed = engine.get(section = "theme").toString()
        assertTrue(exposed.contains("theme.colorMode"))
        assertTrue(exposed.contains("theme.amoledDark"))
        assertTrue(exposed.contains("theme.glassTheme"))
    }

    @Test fun `live preference edit after approval rejects stale dark mode and protected key writes`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
        original.edit().clear().putString("colorMode", "SYSTEM").commit()
        val theme = appThemePreferences(context)
        val settings = Settings(dynamicColor = false)
        val engine = AppSettingsEngine({ settings }, { error("theme preference wrote SettingsStore") }, theme)
        val preview = previewAppSettingsChange(settings, "theme.colorMode", JsonPrimitive("DARK"), theme)
        context.writeStringPreference("colorMode", "LIGHT")
        val result = engine.set("theme.colorMode", JsonPrimitive("DARK"), preview.before)
        assertEquals("stale_preview", result["code"]!!.jsonPrimitive.content)
        assertEquals("LIGHT", original.getString("colorMode", null))
        assertFalse(theme.compareAndSet("theme.colorMode", JsonPrimitive("SYSTEM"), JsonPrimitive("DARK")))
        assertFalse(theme.compareAndSet("theme.amoledDark", JsonPrimitive(true), JsonPrimitive(false)))
        assertTrue(runCatching { theme.compareAndSet("toolPermissions", JsonPrimitive(false), JsonPrimitive(true)) }.isFailure)
        assertFalse(original.contains("toolPermissions"))
    }

    @Test fun `glass mode uses reversible UI style preset and restores exact original appearance`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val theme = appThemePreferences(context)
        val original = DisplaySetting(
            chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID,
            userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(solidOpacity = .4f, cornerRadius = 23f),
            assistantChatBubbleStyleOverrides = ChatBubbleStyleOverrides(borderWidth = 2.5f, blurSigma = 12f),
            assistantBubbleFitContent = false,
            assistantBubbleSplitParagraphs = true,
        )
        var settings = Settings(dynamicColor = false, displaySetting = original)
        val engine = AppSettingsEngine({ settings }, { settings = it(settings); settings }, theme)
        val accents = theme.glassAccents(settings)
        assertEquals("ok", engine.set("theme.glassTheme", JsonPrimitive(true), JsonPrimitive(false))["status"]!!.jsonPrimitive.content)
        assertTrue(settings.displaySetting.glassTheme)
        assertEquals(ChatMessageBackgroundStyle.FROSTED, settings.displaySetting.chatMessageBackgroundStyle)
        assertEquals(accents.first, settings.displaySetting.userChatBubbleStyleOverrides.backgroundArgbLight)
        assertEquals(accents.second, settings.displaySetting.userChatBubbleStyleOverrides.backgroundArgbDark)
        assertEquals(original.userChatBubbleStyleOverrides, settings.displaySetting.glassPreviousMessageStyle!!.user)
        assertEquals("ok", engine.set("theme.glassTheme", JsonPrimitive(false), JsonPrimitive(true))["status"]!!.jsonPrimitive.content)
        assertEquals(original, settings.displaySetting)
    }
}
