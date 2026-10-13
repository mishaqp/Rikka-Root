package me.rerere.rikkahub.ui.pages.setting

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.ui.components.Select
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@Composable
fun SettingInterfaceBackgroundPage(state: InterfacePageState = rememberInterfacePageState()) {
    val display = state.settings.displaySetting
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assistant = state.settings.assistants.find { it.id == state.settings.assistantId }
    var targetAssistant by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            failed = false
            runCatching { withContext(Dispatchers.IO) { importInterfaceBackground(context, uri) } }
                .onSuccess { path ->
                    if (targetAssistant && assistant != null) state.updateSettings { current -> current.copy(assistants = current.assistants.map {
                        if (it.id == assistant.id) it.copy(background = path) else it
                    }) } else state.updateDisplaySetting { it.copy(chatBackgroundImagePath = path, chatBackgroundEnabled = true) }
                }.onFailure { failed = true }
        }
    }
    InterfaceSettingsScaffold(R.string.ui_background) {
        item { InterfaceSettingsCard {
            InterfaceSwitchRow(R.string.ui_background_enabled, display.chatBackgroundEnabled, "chatBackgroundEnabled", R.string.ui_background_description) { value -> state.updateDisplaySetting { it.copy(chatBackgroundEnabled = value) } }
            InterfaceDivider()
            InterfaceNavigationRow(R.string.ui_background_choose,
                detail = if (display.chatBackgroundImagePath.isBlank()) stringResource(R.string.ui_background_no_image) else stringResource(R.string.ui_enabled)) {
                targetAssistant = false; picker.launch(arrayOf("image/*"))
            }
            if (display.chatBackgroundImagePath.isNotBlank()) {
                AsyncImage(display.chatBackgroundImagePath, null, Modifier.fillMaxWidth().height(160.dp).padding(12.dp))
                TextButton(onClick = { state.updateDisplaySetting { it.copy(chatBackgroundEnabled = false, chatBackgroundImagePath = "") } }) { Text(stringResource(R.string.ui_background_remove)) }
            }
        } }
        if (assistant != null) item { InterfaceSettingsCard {
            Text(stringResource(R.string.ui_background_current_assistant), Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall)
            InterfaceSwitchRow(R.string.ui_background_gradient, assistant.useGradientBackground, "assistantGradient") { value -> state.updateSettings { current -> current.copy(assistants = current.assistants.map {
                if (it.id == assistant.id) it.copy(useGradientBackground = value) else it
            }) } }
            InterfaceDivider()
            InterfaceNavigationRow(R.string.ui_background_choose, detail = if (assistant.background.isNullOrBlank()) stringResource(R.string.ui_background_no_image) else stringResource(R.string.ui_enabled)) {
                targetAssistant = true; picker.launch(arrayOf("image/*"))
            }
            if (!assistant.background.isNullOrBlank()) {
                AsyncImage(assistant.background, null, Modifier.fillMaxWidth().height(160.dp).padding(12.dp))
                InterfaceSliderRow(R.string.ui_background_opacity, assistant.backgroundOpacity, 0f..1f) { value -> state.updateSettings { current -> current.copy(assistants = current.assistants.map {
                    if (it.id == assistant.id) it.copy(backgroundOpacity = value) else it
                }) } }
                TextButton(onClick = { state.updateSettings { current -> current.copy(assistants = current.assistants.map {
                    if (it.id == assistant.id) it.copy(background = null) else it
                }) } }) { Text(stringResource(R.string.ui_background_remove)) }
            }
        } }
        item { InterfaceSettingsCard {
            InterfaceSliderRow(R.string.ui_display_settings_page_chat_background_mask_title, display.chatBackgroundMaskStrength, 0f..2f) { value -> state.updateDisplaySetting { it.copy(chatBackgroundMaskStrength = value) } }
            InterfaceDivider()
            InterfaceSwitchRow(R.string.ui_blur_effect_title, display.enableBlurEffect, "enableBlurEffect") { value -> state.updateDisplaySetting { it.copy(enableBlurEffect = value) } }
            if (display.enableBlurEffect) {
                Select(BackgroundEffectType.entries, display.backgroundEffectType,
                    { value -> state.updateDisplaySetting { it.copy(backgroundEffectType = value) } }, Modifier.padding(14.dp), optionToString = {
                    stringResource(if (it == BackgroundEffectType.BLUR) R.string.ui_blur_kind_blur else R.string.ui_blur_kind_glass)
                })
            }
        } }
        if (failed) item { Text(stringResource(R.string.ui_background_import_error), color = MaterialTheme.colorScheme.error) }
    }
}

private fun importInterfaceBackground(context: android.content.Context, uri: Uri): String {
    val filesManager = GlobalContext.get().get<me.rerere.rikkahub.data.files.FilesManager>()
    return filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()?.toString() ?: error("Unreadable image")
}
