package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWaitingPresentationTest {
    @Test fun footerCoversRequestBeforeAssistantIsCreated() {
        assertTrue(showChatFooterWaiting(null))
        MessageRole.entries.filter { it != MessageRole.ASSISTANT }.forEach { role ->
            assertTrue(showChatFooterWaiting(UIMessage(role = role, parts = emptyList())))
        }
    }

    @Test fun blankAssistantOwnsSingleWaitingIndicator() {
        assertFalse(showChatFooterWaiting(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())))
        assertFalse(showChatFooterWaiting(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(" \n")))))
    }

    @Test fun streamedTextToolsAndCheckpointsStillShowProgress() {
        listOf(UIMessagePart.Text("Ответ"), UIMessagePart.Reasoning("Мысль"),
            UIMessagePart.Tool("call", "root_exec", "{}"), UIMessagePart.Image("file:///image.png")).forEach { part ->
            assertTrue(showChatFooterWaiting(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))))
        }
        assertTrue(showChatFooterWaiting(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), isContextCheckpoint = true)))
    }
}
