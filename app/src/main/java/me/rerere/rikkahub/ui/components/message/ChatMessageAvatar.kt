package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.utils.toMessageTimeString

@Composable
internal fun ChatMessageHeader(
    message: UIMessage,
    display: MessageDisplayPresentation,
    model: Model?,
    assistant: Assistant?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!display.showHeader) return
    val user = message.role == MessageRole.USER
    val options = display.header
    val providerName = if (options.showProvider) model?.findProvider(LocalSettings.current.providers)?.name.orEmpty() else ""
    if (!options.showUserIdentity && !options.showUserAvatar && !options.showAssistantIcon &&
        !options.showAssistantName && !options.showTimestamp && providerName.isBlank()) return
    val prefix = if (user) "chat_message_user" else "chat_message_assistant"
    val colors = MaterialTheme.colorScheme
    val nameStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colors.onSurface.copy(alpha = .7f),
    )
    val timeStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        color = colors.onSurface.copy(alpha = .5f),
    )
    Row(
        modifier = modifier.fillMaxWidth().testTag("${prefix}_header"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (options.showAssistantIcon) {
            if (assistant?.useAssistantAvatar == true) {
                UIAvatar(name = assistant.name, value = assistant.avatar, loading = loading,
                    modifier = Modifier.size(32.dp).testTag("${prefix}_icon"))
            } else if (model != null) {
                AutoAIIcon(name = model.modelId, loading = loading,
                    modifier = Modifier.size(32.dp).testTag("${prefix}_icon"))
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (options.showUserIdentity) {
                Text(text = options.userNickname.ifEmpty { stringResource(R.string.user_default_name) },
                    style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${prefix}_name"))
            }
            if (options.showAssistantName) {
                val name = if (assistant?.useAssistantAvatar == true) {
                    assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) }
                } else model?.displayName.orEmpty()
                Text(text = name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${prefix}_name"))
            }
            if (options.showTimestamp) {
                Text(text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                    style = timeStyle, maxLines = 1, modifier = Modifier.testTag("${prefix}_time"))
            }
            if (options.showProvider) {
                if (providerName.isNotBlank()) {
                    Text(text = providerName, style = timeStyle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("${prefix}_provider"))
                }
            }
        }
        if (options.showUserAvatar) {
            UIAvatar(name = options.userNickname, value = options.userAvatar, loading = false,
                modifier = Modifier.size(32.dp).testTag("${prefix}_icon"))
        }
    }
}
