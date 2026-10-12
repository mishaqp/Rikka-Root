package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDisplayPresentationTest {
    private fun message(role: MessageRole, parts: List<UIMessagePart> = listOf(UIMessagePart.Text("Message"))) =
        UIMessage(role = role, parts = parts)

    @Test fun roleSurfacesAndSavedOpacityRemainIndependentOfReadOnly() {
        MessageRole.entries.forEach { role ->
            listOf(false, true).forEach { readOnly ->
                listOf(false, true).forEach { bubble ->
                    listOf(1f, .5f).forEach { opacity ->
                        val settings = DisplaySetting(showAssistantBubble = bubble, bubbleOpacity = opacity)
                        val result = presentMessageDisplay(message(role), settings, readOnly = readOnly)
                        assertEquals(role == MessageRole.USER, result.alignEnd)
                        assertEquals(when {
                            role == MessageRole.USER -> MessageTextSurface.USER
                            bubble -> MessageTextSurface.ASSISTANT
                            else -> MessageTextSurface.NONE
                        }, result.textSurface)
                        assertEquals(opacity, result.bubbleOpacity)
                    }
                }
            }
        }
    }

    @Test fun hiddenIdentityConsumesNoHeaderSpaceAndTimeDoesNotDependOnActions() {
        val hidden = DisplaySetting(showUserAvatar = false, showModelIcon = false, showModelName = false)
        assertFalse(presentMessageDisplay(message(MessageRole.USER), hidden).showHeader)
        assertFalse(presentMessageDisplay(message(MessageRole.ASSISTANT), hidden, hasAssistantIdentity = true).showHeader)
        val timed = hidden.copy(showDateTimeInMessage = true, showUserMessageActions = false)
        listOf(MessageRole.USER, MessageRole.ASSISTANT).forEach { role ->
            val result = presentMessageDisplay(message(role), timed, loading = true, lastMessage = true)
            assertTrue(result.header.showTimestamp)
            assertTrue(result.showHeader)
            assertFalse(result.showActions)
        }
        listOf(MessageRole.SYSTEM, MessageRole.TOOL).forEach { role ->
            assertFalse(presentMessageDisplay(message(role), timed).showHeader)
        }
    }

    @Test fun identitySwitchesRetainExistingAssistantPolicyAndIndependentIconNameToggles() {
        val user = presentMessageDisplay(message(MessageRole.USER), DisplaySetting(showUserAvatar = true))
        assertTrue(user.header.showUserIdentity)
        listOf(false, true).forEach { icon ->
            listOf(false, true).forEach { name ->
                listOf(false, true).forEach { identity ->
                    val result = presentMessageDisplay(message(MessageRole.ASSISTANT),
                        DisplaySetting(showModelIcon = icon, showModelName = name), hasAssistantIdentity = identity)
                    assertEquals(identity && icon, result.header.showAssistantIcon)
                    assertEquals(identity && name, result.header.showAssistantName)
                    assertEquals(identity && (icon || name), result.showHeader)
                }
            }
        }
    }

    @Test fun actionPreferenceKeepsBranchesAndCapabilityRestrictions() {
        MessageRole.entries.forEach { role ->
            listOf(false, true).forEach { readOnly ->
                val message = message(role)
                val settings = DisplaySetting(showUserMessageActions = false)
                val result = presentMessageDisplay(message, settings, readOnly = readOnly, hasBranches = true)
                assertEquals(role != MessageRole.USER, result.showActions)
                assertEquals(role == MessageRole.USER, result.showBranchSelector)
                val capabilities = presentChatMessage(message, readOnly = readOnly)
                assertEquals(!readOnly && role == MessageRole.USER, capabilities.canEditFromBubble)
                assertEquals(!readOnly, capabilities.canInteractWithTools)
            }
        }
    }

    @Test fun collapseUsesRenderedExtentRatherThanTextCharactersOrNewlines() {
        assertFalse(presentUserTextViewport(90, 10f, 9, enabled = true, expanded = false).overflowing)
        val long = presentUserTextViewport(91, 10f, 9, enabled = true, expanded = false)
        assertTrue(long.overflowing)
        assertEquals(90, long.viewportHeight)
        assertTrue(long.fadeBottom)
        assertEquals(91, presentUserTextViewport(91, 10f, 9, true, true).viewportHeight)
        assertFalse(presentUserTextViewport(91, 10f, 9, false, false).fadeBottom)
        assertEquals(180, presentUserTextViewport(300, 20f, 9, true, false).viewportHeight)
        MessageRole.entries.forEach { role ->
            assertEquals(role == MessageRole.USER, presentMessageDisplay(message(role), DisplaySetting()).userText.enabled)
        }
    }

    @Test fun waitingIsOnlyForBlankStreamingAssistantIncludingEmptyMedia() {
        val empty = listOf(emptyList(), listOf(UIMessagePart.Text(" \n")), listOf(UIMessagePart.Image("")))
        MessageRole.entries.forEach { role ->
            listOf(false, true).forEach { loading ->
                empty.forEach { parts ->
                    val result = presentMessageDisplay(message(role, parts), DisplaySetting(), loading = loading)
                    assertEquals(role == MessageRole.ASSISTANT && loading, result.showWaitingDots)
                }
                listOf<UIMessagePart>(UIMessagePart.Text("Text"), UIMessagePart.Reasoning("Thinking"),
                    UIMessagePart.Image("file:///image.png"), UIMessagePart.Tool("call", "root_exec", "{}")).forEach { part ->
                    assertFalse(presentMessageDisplay(message(role, listOf(part)), DisplaySetting(), loading = loading).showWaitingDots)
                }
            }
        }
    }

    @Test fun checkpointsBypassEveryRegularSurfaceAndAffordance() {
        MessageRole.entries.forEach { role ->
            val message = message(role).copy(isContextCheckpoint = true)
            val result = presentMessageDisplay(message, DisplaySetting(showUserAvatar = true, showDateTimeInMessage = true),
                loading = true, hasAssistantIdentity = true, hasBranches = true)
            assertEquals(MessageTextSurface.NONE, result.textSurface)
            assertFalse(result.showHeader)
            assertFalse(result.showActions)
            assertFalse(result.showBranchSelector)
            assertFalse(result.showWaitingDots)
            assertFalse(result.userText.enabled)
            assertFalse(result.streamText)
            assertFalse(result.showTokenUsage)
        }
    }

    @Test fun branchVisibilityFollowsExistingLoadingGateForBothActionPreferences() {
        listOf(false, true).forEach { actions ->
            listOf(false, true).forEach { loading ->
                listOf(false, true).forEach { last ->
                    listOf(false, true).forEach { readOnly ->
                        listOf(false, true).forEach { branches ->
                            val message = message(MessageRole.USER)
                            val capability = presentChatMessage(message, loading, last, readOnly)
                            val result = presentMessageDisplay(message, DisplaySetting(showUserMessageActions = actions),
                                loading = loading, lastMessage = last, readOnly = readOnly, hasBranches = branches)
                            assertEquals(capability.showActions && actions, result.showActions)
                            assertEquals(capability.showActions && !actions && branches, result.showBranchSelector)
                            assertFalse(result.showActions && result.showBranchSelector)
                            assertEquals(!readOnly, capability.canEditFromBubble)
                        }
                    }
                }
            }
        }
    }

    @Test fun malformedCollapseLineCountStillProvidesAUsableViewport() {
        val result = presentMessageDisplay(message(MessageRole.USER), DisplaySetting(userMessageCollapseLines = 0))
        assertEquals(1, result.userText.visibleLines)
        assertEquals(10, presentUserTextViewport(50, 10f, 0, true, false).viewportHeight)
        assertEquals(45, presentMessageDisplay(message(MessageRole.USER), DisplaySetting(userMessageCollapseLines = 45)).userText.visibleLines)
    }

    @Test fun tokenPreferenceRemainsIndependentOfUserActionsForEveryRoleWithRealUsage() {
        MessageRole.entries.forEach { role ->
            listOf(false, true).forEach { tokens ->
                val settings = DisplaySetting(showTokenUsage = tokens, showUserAvatar = false, showUserMessageActions = false)
                val message = message(role).copy(usage = TokenUsage(promptTokens = 100, completionTokens = 42, totalTokens = 142))
                val result = presentMessageDisplay(message, settings)
                assertEquals(tokens, result.showTokenUsage)
                assertEquals(role != MessageRole.USER, result.showActions)
                assertFalse(result.showHeader)
            }
        }
    }

    @Test fun rendererOptionsReflectSavedFontsMarkdownAndFutureToolPreferences() {
        val settings = DisplaySetting(fontSizeRatio = 1.4f, chatFontFamily = ChatFontFamily.CUSTOM,
            chatCustomFontPath = "fonts/reader.ttf", enableLatexRendering = false, codeBlockAutoWrap = true,
            codeBlockAutoCollapse = true, showLineNumbers = true, userMessageCollapseLines = 12,
            showToolSummary = false, hideToolResultImages = true, autoCollapseToolSteps = false,
            showThinkingContent = false, autoCloseThinking = false)
        val result = presentMessageDisplay(message(MessageRole.USER), settings)
        assertEquals(1.4f, result.typography.fontSizeRatio)
        assertEquals(ChatFontFamily.CUSTOM, result.typography.fontFamily)
        assertEquals("fonts/reader.ttf", result.typography.customFontPath)
        assertFalse(result.markdownOptions.enableLatexRendering)
        assertTrue(result.markdownOptions.codeBlockAutoWrap)
        assertTrue(result.markdownOptions.codeBlockAutoCollapse)
        assertTrue(result.markdownOptions.showLineNumbers)
        assertEquals(1.4f, result.markdownOptions.fontSizeRatio)
        assertEquals(12, result.userText.visibleLines)
        assertFalse(result.tools.showSummary)
        assertTrue(result.tools.hideResultImages)
        assertFalse(result.tools.autoCollapseSteps)
        assertFalse(result.tools.showThinkingContent)
        assertFalse(result.tools.autoCloseThinking)
    }
}
