package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.richtext.MarkdownDisplayOptions
import kotlin.math.roundToInt

/** Visual choices only. Message permissions remain in [ChatMessagePresentation]. */
data class MessageDisplayPresentation(
    val alignEnd: Boolean,
    val textSurface: MessageTextSurface,
    val bubbleOpacity: Float,
    val header: MessageHeaderOptions,
    val typography: MessageTypographyOptions,
    val markdownOptions: MarkdownDisplayOptions,
    val userText: UserTextDisplayOptions,
    val tools: ToolDisplayOptions,
    val showActions: Boolean,
    val showTokenUsage: Boolean,
    val showBranchSelector: Boolean,
    val streamText: Boolean,
    val showWaitingDots: Boolean,
) {
    val showHeader: Boolean get() = header.showUserIdentity || header.showAssistantIcon ||
        header.showAssistantName || header.showTimestamp
}

enum class MessageTextSurface { NONE, USER, ASSISTANT }

data class MessageHeaderOptions(
    val showUserIdentity: Boolean,
    val showAssistantIcon: Boolean,
    val showAssistantName: Boolean,
    val showTimestamp: Boolean,
    val userAvatar: Avatar,
    val userNickname: String,
)

data class MessageTypographyOptions(
    val fontSizeRatio: Float,
    val fontFamily: ChatFontFamily,
    val customFontPath: String,
)

data class UserTextDisplayOptions(val enabled: Boolean, val visibleLines: Int)

/** Independent stored preferences for the tool timeline and reasoning display. */
data class ToolDisplayOptions(
    val showSummary: Boolean,
    val hideResultImages: Boolean,
    val autoCollapseSteps: Boolean,
    val showThinkingContent: Boolean,
    val autoCloseThinking: Boolean,
)

fun presentMessageDisplay(
    message: UIMessage,
    settings: DisplaySetting,
    loading: Boolean = false,
    lastMessage: Boolean = false,
    readOnly: Boolean = false,
    hasAssistantIdentity: Boolean = false,
    hasBranches: Boolean = false,
): MessageDisplayPresentation {
    val capability = presentChatMessage(message, loading, lastMessage, readOnly)
    val regular = !capability.isCheckpoint
    val user = message.role == MessageRole.USER
    val assistant = message.role == MessageRole.ASSISTANT
    val hasContent = !message.parts.isEmptyUIMessage()
    val showRoleHeader = regular && (hasContent || assistant && loading)
    val showActions = capability.showActions && (!user || settings.showUserMessageActions)
    return MessageDisplayPresentation(
        alignEnd = user,
        textSurface = when {
            !regular -> MessageTextSurface.NONE
            user -> MessageTextSurface.USER
            settings.showAssistantBubble -> MessageTextSurface.ASSISTANT
            else -> MessageTextSurface.NONE
        },
        bubbleOpacity = settings.bubbleOpacity.coerceIn(0f, 1f),
        header = MessageHeaderOptions(
            showUserIdentity = showRoleHeader && user && settings.showUserAvatar,
            showAssistantIcon = showRoleHeader && assistant && hasAssistantIdentity && settings.showModelIcon,
            showAssistantName = showRoleHeader && assistant && hasAssistantIdentity && settings.showModelName,
            showTimestamp = showRoleHeader && (user || assistant) && settings.showDateTimeInMessage,
            userAvatar = settings.userAvatar,
            userNickname = settings.userNickname,
        ),
        typography = MessageTypographyOptions(settings.fontSizeRatio, settings.chatFontFamily, settings.chatCustomFontPath),
        markdownOptions = MarkdownDisplayOptions(
            enableLatexRendering = settings.enableLatexRendering,
            codeBlockAutoWrap = settings.codeBlockAutoWrap,
            codeBlockAutoCollapse = settings.codeBlockAutoCollapse,
            showLineNumbers = settings.showLineNumbers,
            fontSizeRatio = settings.fontSizeRatio,
        ),
        userText = UserTextDisplayOptions(regular && user && settings.collapseLongUserMessages,
            settings.userMessageCollapseLines.coerceAtLeast(1)),
        tools = ToolDisplayOptions(settings.showToolSummary, settings.hideToolResultImages, settings.autoCollapseToolSteps,
            settings.showThinkingContent, settings.autoCloseThinking),
        showActions = showActions,
        // Moru hides the token line while the reply is still streaming.
        showTokenUsage = regular && settings.showTokenUsage && !(assistant && loading),
        showBranchSelector = capability.showActions && user && !showActions && hasBranches,
        streamText = regular && assistant && loading,
        showWaitingDots = regular && assistant && loading && !hasContent,
    )
}

data class UserTextViewport(
    val overflowing: Boolean,
    val viewportHeight: Int,
    val fadeBottom: Boolean,
)

/** Natural rendered content, including Markdown blocks, supplies the extent, never source length. */
fun presentUserTextViewport(
    naturalHeight: Int,
    lineHeightPx: Float,
    visibleLines: Int,
    enabled: Boolean,
    expanded: Boolean,
): UserTextViewport {
    val collapsedHeight = (lineHeightPx.coerceAtLeast(1f) * visibleLines.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
    val overflowing = enabled && naturalHeight > collapsedHeight
    return UserTextViewport(
        overflowing = overflowing,
        viewportHeight = if (overflowing && !expanded) collapsedHeight else naturalHeight,
        fadeBottom = overflowing && !expanded,
    )
}
