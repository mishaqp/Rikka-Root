package me.rerere.rikkahub.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode

/** One palette resolver serves the app, its live preview and both sides of color editing. */
fun settingsBaseColorScheme(context: Context, settings: Settings, dark: Boolean): ColorScheme =
    if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId)).getColorScheme(dark)

/** Platform palette resources may change without recreating a forced-light/dark activity. */
@Composable
internal fun rememberSettingsBaseColorScheme(settings: Settings, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var paletteRevision by remember { mutableIntStateOf(0) }
    if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { paletteRevision++ }
    }
    return remember(context, configuration, paletteRevision, settings.dynamicColor,
        settings.themeId, settings.customThemes, dark) {
        settingsBaseColorScheme(context, settings, dark)
    }
}

/** The optional sheet-only role is deliberately outside Material's global ColorScheme. */
data class EffectiveDisplayColors(
    val colorScheme: ColorScheme,
    val sheetTileContainer: Color?,
)

internal val LocalSheetTileContainerColor = compositionLocalOf<Color?> { null }

/** Real sheet tiles consume this role; ordinary cards and app bars never do. */
internal val ColorScheme.sheetTileContainer: Color
    @Composable @ReadOnlyComposable get() = sheetTileContainerOr(surface)

/** Disabled layering restores each component's own default instead of forcing a shared one. */
@Composable
@ReadOnlyComposable
internal fun ColorScheme.sheetTileContainerOr(defaultColor: Color): Color =
    LocalSheetTileContainerColor.current ?: defaultColor

fun effectiveDisplayColors(
    base: ColorScheme,
    dark: Boolean,
    amoled: Boolean,
    display: DisplaySetting,
): EffectiveDisplayColors {
    val pureBackground = display.usePureBackground || (dark && amoled)
    val background = if (dark) Color.Black else Color.White
    val pure = if (pureBackground) base.copy(background = background, surface = background) else base
    val layered = if (display.useLayeredSurfaces) pure.copy(
        background = if (pureBackground) pure.background else pure.surfaceContainerLowest,
        surface = if (pureBackground) pure.surface else pure.surfaceContainerLowest,
        surfaceContainerLow = lerp(pure.surfaceContainerLow, pure.primary, if (dark) .05f else .025f),
        surfaceContainerHigh = lerp(pure.surfaceContainerHigh, pure.primary, if (dark) .08f else .04f),
    ) else pure
    return EffectiveDisplayColors(
        colorScheme = layered,
        sheetTileContainer = if (display.useLayeredSheetTiles) {
            lerp(layered.surfaceContainerHigh, layered.primary, if (dark) .05f else .025f)
        } else {
            null
        },
    )
}

fun effectiveDisplayColorScheme(
    base: ColorScheme,
    dark: Boolean,
    amoled: Boolean,
    display: DisplaySetting,
): ColorScheme = effectiveDisplayColors(base, dark, amoled, display).colorScheme

@Composable
fun effectiveInterfaceColorScheme(settings: Settings, dark: Boolean): ColorScheme {
    val base = rememberSettingsBaseColorScheme(settings, dark)
    val amoled = rememberAmoledDarkMode().value
    return effectiveDisplayColorScheme(base, dark, amoled, settings.displaySetting)
}

