package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import org.koin.core.context.GlobalContext

@Composable
internal fun InterfaceProfileEditor(state: InterfacePageState) {
    val display = state.settings.displaySetting
    val context = LocalContext.current
    var name by remember(display.userNickname) { mutableStateOf(display.userNickname) }
    var emoji by remember(display.userAvatar) { mutableStateOf((display.userAvatar as? Avatar.Emoji)?.content.orEmpty()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runCatching { GlobalContext.get().get<FilesManager>().createChatFilesByContents(listOf(it)).firstOrNull() }
            .getOrNull()?.let { imported -> state.updateDisplaySetting { it.copy(userAvatar = Avatar.Image(imported.toString())) } } }
    }
    InterfaceSettingsCard {
        OutlinedTextField(name, { name = it; state.updateDisplaySetting { display -> display.copy(userNickname = it) } },
            label = { Text(stringResource(R.string.ui_profile_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(14.dp))
        OutlinedTextField(emoji, { emoji = it; state.updateDisplaySetting { display -> display.copy(userAvatar = if (it.isBlank()) Avatar.Dummy else Avatar.Emoji(it)) } },
            label = { Text(stringResource(R.string.ui_profile_avatar_emoji)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        TextButton(onClick = { picker.launch("image/*") }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.ui_profile_avatar_choose)) }
        InterfaceDivider()
        InterfaceSwitchRow(R.string.ui_show_thinking_content, display.showThinkingContent, "showThinkingContent") { value -> state.updateDisplaySetting { it.copy(showThinkingContent = value) } }
    }
}
