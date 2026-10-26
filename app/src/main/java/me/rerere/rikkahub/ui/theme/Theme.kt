package me.rerere.rikkahub.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.hooks.ProvideAppHaptics

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }
val LocalAmoledDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = rememberSettingsBaseColorScheme(settings, darkTheme)
    // 更新状态栏图标颜色
    val view = LocalView.current
    val activity = view.context.getActivity()
    // 浮窗可能使用 Application Context，没有可更新系统栏的 Activity。
    if (!view.isInEditMode && activity != null) {
        DisposableEffect(view, activity, darkTheme) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    val appFont = rememberAppFontFamily(settings.displaySetting)
    val typography = remember(appFont) { Typography.withFontFamily(appFont) }
    ProvideAppHaptics(settings.displaySetting) {
        RikkahubThemeContent(colorScheme, darkTheme, amoledDarkMode, content = content,
            displaySetting = settings.displaySetting, typography = typography)
    }
}

/** Shared production theme rendering; its host resolves preferences and platform colors. */
@Composable
internal fun RikkahubThemeContent(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    amoledDarkMode: Boolean = false,
    content: @Composable () -> Unit,
    displaySetting: DisplaySetting = DisplaySetting(),
    typography: androidx.compose.material3.Typography = Typography,
) {
    val displayColors = remember(darkTheme, amoledDarkMode, colorScheme, displaySetting.usePureBackground,
        displaySetting.useLayeredSurfaces, displaySetting.useLayeredSheetTiles) {
        effectiveDisplayColors(colorScheme, darkTheme, amoledDarkMode, displaySetting)
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors
    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalAmoledDarkMode provides (darkTheme && amoledDarkMode),
        LocalExtendColors provides extendColors,
        LocalSheetTileContainerColor provides displayColors.sheetTileContainer,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = displayColors.colorScheme,
            typography = typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current

