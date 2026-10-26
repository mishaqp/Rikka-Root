package me.rerere.rikkahub.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.sheetTileContainerOr
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject

private enum class FilesPickerPage {
    MAIN,
    MCP,
    EXTENSIONS,
}

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    // 会话视角下的助手和模型：会话开始后以会话上固定的配置为准
    assistant: Assistant,
    chatModel: Model?,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSketch: () -> Unit,
    onStartVoiceMode: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val showCwd = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    var showCwdSheet by remember { mutableStateOf(false) }

    var page by remember { mutableStateOf(FilesPickerPage.MAIN) }
    // 在子页面时，返回键回到主页面而不是关闭 sheet
    BackHandler(enabled = page != FilesPickerPage.MAIN) {
        page = FilesPickerPage.MAIN
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != FilesPickerPage.MAIN) {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "FilesPickerPage"
    ) { currentPage ->
        when (currentPage) {
            FilesPickerPage.MCP -> McpPickerPage(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
            )

            FilesPickerPage.EXTENSIONS -> ExtensionPickerPage(
                assistant = assistant,
                settings = settings,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
                onDismissAll = onDismiss,
            )

            FilesPickerPage.MAIN -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AttachmentTile(
                        action = AttachmentAction(HugeIcons.Camera01, R.string.take_picture, onTakePic),
                        label = stringResource(R.string.chat_plus_camera),
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentTile(
                        action = AttachmentAction(HugeIcons.Image02, R.string.photo, onPickImage),
                        label = stringResource(R.string.chat_plus_photos),
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentTile(
                        action = AttachmentAction(HugeIcons.Files02, R.string.upload_file, onPickFile),
                        label = stringResource(R.string.chat_plus_files),
                        modifier = Modifier.weight(1f),
                    )
                }

                val showWorkspace = workspaces.isNotEmpty()
                val showMcp = settings.mcpServers.isNotEmpty()
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AttachmentRow(AttachmentAction(HugeIcons.PaintBoard, R.string.sketch, onSketch))
                    if (quickMessages.isNotEmpty()) {
                        QuickMessagesSheetRow(
                            quickMessages = quickMessages,
                            state = state,
                            onDismiss = onDismiss,
                        )
                    }
                    if (provider != null && provider is ProviderSetting.Google) {
                        AttachmentRow(AttachmentAction(HugeIcons.Video01, R.string.video, onPickVideo))
                        AttachmentRow(AttachmentAction(HugeIcons.MusicNote03, R.string.audio, onPickAudio))
                    }
                    onStartVoiceMode?.let { start ->
                        AttachmentRow(AttachmentAction(HugeIcons.Voice, R.string.chat_page_voice_title, start))
                    }

                    if (showWorkspace) {
                        WorkspacePickerListItem(
                            assistant = assistant,
                            workspaces = workspaces,
                            onUpdateAssistant = onUpdateAssistant,
                            onNavigateToDetail = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceDetail(id))
                            },
                            onNavigateToTerminal = { id ->
                                onDismiss()
                                navController.navigate(Screen.WorkspaceTerminal(id))
                            },
                            onNavigateToManage = {
                                onDismiss()
                                navController.navigate(Screen.Workspaces)
                            },
                        )

                        // Workspace CWD
                        if (showCwd) {
                            ToolsSheetRow(
                                icon = HugeIcons.Folder01,
                                label = conversation.workspaceCwd ?: "/workspace",
                                onClick = { showCwdSheet = true },
                                onClickLabel = stringResource(R.string.workspace_cwd_select_directory),
                                // 路径的末尾更有辨识度，放不下时省略开头
                                labelOverflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }

                    if (showMcp) {
                        McpToolsSheetRow(
                            assistant = assistant,
                            settings = settings,
                            mcpManager = mcpManager,
                            onClick = { page = FilesPickerPage.MCP },
                        )
                    }

                    // Extensions (Quick Messages + Prompt Injections + Skills)
                    val activeCount =
                        assistant.quickMessageIds.size +
                            assistant.modeInjectionIds.size +
                            assistant.lorebookIds.size +
                            assistant.enabledSkills.size
                    ToolsSheetRow(
                        icon = HugeIcons.Package,
                        label = stringResource(R.string.assistant_page_tab_extensions),
                        onClick = { page = FilesPickerPage.EXTENSIONS },
                        trailingContent = {
                            if (activeCount > 0) {
                                CountBadge(count = activeCount)
                            } else {
                                ToolsSheetChevron()
                            }
                        },
                    )

                    // Compress History Button
                    ToolsSheetRow(
                        icon = HugeIcons.Package01,
                        label = stringResource(R.string.chat_page_compress_context),
                        onClick = { onShowCompressDialogChange(true) },
                        trailingContent = {
                            if (conversation.messageNodes.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                ToolsSheetChevron()
                            }
                        },
                    )
                }
            }
        }
    }

    if (showCwdSheet && boundWorkspace != null) {
        WorkspaceCwdPickerSheet(
            workspaceId = boundWorkspace.id,
            currentCwd = conversation.workspaceCwd,
            onSelectCwd = { newCwd ->
                onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
            },
            onDismiss = { showCwdSheet = false },
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        CompressContextDialog(onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
            onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
        })
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    ToolsSheetRow(
        icon = HugeIcons.Codesandbox,
        label = stringResource(R.string.assistant_page_workspace),
        onClick = { showSheet = true },
        subtitle = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    ToolsSheetChevron()
                }
            }
        },
    )

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    // 会话的工作目录随工作区一并重置
                    onUpdateAssistant { current -> current.copy(workspaceId = newId) }
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

