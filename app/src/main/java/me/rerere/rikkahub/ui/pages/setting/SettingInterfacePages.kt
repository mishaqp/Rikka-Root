package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.findThemeById
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingInterfacePage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    val display = settings.displaySetting

    val enabled = stringResource(R.string.ui_enabled)
    val disabled = stringResource(R.string.ui_disabled)
    InterfaceSettingsScaffold(R.string.ui_interface) {
        item {
            InterfaceSettingsCard {
                InterfaceNavigationRow(R.string.ui_display_settings_page_theme_settings_title, HugeIcons.PaintBoard,
                    detailContent = { findThemeById(settings.themeId, settings.customThemes)?.name?.invoke() }) { state.navigate(Screen.SettingTheme) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_glass_theme_title, HugeIcons.AiMagic,
                    if (display.glassTheme) enabled else disabled) { state.navigate(Screen.SettingInterfaceGlass) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_language_title, HugeIcons.Globe02,
                    interfaceLanguageLabel(display.appLanguageTag)) { state.navigate(Screen.SettingInterfaceLanguage) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_chat_item_display_title, HugeIcons.Message01) { state.navigate(Screen.SettingInterfaceMessages) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_rendering_settings_title, HugeIcons.Book01) { state.navigate(Screen.SettingInterfaceRendering) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_behavior_startup_title, HugeIcons.Settings03) { state.navigate(Screen.SettingInterfaceBehavior) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_image_settings_page_title, HugeIcons.Image02) { state.navigate(Screen.SettingInterfaceImages) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_message_style_settings_page_title, HugeIcons.PaintBoard) { state.navigate(Screen.SettingInterfaceMessageStyle) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_settings_page_auto_retry, HugeIcons.Refresh03) { state.navigate(Screen.SettingInterfaceAutoRetry) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_haptics_settings_title, HugeIcons.SmartPhone01) { state.navigate(Screen.SettingInterfaceHaptics) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_background_settings_title, HugeIcons.Notification01) { state.navigate(Screen.SettingPreferencesNotification) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_background, HugeIcons.Image02) { state.navigate(Screen.SettingInterfaceBackground) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_app_font_title, HugeIcons.Book03,
                    fontChoiceLabel(display.appFontFamily, display.appCustomFontName)) { state.navigate(Screen.SettingInterfaceFonts("app")) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_code_font_title, HugeIcons.ComputerTerminal01,
                    fontChoiceLabel(display.codeFontFamily, display.codeCustomFontName)) { state.navigate(Screen.SettingInterfaceFonts("code")) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_fonts_chat, HugeIcons.Book03,
                    fontChoiceLabel(display.chatFontFamily, display.chatCustomFontName)) { state.navigate(Screen.SettingInterfaceFonts("chat")) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_chat_font_size_title,
                    detail = stringResource(R.string.ui_percent, (display.fontSizeRatio * 100).toInt())) { state.navigate(Screen.SettingInterfaceFontSize) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_auto_scroll_idle_title,
                    detail = if (display.enableAutoScroll) stringResource(R.string.ui_seconds, display.autoScrollIdleSeconds) else disabled) { state.navigate(Screen.SettingInterfaceScroll) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_chat_background_mask_title,
                    detail = stringResource(R.string.ui_percent, (display.chatBackgroundMaskStrength * 100).toInt())) { state.navigate(Screen.SettingInterfaceBackground) }
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_chat_input_background_opacity_title,
                    detail = stringResource(R.string.ui_percent, (display.chatInputBackgroundOpacityLight * 100).toInt())) { state.navigate(Screen.SettingInterfaceInputOpacity) }
            }
        }
        item { InterfaceSettingsCard {
            InterfaceNavigationRow(R.string.ui_notifications, HugeIcons.Notification01) { state.navigate(Screen.SettingPreferencesNotification) }
            InterfaceDivider()
            InterfaceNavigationRow(R.string.ui_network, HugeIcons.Internet) { state.navigate(Screen.SettingPreferencesNetwork) }

        } }
    }
}

