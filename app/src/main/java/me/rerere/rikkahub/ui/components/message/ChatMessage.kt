package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.richtext.splitAssistantParagraphs
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.pages.chat.ChatPendingToolPresentation
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticAction
import me.rerere.rikkahub.ui.hooks.rememberAppHapticFeedback
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.webview.WebViewContentCache
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    readOnly: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: me.rerere.rikkahub.service.ChatService.ApprovalScope, toolName: String) -> kotlinx.coroutines.Job)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    pendingTools: List<ChatPendingToolPresentation> = emptyList(),
    activeToolCallId: String? = null,
) {
    val message = node.messages[node.selectIndex]
    val presentation = presentChatMessage(message, loading, lastMessage, readOnly)
    if (presentation.isCheckpoint) {
        ChatMessageContextCheckpoint(
            message = message,
            readOnly = readOnly,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = modifier,
        )
        return
    }
    val settings = LocalSettings.current.displaySetting
    val display = presentMessageDisplay(
        message = message,
        settings = settings,
        loading = loading,
        lastMessage = lastMessage,
        readOnly = readOnly,
        hasAssistantIdentity = model != null || assistant?.useAssistantAvatar == true,
        hasBranches = node.messages.size > 1,
    )
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings.copy(
        chatFontFamily = display.typography.fontFamily,
        chatCustomFontPath = display.typography.customFontPath,
    ))
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * display.typography.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * display.typography.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (display.alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChatMessageHeader(
            message = message,
            display = display,
            model = model,
            assistant = assistant,
            loading = loading,
        )
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                messageKey = message.id.toString(),
                display = display,
                assistant = assistant,
                role = presentation.role,
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                onToolApproval = if (presentation.canInteractWithTools) onToolApproval else null,
                onToolAnswer = if (presentation.canInteractWithTools) onToolAnswer else null,
                onUserMessageClick = if (presentation.canEditFromBubble) onEdit else null,
                pendingTools = pendingTools.filter { it.key.messageId == presentation.messageId },
                activeToolCallId = activeToolCallId,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        if (display.showWaitingDots) {
            ChatMessageSurface(display.textSurface, display.bubbleOpacity) {
                ChatWaitingDots()
            }
        }

        if (display.showBranchSelector) {
            ChatMessageBranchSelector(node = node, onUpdate = onUpdate, enabled = !readOnly)
        }

        val motionScheme = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = display.showActions,
            enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                fadeIn(motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                fadeOut(motionScheme.fastEffectsSpec())
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    readOnly = readOnly,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation,
                    onEdit = onEdit,
                )
            }
        }

        if (settings.showProducedFiles) {
            EditedFilesList(
                parts = message.parts,
                assistant = assistant,
            )
        }

        if (display.showTokenUsage) {
            ProvideTextStyle(textStyle) {
                ChatMessageNerdLine(message = message)
            }
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            readOnly = readOnly,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme,
                        displayOptions = display.markdownOptions,
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    messageKey: String,
    display: MessageDisplayPresentation,
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: me.rerere.rikkahub.service.ChatService.ApprovalScope, toolName: String) -> kotlinx.coroutines.Job)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    pendingTools: List<ChatPendingToolPresentation> = emptyList(),
    activeToolCallId: String? = null,
) {
    val context = LocalContext.current
    // 消息输出HapticFeedback
    val hapticFeedback = rememberAppHapticFeedback(HapticAction.GENERATION)
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEachIndexed { blockOrdinal, block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    ChatToolTimeline(
                        block = block,
                        messageKey = messageKey,
                        blockOrdinal = blockOrdinal,
                        options = display.tools,
                        fontSizeRatio = display.typography.fontSizeRatio,
                        loading = loading,
                        model = model,
                        assistant = assistant,
                        pendingTools = pendingTools,
                        activeToolCallId = activeToolCallId,
                        onToolApproval = onToolApproval,
                        onToolAnswer = onToolAnswer,
                    )
                }
            }

            is MessagePartBlock.ChartBlock -> key(block.index) {
                val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                spec?.let { ChartCard(spec = it) }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (display.showWaitingDots && part.text.isBlank()) {
                                // The waiting surface owns empty streaming text.
                            } else if (role != MessageRole.USER && SkillWebviewCardOrNull(part)) {
                                // Agent's skill result viewer has rendered this text part.
                            } else {
                                val visualText = part.text.replaceRegexes(
                                    assistant = assistant,
                                    scope = if (role == MessageRole.USER) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT,
                                    visual = true,
                                )
                                val renderText = @Composable { text: String ->
                                    MessageTextContent(text = text, markdown = display.enableMarkdown,
                                        onClickCitation = handleClickCitation, displayOptions = display.markdownOptions)
                                }
                                if (role == MessageRole.USER) {
                                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                                        ChatMessageSurface(
                                            surface = display.textSurface,
                                            opacity = display.bubbleOpacity,
                                            modifier = Modifier.widthIn(max = maxWidth * .75f),
                                            onClick = onUserMessageClick,
                                        ) {
                                            if (display.userText.collapseChars != null) {
                                                CharacterCollapsibleUserMessageText(
                                                    messageKey = "$messageKey:${block.index}",
                                                    options = display.userText,
                                                    text = visualText,
                                                    content = renderText,
                                                )
                                            } else {
                                                CollapsibleUserMessageText(
                                                    messageKey = "$messageKey:${block.index}",
                                                    options = display.userText,
                                                ) { renderText(visualText) }
                                            }
                                        }
                                    }
                                } else {
                                    val texts = if (display.assistantSplitParagraphs)
                                        splitAssistantParagraphs(visualText) else listOf(visualText)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        texts.forEachIndexed { paragraphIndex, paragraph ->
                                            key(block.index, paragraphIndex) {
                                                ChatMessageSurface(
                                                    surface = display.textSurface,
                                                    opacity = display.bubbleOpacity,
                                                    modifier = if (role != MessageRole.ASSISTANT || display.assistantFitContent)
                                                        Modifier.wrapContentWidth(Alignment.Start) else Modifier.fillMaxWidth(),
                                                ) {
                                                    ChatStreamingText(enabled = display.streamText) {
                                                        renderText(paragraph)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.Video01,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Audio -> {
                        ChatMessageMediaTile(
                            icon = HugeIcons.MusicNote03,
                            onClick = { context.openLocalFile(part.url) },
                        )
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.large)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        ChatMessageFileChip(
                            text = part.fileName,
                            onClick = { context.openLocalFile(part.url) },
                            icon = {
                                when (part.mime) {
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.docx),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    "application/pdf" -> {
                                        Icon(
                                            painter = painterResource(R.drawable.pdf),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    else -> {
                                        Icon(
                                            imageVector = HugeIcons.File02,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            },
                        )
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        ChatMessageCitations(annotations = annotations)
    }
}