// 扩展选择页，作为子页面嵌在加号 sheet 里
@Composable
private fun ExtensionPickerPage(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.assistant_page_tab_extensions),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        ExtensionSelector(
            assistant = assistant,
            settings = settings,
            onUpdate = onUpdateAssistant,
            modifier = Modifier.weight(1f),
            onNavigateToQuickMessages = {
                onDismissAll()
                navController.navigate(Screen.QuickMessages)
            },
            onNavigateToPrompts = {
                onDismissAll()
                navController.navigate(Screen.Prompts)
            },
            onNavigateToSkills = {
                onDismissAll()
                navController.navigate(Screen.Skills)
            })

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
)

@Composable
private fun AttachmentRow(action: AttachmentAction) {
    ToolsSheetRow(
        icon = action.icon,
        label = stringResource(action.label),
        onClick = action.onClick,
    )
}

@Composable
private fun QuickMessagesSheetRow(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        ToolsSheetRow(
            icon = HugeIcons.Zap,
            label = stringResource(R.string.chat_plus_quick_messages),
            onClick = { expanded = !expanded },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(min = 200.dp, max = 360.dp),
        ) {
            quickMessages.forEach { quickMessage ->
                QuickMessageSheetItem(
                    title = quickMessage.title,
                    message = quickMessage.content,
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
internal fun QuickMessageSheetItem(
    title: String,
    message: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = scheme.sheetTileContainerOr(scheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun McpToolsSheetRow(
    assistant: Assistant,
    settings: Settings,
    mcpManager: McpManager,
    onClick: () -> Unit,
) {
    val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val loading = status.values.any { it == McpStatus.Connecting }
    val enabledCount = settings.mcpServers.count {
        it.commonOptions.enable && assistant.mcpServers.contains(it.id)
    }
    ToolsSheetRow(
        icon = HugeIcons.McpServer,
        label = stringResource(R.string.mcp_picker_title),
        onClick = onClick,
        leadingContent = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(HugeIcons.McpServer, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        trailingContent = {
            if (enabledCount > 0) {
                CountBadge(count = enabledCount)
            } else {
                ToolsSheetChevron()
            }
        },
    )
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    label: String,
    modifier: Modifier = Modifier,
) {
    ToolsSheetSurface(
        onClick = action.onClick,
        modifier = modifier.heightIn(min = 72.dp),
        pressedScale = 0.98f,
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Moru ToolsSheetRow: 48dp / 56dp, radius 14dp, icon 20dp, horizontal padding 12dp.
@Composable
private fun ToolsSheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    onClickLabel: String? = null,
    labelOverflow: TextOverflow = TextOverflow.Ellipsis,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ToolsSheetSurface(
        onClick = onClick,
        onClickLabel = onClickLabel,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 48.dp else 56.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = labelOverflow,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingContent != null) trailingContent() else ToolsSheetChevron()
        }
    }
}

@Composable
private fun ToolsSheetChevron() {
    Icon(
        imageVector = HugeIcons.ArrowRight01,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.size(18.dp),
    )
}

private val ToolsSheetPressEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

// Moru IosCardPress: color / optional 0.98 scale tween, 260ms easeOutCubic.
// Sheet fills stay opaque and use the active MaterialTheme.
@Composable
internal fun ToolsSheetSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    pressedScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    val base = scheme.sheetTileContainerOr(scheme.surfaceContainerLow)
    val washStrength = if (LocalDarkMode.current) 0.14f else 0.12f
    val targetColor = when {
        pressed -> lerp(base, scheme.onSurface, washStrength)
        hovered -> lerp(base, scheme.onSurface, washStrength * 0.7f)
        else -> base
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(260, easing = ToolsSheetPressEasing),
        label = "ToolsSheetPressColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(260, easing = ToolsSheetPressEasing),
        label = "ToolsSheetPressScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
        content = content,
    )
}

// 列表项尾部的已启用数量
@Composable
internal fun CountBadge(count: Int) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(count.toString())
    }
}

@Preview(showBackground = true)
@Composable
private fun AttachmentTilesPreview() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AttachmentTile(
            action = AttachmentAction(HugeIcons.Camera01, R.string.take_picture) {},
            label = stringResource(R.string.chat_plus_camera),
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            action = AttachmentAction(HugeIcons.Image02, R.string.photo) {},
            label = stringResource(R.string.chat_plus_photos),
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            action = AttachmentAction(HugeIcons.Files02, R.string.upload_file) {},
            label = stringResource(R.string.chat_plus_files),
            modifier = Modifier.weight(1f),
        )
    }
}
