package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.findThemeById
import me.rerere.rikkahub.ui.theme.effectiveInterfaceColorScheme
import me.rerere.ui.components.Select

@Composable
fun SettingInterfaceThemeAdvancedPage(state: InterfacePageState = rememberInterfacePageState()) {
    val display = state.settings.displaySetting
    InterfaceSettingsScaffold(R.string.ui_theme_advanced) {
        item { InterfaceSettingsCard {
            InterfaceSwitchRow(R.string.ui_theme_advanced_settings_page_use_layered_surfaces_title, display.useLayeredSurfaces,
                "useLayeredSurfaces", R.string.ui_theme_advanced_settings_page_use_layered_surfaces_subtitle) { value -> state.updateDisplaySetting { it.copy(useLayeredSurfaces = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_theme_advanced_settings_page_use_layered_sheet_tiles_title, display.useLayeredSheetTiles,
                "useLayeredSheetTiles", R.string.ui_theme_advanced_settings_page_use_layered_sheet_tiles_subtitle) { value -> state.updateDisplaySetting { it.copy(useLayeredSheetTiles = value) } }
        } }
    }
}

@Composable
fun SettingInterfaceGlassPage(state: InterfacePageState = rememberInterfacePageState()) {
    val display = state.settings.displaySetting
    val primaryLight = effectiveInterfaceColorScheme(state.settings, false).primary
    val primaryDark = effectiveInterfaceColorScheme(state.settings, true).primary
    InterfaceSettingsScaffold(R.string.ui_glass_theme_title) {
        item { InterfaceSettingsCard {
            InterfaceSwitchRow(R.string.ui_glass_theme_enable, display.glassTheme, "glassTheme", R.string.ui_glass_theme_enable_detail) { enabled ->
                state.updateDisplaySetting { current -> current.withGlassTheme(enabled, primaryLight.toArgb().toLong() and 0xFFFFFFFFL,
                    primaryDark.toArgb().toLong() and 0xFFFFFFFFL) }
            }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_glass_economy_title, display.glassEconomy, "glassEconomy", R.string.ui_glass_economy_detail) { value -> state.updateDisplaySetting { it.copy(glassEconomy = value) } }
        } }
        item { InterfaceFooter(R.string.ui_glass_theme_footer) }
        item { MessageStylePreview(display, settings = state.settings, emphasizeEditedRole = false) }
    }
}

/** A reversible preset retains the user's exact message-style choices across app restarts. */
internal fun DisplaySetting.withGlassTheme(enabled: Boolean, accentLight: Long, accentDark: Long): DisplaySetting {
    if (enabled == glassTheme) return this
    if (!enabled) {
        val previous = glassPreviousMessageStyle
        if (previous == null) return copy(glassTheme = false, glassPreviousMessageStyle = null)
        return copy(glassTheme = false, glassPreviousMessageStyle = null,
            chatMessageBackgroundStyle = previous?.style ?: ChatMessageBackgroundStyle.DEFAULT,
            userChatBubbleStyleOverrides = previous?.user ?: ChatBubbleStyleOverrides(),
            assistantChatBubbleStyleOverrides = previous?.assistant ?: ChatBubbleStyleOverrides(),
            assistantBubbleFitContent = previous?.assistantBubbleFitContent ?: true,
            assistantBubbleSplitParagraphs = previous?.assistantBubbleSplitParagraphs ?: false)
    }
    return copy(glassTheme = true,
        glassPreviousMessageStyle = MessageStyleBackup(chatMessageBackgroundStyle, userChatBubbleStyleOverrides,
            assistantChatBubbleStyleOverrides, assistantBubbleFitContent, assistantBubbleSplitParagraphs),
        chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED,
        userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(backgroundArgbLight = accentLight,
            backgroundArgbDark = accentDark, textArgbLight = 0xFFFFFFFFL, textArgbDark = 0xFFFFFFFFL,
            borderArgbLight = 0xFFFFFFFFL, borderArgbDark = 0xFFFFFFFFL,
            frostedOpacity = .72f, borderOpacity = .28f, blurSigma = assistantChatBubbleStyleOverrides.blurSigma),
        assistantChatBubbleStyleOverrides = ChatBubbleStyleOverrides(frostedOpacity = .34f, borderOpacity = .2f,
            borderArgbLight = 0xFFFFFFFFL, borderArgbDark = 0xFFFFFFFFL, blurSigma = assistantChatBubbleStyleOverrides.blurSigma))
}

@Composable
fun SettingInterfaceLanguagePage(state: InterfacePageState = rememberInterfacePageState()) {
    val current = state.settings.displaySetting.appLanguageTag
        ?: me.rerere.rikkahub.ui.locale.currentInterfaceAppLanguage(LocalContext.current)
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_language_title) {
        item { InterfaceSettingsCard {
            listOf("" to R.string.ui_language_system, "ru" to R.string.ui_moru_language_russian,
                "zh-CN" to R.string.ui_language_chinese, "zh-Hant" to R.string.ui_language_chinese_traditional,
                "en" to R.string.ui_language_english).forEachIndexed { index, (tag, title) ->
                if (index > 0) InterfaceDivider()
                InterfaceNavigationRow(title, detail = if (current == tag) stringResource(R.string.ui_enabled) else null) {
                    state.updateDisplaySetting { it.copy(appLanguageTag = tag) }
                }
            }
        } }
    }
}

@Composable
internal fun interfaceLanguageLabel(tag: String?): String {
    val selected = tag ?: me.rerere.rikkahub.ui.locale.currentInterfaceAppLanguage(LocalContext.current)
    val label = when {
        selected.isBlank() -> R.string.ui_language_system
        selected.startsWith("ru") -> R.string.ui_moru_language_russian
        selected.startsWith("zh-Hant") || selected.startsWith("zh-TW") -> R.string.ui_language_chinese_traditional
        selected.startsWith("zh") -> R.string.ui_language_chinese
        selected.startsWith("en") -> R.string.ui_language_english
        else -> null
    }
    return label?.let { stringResource(it) }
        ?: java.util.Locale.forLanguageTag(selected).getDisplayName(java.util.Locale.forLanguageTag("ru"))
}

@Composable
internal fun InterfaceThemeMainControls(state: InterfacePageState) {
    var colorMode by rememberColorMode()
    var amoled by rememberAmoledDarkMode()
    InterfaceSettingsCard {
        Text(stringResource(R.string.ui_theme_color_mode), Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        Select(ColorMode.entries, colorMode, { colorMode = it }, Modifier.padding(horizontal = 14.dp), optionToString = {
            stringResource(when (it) { ColorMode.SYSTEM -> R.string.ui_settings_page_system_mode
                ColorMode.LIGHT -> R.string.ui_settings_page_light_mode; ColorMode.DARK -> R.string.ui_settings_page_dark_mode })
        })
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_theme_settings_page_use_dynamic_color_title, state.settings.dynamicColor,
                "dynamicColor", R.string.ui_theme_settings_page_use_dynamic_color_subtitle) { value -> state.updateSettings { it.copy(dynamicColor = value) } }
        }
        InterfaceDivider()
        InterfaceSwitchRow(R.string.ui_theme_pure, state.settings.displaySetting.usePureBackground,
            "usePureBackground", R.string.ui_theme_pure_description) { value -> state.updateDisplaySetting { it.copy(usePureBackground = value) } }
        InterfaceDivider()
        InterfaceSwitchRow(R.string.ui_theme_amoled, amoled, "amoled") { amoled = it }
    }
}