@Composable
internal fun InterfaceToggleList(
    controls: List<InterfaceToggle>,
    display: DisplaySetting,
    onUpdate: ((DisplaySetting) -> DisplaySetting) -> Unit,
    after: @Composable (InterfaceToggle) -> Unit = {},
) {
    InterfaceSettingsCard {
        controls.forEachIndexed { index, row ->
            if (index > 0) InterfaceDivider()
            InterfaceSwitchRow(row.titleRes, row.read(display), row.key) { value -> onUpdate { row.write(it, value) } }
            after(row)
        }
    }
}

@Composable
fun SettingInterfaceMessagesPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_chat_item_display_title) {
        item { InterfaceToggleList(InterfaceControls.messages, settings.displaySetting, { state.updateDisplaySetting(it) }) }
        item { InterfaceProfileEditor(state) }
    }
}

@Composable
fun SettingInterfaceRenderingPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    val display = settings.displaySetting
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_rendering_settings_title) {
        item { InterfaceToggleList(InterfaceControls.rendering, display, { state.updateDisplaySetting(it) }) { row ->
            if (row.key == "codeBlockAutoCollapse" && display.codeBlockAutoCollapse) {
                InterfaceDivider()
                InterfaceNumberRow(R.string.ui_display_settings_page_auto_collapse_code_block_lines_title,
                    display.codeBlockCollapseLines, 1..999, R.string.ui_display_settings_page_auto_collapse_code_block_lines_unit) { value ->
                    state.updateDisplaySetting { it.copy(codeBlockCollapseLines = value) }
                }
            }
        } }
        item { InterfaceSettingsCard {
            InterfaceSwitchRow(R.string.ui_line_numbers, display.showLineNumbers, "showLineNumbers") { value -> state.updateDisplaySetting { it.copy(showLineNumbers = value) } }
        } }
    }
}

