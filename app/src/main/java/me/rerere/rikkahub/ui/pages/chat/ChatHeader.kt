package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R

/** Moru's compact name/model line and workspace capsule, using opaque theme surfaces. */
@Composable
internal fun ChatHeader(
    title: String,
    modelName: String,
    modelDescription: String,
    bigScreen: Boolean,
    previewMode: Boolean,
    canRename: Boolean,
    canSelectModel: Boolean,
    showWorkspace: Boolean,
    avatar: @Composable () -> Unit,
    onOpenDrawer: () -> Unit,
    onClickTitle: () -> Unit,
    onSelectModel: () -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onFiles: () -> Unit,
    onTerminal: () -> Unit,
    onBrowser: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!bigScreen) {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
                        Icon(HugeIcons.Menu03, stringResource(R.string.chat_header_open_drawer), Modifier.size(20.dp))
                    }
                } else Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    avatar()
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        onClick = onClickTitle,
                        enabled = canRename,
                        color = colors.background,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1.2f).testTag("chat_header_title")
                            .semantics { contentDescription = title },
                    ) {
                        Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                            HeaderText(title, MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                    Text(" · ", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Surface(
                        onClick = onSelectModel,
                        enabled = canSelectModel,
                        color = colors.background,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).testTag("chat_header_model")
                            .semantics { contentDescription = modelDescription },
                    ) {
                        Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                            HeaderText(modelName, MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp,
                                fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant))
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(HugeIcons.MoreVertical, stringResource(R.string.chat_header_menu), Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (previewMode) R.string.chat_header_back_to_chat else R.string.chat_header_preview)) },
                            leadingIcon = { Icon(HugeIcons.LeftToRightListBullet, null, Modifier.size(20.dp)) },
                            onClick = { menuOpen = false; onClickMenu() },
                        )
                    }
                }
                IconButton(onClick = onNewChat, modifier = Modifier.size(44.dp).testTag("chat_header_new_chat")) {
                    Icon(HugeIcons.MessageAdd01, stringResource(R.string.chat_header_new_chat), Modifier.size(22.dp))
                }
            }
            if (showWorkspace) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
                    Surface(
                        color = colors.surfaceContainerLow,
                        shape = CircleShape,
                        modifier = Modifier.height(40.dp).testTag("chat_header_workspace"),
                    ) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onFiles, modifier = Modifier.size(48.dp, 34.dp)) {
                                    Icon(HugeIcons.Folder01, stringResource(R.string.chat_header_files), Modifier.size(19.dp))
                                }
                                IconButton(onClick = onTerminal, modifier = Modifier.size(48.dp, 34.dp)) {
                                    Icon(HugeIcons.ComputerTerminal01, stringResource(R.string.chat_header_terminal), Modifier.size(19.dp))
                                }
                                IconButton(onClick = onBrowser, modifier = Modifier.size(48.dp, 34.dp)) {
                                    Icon(HugeIcons.Globe02, stringResource(R.string.chat_header_browser), Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderText(value: String, style: TextStyle) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(240)) { (it * .15f).toInt() }) togetherWith
                (fadeOut(tween(240)) + slideOutVertically(tween(240)) { -(it * .15f).toInt() })
        },
        label = "chat_header_text",
    ) { Text(it, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
