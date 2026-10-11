package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.extendColors

/** Tint a surface rather than using a bright container as an icon background. */
internal fun settingsToneColors(
    scheme: ColorScheme,
    tone: SettingsSectionTone,
    dark: Boolean,
): Pair<Color, Color> {
    val accent = when (tone) {
        SettingsSectionTone.Primary, SettingsSectionTone.Surface -> scheme.primary
        SettingsSectionTone.Secondary, SettingsSectionTone.Outline -> scheme.secondary
        SettingsSectionTone.Tertiary -> scheme.tertiary
    }
    return lerp(scheme.surface, accent, if (dark) .18f else .10f) to accent
}

@Composable
internal fun settingsToneColors(tone: SettingsSectionTone): Pair<Color, Color> =
    settingsToneColors(MaterialTheme.colorScheme, tone, LocalDarkMode.current)

@Composable
internal fun settingsStatusColors(tone: SettingsDashboardStatusTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.extendColors
    return when (tone) {
        SettingsDashboardStatusTone.Success -> semantic.green1 to semantic.green9
        SettingsDashboardStatusTone.Warning -> semantic.orange1 to semantic.orange9
        SettingsDashboardStatusTone.Error -> scheme.errorContainer to scheme.onErrorContainer
        SettingsDashboardStatusTone.Neutral -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
}