@Composable
fun SettingInterfaceBehaviorPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    val locale = LocalResources.current.configuration.locales[0]
    val display = settings.displaySetting
    val legacyLaunch by rememberSharedPreferenceBoolean("create_new_conversation_on_start", true)
    var showNavMode by remember { mutableStateOf(false) }
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_behavior_startup_title) {
        item { InterfaceToggleList(InterfaceControls.behavior, display, { state.updateDisplaySetting(it) }) { row ->
            if (row.key == "collapseLongUserMessages" && display.collapseLongUserMessages) {
                InterfaceDivider()
                InterfaceNumberRow(R.string.ui_display_settings_page_collapse_long_user_messages_chars_title,
                    display.userMessageCollapseChars ?: 500, 50..100000, R.string.ui_display_settings_page_collapse_long_user_messages_chars_unit,
                    emptyUntilEdited = display.userMessageCollapseChars == null) { value ->
                    state.updateDisplaySetting { it.copy(userMessageCollapseChars = value) }
                }
                if (display.userMessageCollapseChars == null) InterfaceFooter(R.string.ui_collapse_current_lines_hint, display.userMessageCollapseLines)
                InterfaceSwitchRow(R.string.ui_collapse_use_lines, display.userMessageCollapseChars == null,
                    "userMessageCollapseMode") { value -> state.updateDisplaySetting { it.copy(userMessageCollapseChars = if (value) null else 500) } }
                if (display.userMessageCollapseChars == null) InterfaceNumberRow(R.string.ui_legacy_lines,
                    display.userMessageCollapseLines, 3..50) { value -> state.updateDisplaySetting { it.copy(userMessageCollapseLines = value) } }
            }
            if (row.key == "keepScreenOnDuringGeneration") {
                InterfaceDivider()
                InterfaceNavigationRow(R.string.ui_display_settings_page_message_nav_buttons_title,
                    detail = messageNavModeLabel(if (display.showMessageJumper) display.messageNavButtonsMode else MessageNavButtonsMode.NEVER)) { showNavMode = true }
            }
            if (row.key == "keepAssistantListExpandedOnSidebarClose") {
                InterfaceDivider()
                InterfaceSwitchRow(R.string.ui_display_settings_page_new_chat_on_assistant_switch_title,
                    display.newChatOnAssistantSwitch ?: legacyLaunch, "newChatOnAssistantSwitch") { value -> state.updateDisplaySetting { it.copy(newChatOnAssistantSwitch = value) } }
            }
            if (row.key == "newChatAfterDelete") {
                InterfaceDivider()
                InterfaceSwitchRow(R.string.ui_display_settings_page_new_chat_on_launch_title,
                    display.newChatOnLaunch ?: legacyLaunch, "newChatOnLaunch") { value -> state.updateDisplaySetting { it.copy(newChatOnLaunch = value) } }
            }
            if (row.key == "pasteLongTextAsFile" && display.pasteLongTextAsFile) {
                InterfaceDivider()
                InterfaceNumberRow(R.string.ui_display_settings_page_long_paste_as_file_threshold_title,
                    display.pasteLongTextThreshold, 100..1000000, R.string.ui_display_settings_page_long_paste_as_file_threshold_unit) { value ->
                    state.updateDisplaySetting { it.copy(pasteLongTextThreshold = value) }
                }
            }
        } }
        item { InterfaceSettingsCard {
            Text(stringResource(R.string.ui_conversation_order), Modifier.padding(14.dp))
            me.rerere.ui.components.Select(ConversationSortOrder.entries, display.conversationSortOrder,
                { value -> state.updateDisplaySetting { it.copy(conversationSortOrder = value) } }, Modifier.padding(horizontal = 14.dp),
                optionToString = { stringResource(if (it == ConversationSortOrder.UPDATE_TIME) R.string.ui_conversation_order_updated else R.string.ui_conversation_order_created) })
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_auto_collapse_tool_steps, display.autoCollapseToolSteps, "autoCollapseToolSteps") { value -> state.updateDisplaySetting { it.copy(autoCollapseToolSteps = value, collapseThinkingSteps = null) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_auto_titles, display.enableAutoChatTitles, "enableAutoChatTitles") { value -> state.updateDisplaySetting { it.copy(enableAutoChatTitles = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_nav_left, display.messageJumperOnLeft, "messageJumperOnLeft") { value -> state.updateDisplaySetting { it.copy(messageJumperOnLeft = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_volume_scroll, display.enableVolumeKeyScroll, "enableVolumeKeyScroll") { value -> state.updateDisplaySetting { it.copy(enableVolumeKeyScroll = value) } }
            if (display.enableVolumeKeyScroll) InterfaceSliderRow(R.string.ui_volume_scroll_speed, display.volumeKeyScrollRatio, .1f..2f) { value -> state.updateDisplaySetting { it.copy(volumeKeyScrollRatio = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_loading_icon, display.useAppIconStyleLoadingIndicator, "useAppIconStyleLoadingIndicator") { value -> state.updateDisplaySetting { it.copy(useAppIconStyleLoadingIndicator = value) } }
        } }
        item { InterfaceSettingsCard {
            InterfaceSliderRow(R.string.ui_tts_speed, settings.defaultTTSPlaybackSpeed, .5f..2f,
                valueText = String.format(locale, "%.1f×", settings.defaultTTSPlaybackSpeed), steps = 14) { value -> state.updateSettings { it.copy(defaultTTSPlaybackSpeed = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_tts_quotes, display.ttsOnlyReadQuoted, "ttsOnlyReadQuoted") { value -> state.updateDisplaySetting { it.copy(ttsOnlyReadQuoted = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_tts_outside_brackets, display.ttsOnlyReadOutsideBrackets, "ttsOnlyReadOutsideBrackets") { value -> state.updateDisplaySetting { it.copy(ttsOnlyReadOutsideBrackets = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_tts_auto, display.autoPlayTTSAfterGeneration, "autoPlayTTSAfterGeneration") { value -> state.updateDisplaySetting { it.copy(autoPlayTTSAfterGeneration = value) } }
        } }
    }
    if (showNavMode) AlertDialog(onDismissRequest = { showNavMode = false }, title = { Text(stringResource(R.string.ui_display_settings_page_message_nav_buttons_title)) },
        text = { Column { MessageNavButtonsMode.entries.forEach { mode ->
            TextButton(onClick = { state.updateDisplaySetting { it.copy(messageNavButtonsMode = mode, showMessageJumper = mode != MessageNavButtonsMode.NEVER) }; showNavMode = false }) { Text(messageNavModeLabel(mode)) }
        } } }, confirmButton = {})
}

@Composable
fun SettingInterfaceHapticsPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_haptics_settings_title) {
        item { InterfaceToggleList(InterfaceControls.haptics, settings.displaySetting, { state.updateDisplaySetting(it) }) }
    }
}

@Composable
fun SettingInterfaceImagesPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    InterfaceSettingsScaffold(R.string.ui_image_settings_page_title) {
        item { InterfaceSettingsCard { InterfaceSwitchRow(R.string.ui_image_crop, !settings.displaySetting.skipCropImage,
            "skipCropImage", description = R.string.ui_image_crop_description) { value -> state.updateDisplaySetting { it.copy(skipCropImage = !value) } } } }
    }
}

@Composable
fun SettingInterfaceAutoRetryPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    InterfaceSettingsScaffold(R.string.ui_auto_retry_title) {
        item { InterfaceSettingsCard { InterfaceSwitchRow(R.string.ui_auto_retry_title, settings.networkSetting.enableAutoRetry,
            "enableAutoRetry", description = R.string.ui_auto_retry_description) { value -> state.updateSettings { it.copy(networkSetting = it.networkSetting.copy(enableAutoRetry = value)) } } } }
    }
}

@Composable
fun SettingInterfaceFontSizePage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_chat_font_size_title) {
        item { InterfaceSettingsCard { InterfaceSliderRow(R.string.ui_display_settings_page_chat_font_size_title, settings.displaySetting.fontSizeRatio, .5f..2f) { value -> state.updateDisplaySetting { it.copy(fontSizeRatio = value) } }
            Text(stringResource(R.string.ui_font_sample), Modifier.padding(14.dp), fontSize = MaterialTheme.typography.bodyLarge.fontSize * settings.displaySetting.fontSizeRatio) } }
    }
}

@Composable
fun SettingInterfaceScrollPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    val display = settings.displaySetting
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_auto_scroll_idle_title) {
        item { InterfaceSettingsCard {
            InterfaceSwitchRow(R.string.ui_display_settings_page_auto_scroll_enable_title, display.enableAutoScroll, "enableAutoScroll") { value -> state.updateDisplaySetting { it.copy(enableAutoScroll = value) } }
            InterfaceDivider()
            InterfaceSliderRow(R.string.ui_display_settings_page_auto_scroll_idle_title, display.autoScrollIdleSeconds.toFloat(), 0f..64f,
                stringResource(R.string.ui_seconds, display.autoScrollIdleSeconds), steps = 31) { value -> state.updateDisplaySetting { it.copy(autoScrollIdleSeconds = value.toInt()) } }
        } }
        item { InterfaceFooter(R.string.ui_display_settings_page_auto_scroll_idle_subtitle) }
    }
}

@Composable
fun SettingInterfaceInputOpacityPage(state: InterfacePageState = rememberInterfacePageState()) {
    val settings = state.settings
    InterfaceSettingsScaffold(R.string.ui_display_settings_page_chat_input_background_opacity_title) {
        item { InterfaceSettingsCard {
            InterfaceSliderRow(R.string.ui_settings_page_light_mode, settings.displaySetting.chatInputBackgroundOpacityLight, 0f..1f) { value -> state.updateDisplaySetting { it.copy(chatInputBackgroundOpacityLight = value) } }
            InterfaceDivider()
            InterfaceSliderRow(R.string.ui_settings_page_dark_mode, settings.displaySetting.chatInputBackgroundOpacityDark, 0f..1f) { value -> state.updateDisplaySetting { it.copy(chatInputBackgroundOpacityDark = value) } }
        } }
    }
}

@Composable
internal fun messageNavModeLabel(mode: MessageNavButtonsMode): String = stringResource(when (mode) {
    MessageNavButtonsMode.ALWAYS -> R.string.ui_display_settings_page_message_nav_buttons_mode_always
    MessageNavButtonsMode.SCROLL -> R.string.ui_display_settings_page_message_nav_buttons_mode_scroll
    MessageNavButtonsMode.NEVER -> R.string.ui_display_settings_page_message_nav_buttons_mode_never
})
