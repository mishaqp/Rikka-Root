package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ChatBubbleStyleOverrides
import me.rerere.rikkahub.data.datastore.ChatMessageBackgroundStyle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.*
import org.junit.Test

class ChatCustomizationPresentationTest {
    private fun message(role: MessageRole) = UIMessage(role = role, parts = listOf(UIMessagePart.Text("Текст")))

    @Test fun legacyHeaderChoicesInheritUntilEachRoleIsExplicitlyChanged() {
        val old = DisplaySetting(showUserAvatar = true, showDateTimeInMessage = true)
        val user = presentMessageDisplay(message(MessageRole.USER), old)
        assertTrue(user.header.showUserIdentity)
        assertTrue(user.header.showUserAvatar)
        assertTrue(user.header.showTimestamp)
        val changed = old.copy(showUserName = false, showUserTimestamp = false, showAssistantTimestamp = true)
        val newUser = presentMessageDisplay(message(MessageRole.USER), changed)
        assertFalse(newUser.header.showUserIdentity)
        assertTrue(newUser.header.showUserAvatar)
        assertFalse(newUser.header.showTimestamp)
        assertTrue(presentMessageDisplay(message(MessageRole.ASSISTANT), changed).header.showTimestamp)
        val nameOnly = presentMessageDisplay(message(MessageRole.USER), DisplaySetting(showUserName = true))
        assertTrue(nameOnly.showHeader)
        assertTrue(nameOnly.header.showUserIdentity)
        assertFalse(nameOnly.header.showUserAvatar)
    }

    @Test fun roleMarkdownAndProviderChoicesNeverAffectCapabilitiesOrCheckpoints() {
        val saved = DisplaySetting(enableUserMarkdown = false, enableAssistantMarkdown = true,
            showProviderInChatMessage = true, assistantBubbleFitContent = true, assistantBubbleSplitParagraphs = true)
        assertFalse(presentMessageDisplay(message(MessageRole.USER), saved).enableMarkdown)
        val assistant = presentMessageDisplay(message(MessageRole.ASSISTANT), saved, readOnly = true)
        assertTrue(assistant.enableMarkdown)
        assertTrue(assistant.header.showProvider)
        assertTrue(assistant.assistantFitContent)
        assertTrue(assistant.assistantSplitParagraphs)
        assertFalse(presentChatMessage(message(MessageRole.ASSISTANT), readOnly = true).canInteractWithTools)
        assertFalse(presentMessageDisplay(message(MessageRole.ASSISTANT).copy(isContextCheckpoint = true), saved).showHeader)
        assertFalse(presentMessageDisplay(message(MessageRole.SYSTEM), saved).header.showProvider)
        assertTrue(presentMessageDisplay(message(MessageRole.SYSTEM), saved.copy(enableAssistantMarkdown = false)).enableMarkdown)
        assertTrue(presentMessageDisplay(message(MessageRole.TOOL), saved.copy(enableAssistantMarkdown = false)).enableMarkdown)
    }

    @Test fun hiddenToolCardsKeepApprovalsAnswersErrorsAndPendingRuntimeRequests() {
        fun tool(id: String, approval: ToolApprovalState = ToolApprovalState.Auto, output: String? = null, name: String = "root_exec") =
            ThinkingStep.ToolStep(UIMessagePart.Tool(id, name, "{}", approvalState = approval,
                output = output?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList()))
        val steps = listOf(tool("done", output = "{\"success\":true}"),
            tool("approval", ToolApprovalState.Pending), tool("question", ToolApprovalState.Pending, name = "ask_user"),
            tool("error", output = "{\"success\":false}"), tool("runtime-pending", output = "{}"))
        val options = presentMessageDisplay(message(MessageRole.ASSISTANT), DisplaySetting(showToolCards = false)).tools
        val result = presentTimeline(steps, options, false, "m", 0, pendingToolCallIds = setOf("runtime-pending"))
        assertEquals(listOf("approval", "question", "error", "runtime-pending"),
            result.steps.map { (it.step as ThinkingStep.ToolStep).tool.toolCallId })
        assertFalse(result.canFoldToSummary)
        assertEquals(listOf(0, 1, 2, 3), result.collapsedVisibleIndices)
        assertEquals(5, steps.size)
    }

    @Test fun explicitCharacterPreviewExpandsWithoutChangingMessageSourceOrSplittingEmoji() {
        val source = "а".repeat(49) + "🙂" + " продолжение"
        val options = UserTextDisplayOptions(true, 9, 50)
        val preview = presentUserCharacterPreview(source, options, false)
        assertTrue(preview.overflowing)
        assertEquals("а".repeat(49) + "…", preview.text)
        assertEquals(source, presentUserCharacterPreview(source, options, true).text)
        assertEquals(source, presentUserCharacterPreview(source, options.copy(enabled = false), false).text)
        assertEquals(source, presentUserCharacterPreview(source, options.copy(collapseChars = null), false).text)
    }

