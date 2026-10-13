package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

class InterfacePageState(
    val settings: Settings,
    val updateSettings: ((Settings) -> Settings) -> Unit,
    val navigate: (Screen) -> Unit,
) {
    fun updateDisplaySetting(transform: (DisplaySetting) -> DisplaySetting) =
        updateSettings { it.copy(displaySetting = transform(it.displaySetting)) }
}

@Composable
internal fun rememberInterfacePageState(vm: SettingVM = koinViewModel()): InterfacePageState {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    return InterfacePageState(settings, { vm.updateSettings(it) }, { nav.navigate(it) })
}

enum class InterfaceSettingsPageId(@StringRes val titleRes: Int) {
    Hub(R.string.ui_interface),
    Messages(R.string.ui_display_settings_page_chat_item_display_title),
    Rendering(R.string.ui_display_settings_page_rendering_settings_title),
    Behavior(R.string.ui_display_settings_page_behavior_startup_title),
    Haptics(R.string.ui_display_settings_page_haptics_settings_title),
    Images(R.string.ui_image_settings_page_title),
    AutoRetry(R.string.ui_auto_retry_title),
    FontSize(R.string.ui_display_settings_page_chat_font_size_title),
    Scroll(R.string.ui_display_settings_page_auto_scroll_idle_title),
    InputOpacity(R.string.ui_display_settings_page_chat_input_background_opacity_title),
    FontsApp(R.string.ui_display_settings_page_app_font_title),
    FontsCode(R.string.ui_display_settings_page_code_font_title),
    FontsChat(R.string.ui_fonts_chat),
    Background(R.string.ui_background),
    MessageStyle(R.string.ui_message_style_settings_page_title),
    Glass(R.string.ui_glass_theme_title),
    Language(R.string.ui_display_settings_page_language_title),
    ThemeAdvanced(R.string.ui_theme_advanced),
    Themes(R.string.ui_display_settings_page_theme_settings_title),
    GoogleFonts(R.string.ui_google_fonts_title),
}

/** The production routes and offline screenshots render the same state and callbacks. */
@Composable
fun InterfaceSettingsContent(
    page: InterfaceSettingsPageId,
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onNavigate: (Screen) -> Unit = {},
    googleFontsCatalog: List<GoogleFontEntry> = emptyList(),
) {
    val state = InterfacePageState(settings, onUpdateSettings, onNavigate)
    when (page) {
        InterfaceSettingsPageId.Hub -> SettingInterfacePage(state)
        InterfaceSettingsPageId.Messages -> SettingInterfaceMessagesPage(state)
        InterfaceSettingsPageId.Rendering -> SettingInterfaceRenderingPage(state)
        InterfaceSettingsPageId.Behavior -> SettingInterfaceBehaviorPage(state)
        InterfaceSettingsPageId.Haptics -> SettingInterfaceHapticsPage(state)
        InterfaceSettingsPageId.Images -> SettingInterfaceImagesPage(state)
        InterfaceSettingsPageId.AutoRetry -> SettingInterfaceAutoRetryPage(state)
        InterfaceSettingsPageId.FontSize -> SettingInterfaceFontSizePage(state)
        InterfaceSettingsPageId.Scroll -> SettingInterfaceScrollPage(state)
        InterfaceSettingsPageId.InputOpacity -> SettingInterfaceInputOpacityPage(state)
        InterfaceSettingsPageId.FontsApp -> SettingInterfaceFontsPage("app", state)
        InterfaceSettingsPageId.FontsCode -> SettingInterfaceFontsPage("code", state)
        InterfaceSettingsPageId.FontsChat -> SettingInterfaceFontsPage("chat", state)
        InterfaceSettingsPageId.Background -> SettingInterfaceBackgroundPage(state)
        InterfaceSettingsPageId.MessageStyle -> SettingInterfaceMessageStylePage(state)
        InterfaceSettingsPageId.Glass -> SettingInterfaceGlassPage(state)
        InterfaceSettingsPageId.Language -> SettingInterfaceLanguagePage(state)
        InterfaceSettingsPageId.ThemeAdvanced -> SettingInterfaceThemeAdvancedPage(state)
        InterfaceSettingsPageId.Themes -> SettingThemePage(state, showLargeHeading = false)
        InterfaceSettingsPageId.GoogleFonts -> GoogleFontsPickerContent(googleFontsCatalog)
    }
}
