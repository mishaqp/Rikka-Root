package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode

/** One palette resolver serves the app, its live preview and both sides of color editing. */
fun settingsBaseColorScheme(context: Context, settings: Settings, dark: Boolean): ColorScheme =
    if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId)).getColorScheme(dark)

fun effectiveDisplayColorScheme(
    base: ColorScheme,
    dark: Boolean,
    amoled: Boolean,
    display: DisplaySetting,
): ColorScheme {
    val pureBackground = display.usePureBackground || (dark && amoled)
    val background = if (dark) Color.Black else Color.White
    val pure = if (pureBackground) base.copy(background = background, surface = background) else base
    val layered = if (display.useLayeredSurfaces) pure.copy(
        background = if (pureBackground) pure.background else pure.surfaceContainerLowest,
        surface = if (pureBackground) pure.surface else pure.surfaceContainerLowest,
        surfaceContainerLow = lerp(pure.surfaceContainerLow, pure.primary, if (dark) .05f else .025f),
        surfaceContainerHigh = lerp(pure.surfaceContainerHigh, pure.primary, if (dark) .08f else .04f),
    ) else pure
    return if (display.useLayeredSheetTiles) layered.copy(
        surfaceContainer = lerp(layered.surfaceContainerHigh, layered.primary, if (dark) .05f else .025f),
    ) else layered
}

@Composable
fun effectiveInterfaceColorScheme(settings: Settings, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val amoled = rememberAmoledDarkMode().value
    return effectiveDisplayColorScheme(settingsBaseColorScheme(context, settings, dark), dark, amoled, settings.displaySetting)
}