    @Test fun hiddenThinkingCardsRemoveOnlyReasoningWhileLegacyPreviewChoiceKeepsItsTitle() {
        val reasoning = ThinkingStep.ReasoningStep(UIMessagePart.Reasoning("Открытый план"))
        val approval = ThinkingStep.ToolStep(UIMessagePart.Tool("approve", "root_exec", "{}",
            approvalState = ToolApprovalState.Pending))
        val source = mutableListOf<ThinkingStep>(reasoning, approval)
        val settings = DisplaySetting(showThinkingContent = false, showToolCards = false)
        val legacy = presentMessageDisplay(message(MessageRole.ASSISTANT), settings).tools
        assertEquals(listOf(reasoning, approval), presentTimeline(source, legacy, true, "m", 0).steps.map { it.step })
        val hidden = presentMessageDisplay(message(MessageRole.ASSISTANT), settings.copy(showThinkingCards = false)).tools
        assertEquals(listOf(approval), presentTimeline(source, hidden, true, "m", 0).steps.map { it.step })
        assertFalse(presentTimeline(source, hidden, true, "m", 0).canFoldToSummary)
        assertEquals(listOf(reasoning, approval), source)
    }

    @Test fun explicitTimelineCollapseChoiceCanDisableAndReenableLegacyFold() {
        val steps = (0..3).map { index -> ThinkingStep.ToolStep(UIMessagePart.Tool("tool-$index", "root_exec", "{}",
            output = listOf(UIMessagePart.Text("{\"success\":true}")))) }
        val legacy = presentMessageDisplay(message(MessageRole.ASSISTANT), DisplaySetting()).tools
        val inherited = presentTimeline(steps, legacy, false, "m", 0)
        assertEquals(listOf(2, 3), inherited.collapsedVisibleIndices)
        assertTrue(inherited.canFoldToSummary)
        val off = presentTimeline(steps, legacy.copy(collapseThinkingSteps = false), false, "m", 0)
        assertEquals(listOf(0, 1, 2, 3), off.collapsedVisibleIndices)
        assertFalse(off.canFoldToSummary)
        val on = presentTimeline(steps, legacy.copy(autoCollapseSteps = false, showSummary = false, collapseThinkingSteps = true), false, "m", 0)
        assertEquals(listOf(2, 3), on.collapsedVisibleIndices)
        assertTrue(on.canFoldToSummary)
    }

    @Test fun customBubbleRolesThemesAndEconomyRemainIndependent() {
        val colors = lightColorScheme()
        val settings = DisplaySetting(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED,
            userChatBubbleStyleOverrides = ChatBubbleStyleOverrides(backgroundArgbLight = 0xff112233,
                backgroundArgbDark = 0xff445566, textArgbLight = 0xffaabbcc))
        val light = resolveMessageBubbleStyle(MessageTextSurface.USER, colors, false, settings)
        val dark = resolveMessageBubbleStyle(MessageTextSurface.USER, colors, true, settings)
        assertEquals(Color(0xff112233).red, light.background.red, .001f)
        assertEquals(Color(0xff445566).red, dark.background.red, .001f)
        assertEquals(Color(0xffaabbcc), light.text)
        assertEquals(14f, light.blurSigma)
        assertEquals(0f, resolveMessageBubbleStyle(MessageTextSurface.USER, colors, false, settings.copy(glassEconomy = true)).blurSigma)
        assertEquals(light.background, resolveMessageBubbleStyle(MessageTextSurface.USER, colors, false, settings.copy(glassEconomy = true)).background)
        assertEquals(colors.surfaceContainerHigh.red,
            resolveMessageBubbleStyle(MessageTextSurface.ASSISTANT, colors, false, settings).background.red, .001f)
        assertEquals(messageSurfaceColor(MessageTextSurface.USER, colors, false, 1f),
            resolveMessageBubbleStyle(MessageTextSurface.USER, colors, false, settings.copy(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.DEFAULT)).background)
    }

    @Test fun savedBubbleGeometryBorderAndStyleOpacityReachThePaintedSurface() {
        val settings = DisplaySetting(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.FROSTED, bubbleOpacity = .5f,
            assistantChatBubbleStyleOverrides = ChatBubbleStyleOverrides(borderArgbLight = 0xff334455,
                borderWidth = 1.4f, borderOpacity = .7f, cornerRadius = 24f, blurSigma = 8f,
                frostedOpacity = .2f, solidOpacity = .8f))
        val frosted = resolveMessageBubbleStyle(MessageTextSurface.ASSISTANT, lightColorScheme(), false, settings)
        assertEquals(.1f, frosted.background.alpha, .003f)
        assertEquals(.7f, frosted.border.alpha, .003f)
        assertEquals(Color(0xff334455).red, frosted.border.red, .001f)
        assertEquals(1.4f, frosted.borderWidth)
        assertEquals(24f, frosted.radius)
        assertEquals(8f, frosted.blurSigma)
        val solid = resolveMessageBubbleStyle(MessageTextSurface.ASSISTANT, lightColorScheme(), false,
            settings.copy(chatMessageBackgroundStyle = ChatMessageBackgroundStyle.SOLID))
        assertEquals(.4f, solid.background.alpha, .003f)
        assertEquals(0f, solid.blurSigma)
        assertEquals(frosted.radius, solid.radius)
    }
}
