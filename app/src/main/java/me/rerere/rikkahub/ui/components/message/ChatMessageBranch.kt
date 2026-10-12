package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.data.model.MessageNode

@Composable
fun ChatMessageBranchSelector(
    node: MessageNode,
    modifier: Modifier = Modifier,
    onUpdate: (MessageNode) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.messages.size > 1) {
            // Moru: ‹ 2/3 › in three 28dp slots with 16dp chevrons.
            val actionColor = MaterialTheme.colorScheme.onSurface

            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = "Prev",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == 0) 0.5f else 1f)
                    .clickable(
                        enabled = enabled,                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex > 0) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex - 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(6.dp)
                    .size(16.dp),
                tint = actionColor
            )

            Box(
                modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${node.selectIndex + 1}/${node.messages.size}",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = actionColor.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }

            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = "Next",
                modifier = Modifier
                    .clip(CircleShape)
                    .alpha(if (node.selectIndex == node.messages.lastIndex) 0.5f else 1f)
                    .clickable(
                        enabled = enabled,                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (node.selectIndex < node.messages.lastIndex) {
                                onUpdate(
                                    node.copy(
                                        selectIndex = node.selectIndex + 1
                                    )
                                )
                            }
                        }
                    )
                    .padding(6.dp)
                    .size(16.dp),
                tint = actionColor
            )
        }
    }
}
