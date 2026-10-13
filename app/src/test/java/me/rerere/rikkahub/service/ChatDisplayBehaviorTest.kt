package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatDisplayBehaviorTest {
    @Test fun regenerationKeepsLaterMessagesUnlessDeletionIsExplicitlyEnabled() {
        val question = MessageNode.of(UIMessage.user("Вопрос"))
        val answer = MessageNode(messages = listOf(UIMessage.assistant("Первый"), UIMessage.assistant("Выбранный")), selectIndex = 1)
        val later = MessageNode.of(UIMessage.user("Продолжение"))
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(question, answer, later))

        assertSame(source, conversationForAssistantRegeneration(source, answer.currentMessage.id, false))
        val truncated = conversationForAssistantRegeneration(source, answer.currentMessage.id, true)
        assertEquals(listOf(question, answer), truncated.messageNodes)
        assertEquals(source.config, truncated.config)
        assertEquals(source.id, truncated.id)
        assertEquals(listOf(question, answer, later), source.messageNodes)
    }

    @Test fun forkCanKeepOnlySelectedVersionsWithoutChangingTheSource() {
        val first = UIMessage.assistant("Первая версия")
        val selected = UIMessage.assistant("Выбранная версия")
        val node = MessageNode(messages = listOf(first, selected), selectIndex = 1)
        val later = MessageNode.of(UIMessage.user("Позже"))
        val nodes = listOf(node, later)

        assertEquals(listOf(node), messageNodesForFork(nodes, 0, true))
        val selectedOnly = messageNodesForFork(nodes, 0, false).single()
        assertEquals(listOf(selected), selectedOnly.messages)
        assertEquals(0, selectedOnly.selectIndex)
        assertEquals(node.id, selectedOnly.id)
        assertEquals(listOf(first, selected), node.messages)
        assertEquals(1, node.selectIndex)
    }
}
