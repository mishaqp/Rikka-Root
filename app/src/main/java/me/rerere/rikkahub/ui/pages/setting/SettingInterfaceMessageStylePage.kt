package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.ui.components.message.ChatMessageSurface
import me.rerere.rikkahub.ui.components.message.LocalChatMessageHazeState
import me.rerere.rikkahub.ui.components.message.MessageTextSurface
import me.rerere.rikkahub.ui.components.message.resolveMessageBubbleStyle
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.findThemeById
import me.rerere.rikkahub.ui.theme.effectiveInterfaceColorScheme
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground

@Composable
fun SettingInterfaceMessageStylePage(state: InterfacePageState = rememberInterfacePageState()) {
    val display = state.settings.displaySetting
    val locale = LocalResources.current.configuration.locales[0]
    val currentDark = LocalDarkMode.current
    var editingDark by remember { mutableStateOf(currentDark) }
    var editingUser by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    val colors = effectiveInterfaceColorScheme(state.settings, editingDark)
    val overrides = if (editingUser) display.userChatBubbleStyleOverrides else display.assistantChatBubbleStyleOverrides
    val resolved = resolveMessageBubbleStyle(if (editingUser) MessageTextSurface.USER else MessageTextSurface.ASSISTANT,
        colors, editingDark, display, 1f)
    fun change(transform: (ChatBubbleStyleOverrides) -> ChatBubbleStyleOverrides) = state.updateDisplaySetting { current ->
        if (editingUser) current.copy(userChatBubbleStyleOverrides = transform(current.userChatBubbleStyleOverrides))
        else current.copy(assistantChatBubbleStyleOverrides = transform(current.assistantChatBubbleStyleOverrides))
    }
    InterfaceSettingsScaffold(R.string.ui_message_style_settings_page_title, actions = {
        IconButton(onClick = { resetConfirm = true }) { Icon(HugeIcons.Refresh03, stringResource(R.string.ui_reset)) }
    }) {
        item { InterfaceSettingsCard {
            ChatMessageBackgroundStyle.entries.forEachIndexed { index, style ->
                if (index > 0) InterfaceDivider()
                Row(Modifier.fillMaxWidth().clickable { state.updateDisplaySetting { it.copy(chatMessageBackgroundStyle = style) } }
                    .padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val sample = when (style) { ChatMessageBackgroundStyle.DEFAULT -> colors.primary.copy(alpha = .14f)
                        ChatMessageBackgroundStyle.FROSTED -> colors.surfaceContainerHigh.copy(alpha = .62f)
                        ChatMessageBackgroundStyle.SOLID -> colors.surfaceContainerHigh }
                    Box(Modifier.size(28.dp).background(sample, RoundedCornerShape(8.dp)).border(.8.dp, colors.outlineVariant, RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(styleTitle(style)), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(styleDescription(style)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (display.chatMessageBackgroundStyle == style) Icon(HugeIcons.Tick01, null, Modifier.size(18.dp), tint = colors.primary)
                }
            }
        } }
        item { InterfaceSettingsCard {
            InterfaceSwitchRow(R.string.ui_message_style_settings_page_assistant_fit_content, display.assistantBubbleFitContent,
                "assistantBubbleFitContent", R.string.ui_message_style_settings_page_assistant_fit_content_subtitle) { value -> state.updateDisplaySetting { it.copy(assistantBubbleFitContent = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_message_style_settings_page_assistant_split_paragraphs, display.assistantBubbleSplitParagraphs,
                "assistantBubbleSplitParagraphs", R.string.ui_message_style_settings_page_assistant_split_paragraphs_subtitle) { value -> state.updateDisplaySetting { it.copy(assistantBubbleSplitParagraphs = value) } }
        } }
        item { StyleSegmentedToggle(R.string.ui_message_style_settings_page_light, R.string.ui_message_style_settings_page_dark, editingDark) { editingDark = it } }
        if (display.chatMessageBackgroundStyle != ChatMessageBackgroundStyle.DEFAULT) {
            item { StyleSegmentedToggle(R.string.ui_message_style_settings_page_role_user, R.string.ui_message_style_settings_page_role_assistant, !editingUser) { editingUser = !it } }
            item { InterfaceFooter(R.string.ui_message_style_settings_page_role_assistant_hint) }
        }
        item { MessageStylePreview(display, editingDark, editingUser, state.settings) }
        if (display.chatMessageBackgroundStyle == ChatMessageBackgroundStyle.DEFAULT) item { InterfaceFooter(R.string.ui_message_style_settings_page_default_hint) }
        else item { InterfaceSettingsCard {
            if (display.chatMessageBackgroundStyle == ChatMessageBackgroundStyle.FROSTED) {
                InterfaceSliderRow(R.string.ui_message_style_settings_page_blur, overrides.blurSigma ?: 14f, 0f..30f,
                    valueText = (overrides.blurSigma ?: 14f).toInt().toString(), steps = 29) { value -> change { it.copy(blurSigma = value) } }
                InterfaceFooter(R.string.ui_message_style_settings_page_blur_hint)
                InterfaceDivider()
            }
            InterfaceColorRow(R.string.ui_message_style_settings_page_background_color, resolved.background.copy(alpha = 1f)) { value -> change {
                if (editingDark) it.copy(backgroundArgbDark = value) else it.copy(backgroundArgbLight = value)
            } }
            InterfaceDivider()
            val opacity = if (display.chatMessageBackgroundStyle == ChatMessageBackgroundStyle.FROSTED) overrides.frostedOpacity ?: .66f else overrides.solidOpacity ?: 1f
            InterfaceSliderRow(R.string.ui_message_style_settings_page_background_opacity, opacity, 0f..1f) { value -> change {
                if (display.chatMessageBackgroundStyle == ChatMessageBackgroundStyle.FROSTED) it.copy(frostedOpacity = value) else it.copy(solidOpacity = value)
            } }
            InterfaceDivider()
            InterfaceColorRow(R.string.ui_message_style_settings_page_border_color, resolved.border.copy(alpha = 1f)) { value -> change {
                if (editingDark) it.copy(borderArgbDark = value) else it.copy(borderArgbLight = value)
            } }
            InterfaceDivider()
            InterfaceSliderRow(R.string.ui_message_style_settings_page_border_opacity,
                overrides.borderOpacity ?: if (display.chatMessageBackgroundStyle == ChatMessageBackgroundStyle.FROSTED) .14f else .16f,
                0f..1f) { value -> change { it.copy(borderOpacity = value) } }
            InterfaceDivider()
            InterfaceSliderRow(R.string.ui_message_style_settings_page_border_width, overrides.borderWidth ?: .8f, 0f..3f,
                valueText = String.format(locale, "%.1f", overrides.borderWidth ?: .8f)) { value -> change { it.copy(borderWidth = value) } }
            InterfaceDivider()
            InterfaceColorRow(R.string.ui_message_style_settings_page_text_color, resolved.text) { value -> change {
                if (editingDark) it.copy(textArgbDark = value) else it.copy(textArgbLight = value)
            } }
            InterfaceDivider()
            InterfaceSliderRow(R.string.ui_message_style_settings_page_corner_radius, overrides.cornerRadius ?: 16f, 0f..28f,
                valueText = (overrides.cornerRadius ?: 16f).toInt().toString(), steps = 27) { value -> change { it.copy(cornerRadius = value) } }
        } }
        item { InterfaceSettingsCard {
            if (display.chatMessageBackgroundStyle == ChatMessageBackgroundStyle.DEFAULT) {
                InterfaceSwitchRow(R.string.ui_message_legacy_bubble, display.showAssistantBubble, "showAssistantBubble") { value -> state.updateDisplaySetting { it.copy(showAssistantBubble = value) } }
                InterfaceDivider()
            }
            InterfaceSliderRow(R.string.ui_message_legacy_opacity, display.bubbleOpacity, .1f..1f) { value -> state.updateDisplaySetting { it.copy(bubbleOpacity = value) } }
        } }
    }
    if (resetConfirm) AlertDialog(onDismissRequest = { resetConfirm = false }, title = { Text(stringResource(R.string.ui_confirm_style_reset)) },
        confirmButton = { TextButton(onClick = { state.updateDisplaySetting { it.copy(userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(), assistantChatBubbleStyleOverrides = ChatBubbleStyleOverrides()) }; resetConfirm = false }) { Text(stringResource(R.string.ui_reset)) } },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.ui_cancel)) } })
}

@Composable
internal fun MessageStylePreview(
    display: DisplaySetting,
    editingDark: Boolean = LocalDarkMode.current,
    editingUser: Boolean = false,
    settings: Settings = LocalSettings.current,
    emphasizeEditedRole: Boolean = true,
) {
    val scheme = effectiveInterfaceColorScheme(settings.copy(displaySetting = display), editingDark)
    val haze = rememberHazeState()
    val custom = display.chatMessageBackgroundStyle != ChatMessageBackgroundStyle.DEFAULT
    CompositionLocalProvider(LocalSettings provides settings.copy(displaySetting = display), LocalDarkMode provides editingDark,
        LocalChatMessageHazeState provides haze) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(16.dp)).testTag("ui_message_style_preview")) {
                Box(Modifier.matchParentSize().hazeSource(haze).background(scheme.surface)) {
                    AssistantBackground(setting = settings.copy(displaySetting = display), modifier = Modifier.matchParentSize())
                }
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ChatMessageSurface(MessageTextSurface.USER, display.bubbleOpacity,
                            Modifier.widthIn(max = 240.dp).alpha(if (emphasizeEditedRole && custom && !editingUser) .45f else 1f)) {
                            Text(stringResource(R.string.ui_message_style_settings_page_preview_user), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    ChatMessageSurface(MessageTextSurface.ASSISTANT, display.bubbleOpacity,
                        Modifier.fillMaxWidth().alpha(if (emphasizeEditedRole && custom && editingUser) .45f else 1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(HugeIcons.Brain02, null, Modifier.size(16.dp))
                            Text(stringResource(R.string.ui_message_style_settings_page_preview_thinking), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ChatMessageSurface(if (custom || display.showAssistantBubble) MessageTextSurface.ASSISTANT else MessageTextSurface.NONE,
                        display.bubbleOpacity, (if (display.assistantBubbleFitContent) Modifier.widthIn(max = 260.dp) else Modifier.fillMaxWidth())
                            .alpha(if (emphasizeEditedRole && custom && editingUser) .45f else 1f)) {
                        Text(stringResource(R.string.ui_message_style_settings_page_preview_assistant), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleSegmentedToggle(left: Int, right: Int, rightSelected: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = !rightSelected, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(left), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = LocalTextStyle.current.fontSize)) }
        SegmentedButton(selected = rightSelected, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(right), maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = LocalTextStyle.current.fontSize)) }
    }
}

@Composable
internal fun InterfaceColorRow(title: Int, color: Color, onChange: (Long) -> Unit) {
    var show by remember { mutableStateOf(false) }
    var value by remember(color) { mutableStateOf("%08X".format(color.toArgb())) }
    var invalid by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { show = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.size(28.dp).background(color, CircleShape).border(.6.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(stringResource(title)) },
        text = { Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Color.White, Color.Black, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary).forEach { swatch ->
                    Box(Modifier.size(32.dp).background(swatch, CircleShape).border(.6.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { value = "%08X".format(swatch.toArgb()); invalid = false })
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value, { value = it.removePrefix("#").filter { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' }.take(8); invalid = false },
                singleLine = true, label = { Text(stringResource(R.string.ui_color)) }, placeholder = { Text(stringResource(R.string.ui_color_hint)) }, isError = invalid)
            if (invalid) Text(stringResource(R.string.ui_color_invalid), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = {
            val parsed = value.toLongOrNull(16)
            if (parsed == null || value.length !in listOf(6, 8)) invalid = true
            else { onChange((parsed and 0xFFFFFFL) or 0xFF000000L); show = false }
        }) { Text(stringResource(R.string.ui_apply)) } }, dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.ui_cancel)) } })
}

private fun styleTitle(style: ChatMessageBackgroundStyle) = when (style) {
    ChatMessageBackgroundStyle.DEFAULT -> R.string.ui_display_settings_page_chat_message_background_default
    ChatMessageBackgroundStyle.FROSTED -> R.string.ui_display_settings_page_chat_message_background_frosted
    ChatMessageBackgroundStyle.SOLID -> R.string.ui_display_settings_page_chat_message_background_solid
}

private fun styleDescription(style: ChatMessageBackgroundStyle) = when (style) {
    ChatMessageBackgroundStyle.DEFAULT -> R.string.ui_message_style_settings_page_style_default_subtitle
    ChatMessageBackgroundStyle.FROSTED -> R.string.ui_message_style_settings_page_style_frosted_subtitle
    ChatMessageBackgroundStyle.SOLID -> R.string.ui_message_style_settings_page_style_solid_subtitle
}
