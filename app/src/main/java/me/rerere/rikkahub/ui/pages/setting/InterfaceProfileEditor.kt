package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
    val name = remember { ProfileFieldDraft(display.userNickname) { it } }
    val emoji = remember { ProfileFieldDraft(display.userAvatar) { (it as? Avatar.Emoji)?.content.orEmpty() } }
    LaunchedEffect(display.userNickname) { name.acceptSavedValue(display.userNickname) }
    LaunchedEffect(display.userAvatar) { emoji.acceptSavedValue(display.userAvatar) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runCatching { GlobalContext.get().get<FilesManager>().createChatFilesByContents(listOf(it)).firstOrNull() }
            .getOrNull()?.let { imported -> state.updateDisplaySetting { it.copy(userAvatar = Avatar.Image(imported.toString())) } } }
    }
    InterfaceSettingsCard {
        OutlinedTextField(name.text, { name.edit(it, it); state.updateDisplaySetting { display -> display.copy(userNickname = it) } },
            label = { Text(stringResource(R.string.ui_profile_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(14.dp).onFocusChanged { if (!it.isFocused) name.finishEditing() })
        OutlinedTextField(emoji.text, {
            val avatar = if (it.isBlank()) Avatar.Dummy else Avatar.Emoji(it)
            emoji.edit(it, avatar)
            state.updateDisplaySetting { display -> display.copy(userAvatar = avatar) }
        },
            label = { Text(stringResource(R.string.ui_profile_avatar_emoji)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).onFocusChanged { if (!it.isFocused) emoji.finishEditing() })
        TextButton(onClick = { picker.launch("image/*") }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.ui_profile_avatar_choose)) }
        InterfaceDivider()
        InterfaceSwitchRow(R.string.ui_show_thinking_content, display.showThinkingContent, "showThinkingContent") { value -> state.updateDisplaySetting { it.copy(showThinkingContent = value) } }
    }
}

/** A preceding save must not replace a newer keystroke; unrelated stored values still apply. */
private class ProfileFieldDraft<T>(initialValue: T, private val displayText: (T) -> String) {
    var text by mutableStateOf(displayText(initialValue))
        private set
    private val pendingValues = ArrayDeque<T>()

    fun edit(text: String, storedValue: T) {
        this.text = text
        pendingValues.addLast(storedValue)
    }

    fun acceptSavedValue(value: T) {
        val acknowledged = pendingValues.indexOf(value)
        if (acknowledged >= 0) {
            repeat(acknowledged + 1) { pendingValues.removeFirst() }
        } else {
            pendingValues.clear()
            text = displayText(value)
        }
    }

    fun finishEditing() {
        pendingValues.clear()
    }
}
