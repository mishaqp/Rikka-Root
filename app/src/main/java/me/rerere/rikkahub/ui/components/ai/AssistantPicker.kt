package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.LayeredSheetTile
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import kotlin.uuid.Uuid

@Composable
fun AssistantPicker(
    onDismissRequest: () -> Unit,
    assistants: List<Assistant>,
    onAssistantSelected: (Assistant) -> Unit,
    selectedAssistantId: Uuid,
    onClearSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberPremiumHaptics()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.6f)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                    Text(stringResource(R.string.assistant_picker_title))
                }
                TextButton(
                    onClick = {
                        onClearSelect()
                        onDismissRequest()
                    }
                ) {
                    Text(stringResource(R.string.assistant_picker_clear))
                }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(assistants, key = { it.id }) { assistant ->
                    val checked = selectedAssistantId == assistant.id
                    LayeredSheetTile(
                        selected = checked,
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            onAssistantSelected(assistant)
                        },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        AssistantItem(
                            assistant = assistant,
                            modifier = Modifier
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UIAvatar(
            value = assistant.avatar,
            name = assistant.name,
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
        )
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = assistant.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (assistant.useAssistantAvatar) {
                    TagLabel(text = stringResource(R.string.assistant_picker_use_assistant_avatar))
                }
                if (assistant.enableRecentChatsReference) {
                    TagLabel(text = stringResource(R.string.assistant_picker_recent_chats_reference))
                }
                assistant.tags.fastForEach { tag ->
                    TagLabel(text = tag)
                }
            }
        }
    }
}

@Composable
private fun TagLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .padding(vertical = 2.dp)
    )
}
