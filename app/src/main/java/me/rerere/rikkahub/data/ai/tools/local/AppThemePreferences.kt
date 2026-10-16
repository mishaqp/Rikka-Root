package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.effectiveDisplayColorScheme
import me.rerere.rikkahub.ui.theme.settingsBaseColorScheme

internal interface AppThemePreferences {
    fun colorMode(): ColorMode
    fun amoledDark(): Boolean
    fun compareAndSet(key: String, expected: JsonElement, value: JsonElement): Boolean
    fun glassAccents(settings: Settings): Pair<Long, Long>
}

/** These are the same two keys consumed by rememberColorMode/rememberAmoledDarkMode. */
internal fun appThemePreferences(context: Context): AppThemePreferences = object : AppThemePreferences {
    private val preferences by lazy { context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE) }

    override fun colorMode(): ColorMode = ColorMode.entries.firstOrNull {
        it.name == preferences.getString("colorMode", ColorMode.SYSTEM.name)
    } ?: ColorMode.SYSTEM

    override fun amoledDark(): Boolean = preferences.getBoolean("amoledDark", false)

    override fun compareAndSet(key: String, expected: JsonElement, value: JsonElement): Boolean = synchronized(preferences) {
        // Do not accept an arbitrary preference name, even from another caller in the app.
        val editor = when (key) {
            "theme.colorMode" -> {
                require(value is JsonPrimitive && value.isString && ColorMode.entries.any { it.name == value.content }) { APP_SETTINGS_MANUAL_ONLY }
                if (JsonPrimitive(colorMode().name) != expected) return@synchronized false
                preferences.edit().putString("colorMode", value.content)
            }
            "theme.amoledDark" -> {
                require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { APP_SETTINGS_MANUAL_ONLY }
                if (JsonPrimitive(amoledDark()) != expected) return@synchronized false
                preferences.edit().putBoolean("amoledDark", value.booleanOrNull!!)
            }
            else -> error(APP_SETTINGS_MANUAL_ONLY)
        }
        check(editor.commit()) { "Не удалось сохранить оформление приложения." }
        true
    }

    override fun glassAccents(settings: Settings): Pair<Long, Long> {
        // This is the non-composable resolver used by effectiveInterfaceColorScheme itself.
        fun primary(dark: Boolean): Long = effectiveDisplayColorScheme(
            settingsBaseColorScheme(context, settings, dark), dark, amoledDark(), settings.displaySetting,
        ).primary.toArgb().toLong() and 0xFFFFFFFFL
        return primary(false) to primary(true)
    }
}
