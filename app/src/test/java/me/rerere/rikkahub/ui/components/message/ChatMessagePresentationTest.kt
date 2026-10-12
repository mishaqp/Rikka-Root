package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Characterizes the production conditions before the message layout is redesigned. */
class ChatMessagePresentationTest {
    @Suppress("DEPRECATION")
    @Test fun toolbarUsesLastNodeAndLoadingRatherThanRoleOrFinishedAt() {
        val contents = listOf(
            emptyList<UIMessagePart>() to false,
            listOf(UIMessagePart.Text(" \n")) to false,
            listOf(UIMessagePart.Text("Message")) to true,
            listOf(UIMessagePart.Reasoning("")) to false,
            listOf(UIMessagePart.Reasoning("Reasoning")) to true,
            listOf(UIMessagePart.Image("")) to false,
            listOf(UIMessagePart.Image("file:///image.png")) to true,
            listOf(UIMessagePart.Document("", "report.pdf")) to false,
            listOf(UIMessagePart.Document("file:///report.pdf", "report.pdf")) to true,
            listOf(UIMessagePart.Audio(""), UIMessagePart.Video("")) to false,
            listOf(UIMessagePart.Audio("file:///audio.wav"), UIMessagePart.Video("file:///video.mp4")) to true,
            listOf(UIMessagePart.Tool("call", "root_exec", "{}")) to true,
            listOf(UIMessagePart.ServerTool("server", "web_search", status = ServerToolStatus.COMPLETED)) to true,
            listOf(UIMessagePart.ToolCall("legacy", "tool", "{}"), UIMessagePart.Search) to false,
        )
        MessageRole.entries.forEach { role ->
            contents.forEach { (parts, hasContent) ->
                listOf(false, true).forEach { lastMessage ->
                    listOf(false, true).forEach { loading ->
                        listOf(false, true).forEach { readOnly ->
                            val message = UIMessage(role = role, parts = parts)
                            val actual = presentChatMessage(message, loading, lastMessage, readOnly)
                            assertEquals("$role parts=$parts last=$lastMessage loading=$loading readOnly=$readOnly",
                                if (lastMessage) !loading else hasContent, actual.showActions)
                            assertEquals(hasContent, actual.showAvatarRow)
                            assertEquals(message.id, actual.messageId)
                            assertEquals(role, actual.role)
                        }
                    }
                }
            }
        }
    }

    @Test fun checkpointBypassesRegularMessageSurfacesForEveryRoleAndState() {
        MessageRole.entries.forEach { role ->
            listOf(false, true).forEach { loading ->
                listOf(false, true).forEach { lastMessage ->
                    val message = UIMessage(role = role, isContextCheckpoint = true, parts = listOf(UIMessagePart.Text("Summary")))
                    val actual = presentChatMessage(message, loading = loading, lastMessage = lastMessage)
                    val readOnly = presentChatMessage(message, loading = loading, lastMessage = lastMessage, readOnly = true)
                    assertTrue(readOnly.isCheckpoint)
                    assertFalse(readOnly.canEditFromBubble)
                    assertFalse(readOnly.canInteractWithTools)
                    assertTrue(actual.isCheckpoint)
                    assertFalse(actual.showActions)
                    assertFalse(actual.showAvatarRow)
                    assertFalse(actual.canEditFromBubble)
                    assertFalse(actual.canInteractWithTools)
                }
            }
        }
    }

    @Test fun rolesAndReadOnlyKeepExistingActionCapabilitiesForAllCallbackCombinations() {
        MessageRole.entries.forEach { role ->
            listOf(false, true).forEach { readOnly ->
                listOf(false, true).forEach { canTranslate ->
                    listOf(false, true).forEach { canToggleFavorite ->
                        val message = UIMessage(role = role, parts = listOf(UIMessagePart.Text("Message")))
                        val actual = presentChatMessageActions(message, readOnly, canTranslate, canToggleFavorite)
                        assertEquals(!readOnly, actual.mutationsEnabled)
                        assertEquals(role == MessageRole.USER, actual.confirmRegeneration)
                        assertEquals(role == MessageRole.ASSISTANT, actual.showTts)
                        assertEquals(role == MessageRole.ASSISTANT, actual.canSpeak(true))
                        assertFalse(actual.canSpeak(false))
                        assertEquals(role == MessageRole.ASSISTANT && !readOnly && canTranslate, actual.showTranslation)
                        val expected = buildList {
                            add(ChatMessageSheetAction.SelectAndCopy)
                            add(ChatMessageSheetAction.WebPreview)
                            if (!readOnly) add(ChatMessageSheetAction.Edit)
                            add(ChatMessageSheetAction.Share)
                            if (!readOnly) add(ChatMessageSheetAction.Fork)
                            if (!readOnly && canToggleFavorite) add(ChatMessageSheetAction.Favorite)
                            if (!readOnly) add(ChatMessageSheetAction.Delete)
                        }
                        assertEquals(expected, actual.sheetActions)
                        val presentation = presentChatMessage(message, readOnly = readOnly)
                        assertEquals(!readOnly && role == MessageRole.USER, presentation.canEditFromBubble)
                        assertEquals(!readOnly, presentation.canInteractWithTools)
                    }
                }
            }
        }
    }

    @Test fun previewNeedsTopLevelNonblankTextWhileToolsStillMakeMessageNonempty() {
        val parts = listOf(
            emptyList(), listOf(UIMessagePart.Text(" \n")), listOf(UIMessagePart.Reasoning("Reasoning")),
            listOf(UIMessagePart.Image("file:///image.png")),
            listOf(UIMessagePart.Tool("call", "search_web", "{}", listOf(UIMessagePart.Text("Nested text")))),
        )
        parts.forEach { content ->
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = content)
            assertFalse(presentChatMessageActions(message).sheetActions.contains(ChatMessageSheetAction.WebPreview))
        }
        val tool = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Tool("call", "root_exec", "{}")))
        assertTrue(presentChatMessage(tool).showAvatarRow)
        assertTrue(presentChatMessage(tool).showActions)
        val withText = tool.copy(parts = tool.parts + UIMessagePart.Text("Text"))
        assertTrue(presentChatMessageActions(withText).sheetActions.contains(ChatMessageSheetAction.WebPreview))
    }
}
