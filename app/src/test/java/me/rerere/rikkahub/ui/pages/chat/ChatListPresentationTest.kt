package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatListPresentationTest {
    @Test fun lazyItemKeysAndCurrentNodeFlagsKeepTheirOriginalTypesAndOrder() {
        val nodes = listOf(MessageNode.of(UIMessage.user("Question")), MessageNode.of(UIMessage.assistant("Answer")))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        val idle = presentChatList(conversation, allowSystemPrompt = true, canChangeSystemPrompt = true)
        assertEquals(nodes.map { it.id } + listOf("ConversationSystemPrompt", "ScrollBottomKey"), idle.items.map { it.key })
        val idleMessages = idle.items.filterIsInstance<ChatListItemPresentation.Message>()
        assertSame(nodes[1], idleMessages[1].node)
        assertFalse(idleMessages[0].lastMessage)
        assertTrue(idleMessages[1].lastMessage)
        assertFalse(idleMessages[1].loading)
        val streaming = presentChatList(conversation, loading = true, processingStatus = "Thinking", allowSystemPrompt = true, canChangeSystemPrompt = true)
        assertEquals(nodes.map { it.id } + listOf("LoadingIndicator", "ScrollBottomKey"), streaming.items.map { it.key })
        val messages = streaming.items.filterIsInstance<ChatListItemPresentation.Message>()
        assertFalse(messages[0].loading)
        assertTrue(messages[1].loading)
        assertEquals("Thinking", streaming.items.filterIsInstance<ChatListItemPresentation.Loading>().single().processingStatus)
    }

    @Test fun systemPromptRequiresCompleteHistoryIdleAssistantPermissionAndCallback() {
        HistoryLoadState.entries.forEach { state ->
            listOf(false, true).forEach { loading ->
                listOf(false, true).forEach { allowed ->
                    listOf(false, true).forEach { callback ->
                        val actual = presentChatList(Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), historyLoadState = state), loading,
                            allowSystemPrompt = allowed, canChangeSystemPrompt = callback)
                        assertEquals(state == HistoryLoadState.COMPLETE && !loading && allowed && callback,
                            actual.items.any { it is ChatListItemPresentation.SystemPrompt })
                        assertEquals("ScrollBottomKey", actual.items.last().key)
                    }
                }
            }
        }
    }

    @Test fun incompleteAndNotLoadedHistoryMakeEveryNodeReadOnly() {
        val node = MessageNode.of(UIMessage.assistant("Answer"))
        HistoryLoadState.entries.forEach { state ->
            val actual = presentChatList(Conversation(assistantId = Uuid.random(), messageNodes = listOf(node), historyLoadState = state))
            assertEquals(state != HistoryLoadState.COMPLETE,
                actual.items.filterIsInstance<ChatListItemPresentation.Message>().single().readOnly)
        }
    }

    @Test fun errorsStaySeparateFromLazyRowsAndKeepTheirOriginalIdentityAndOrder() {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val local = ChatError(error = Throwable("Local"), conversationId = conversation.id)
        val global = ChatError(error = Throwable("Global"))
        val actual = presentChatList(conversation, errors = listOf(global, local))
        assertEquals(listOf("ScrollBottomKey"), actual.items.map { it.key })
        assertEquals(listOf(global.id, local.id), actual.errors.map { it.key })
        assertSame(global, actual.errors[0].error)
        assertEquals(conversation.id, actual.errors[1].conversationId)
    }

    @Test fun pendingToolsUseOnlySelectedAlternativeAndKeepOriginalPartPositions() {
        val pending = UIMessagePart.Tool("call", "root_exec", "{}", approvalState = ToolApprovalState.Pending)
        val blankId = pending.copy(toolCallId = "", toolName = "ask_user")
        val executed = pending.copy(toolCallId = "executed", output = listOf(UIMessagePart.Text("Done")))
        val first = UIMessage.user("First").copy(parts = listOf(pending))
        val selected = UIMessage.assistant("Selected").copy(parts = listOf(UIMessagePart.Text("Before"), pending, blankId, executed))
        val node = MessageNode(messages = listOf(first, selected), selectIndex = 1)
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))
        val actual = presentChatList(conversation).items.filterIsInstance<ChatListItemPresentation.Message>().single()
        assertEquals(listOf(1, 2), actual.pendingTools.map { it.key.partIndex })
        assertEquals(listOf("call", ""), actual.pendingTools.map { it.key.toolCallId })
        actual.pendingTools.forEach {
            assertEquals(conversation.id, it.key.conversationId)
            assertEquals(node.id, it.key.nodeId)
            assertEquals(selected.id, it.key.messageId)
        }
        assertEquals(listOf(ChatPendingToolKind.Approval, ChatPendingToolKind.Answer), actual.pendingTools.map { it.kind })
        assertEquals(pending.toolCallId, actual.pendingTools[0].renderKey)
        assertEquals(blankId.hashCode().toString(), actual.pendingTools[1].renderKey)
        val switched = presentChatList(conversation.copy(messageNodes = listOf(node.copy(selectIndex = 0))))
            .items.filterIsInstance<ChatListItemPresentation.Message>().single()
        assertEquals(first.id, switched.pendingTools.single().key.messageId)
        assertEquals(node.id, switched.key)
    }

    @Test fun blankToolIdsAndMultiplePendingCallsDoNotSharePresentationIdentity() {
        val tool = UIMessagePart.Tool("", "root_exec", "{}", approvalState = ToolApprovalState.Pending)
        val message = UIMessage.assistant("").copy(parts = listOf(tool, tool.copy()))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode.of(message)))
        val pending = presentChatList(conversation).items.filterIsInstance<ChatListItemPresentation.Message>().single().pendingTools
        assertEquals(2, pending.map { it.key }.distinct().size)
        assertEquals(listOf(0, 1), pending.map { it.key.partIndex })
    }

    @Test fun pendingDescriptorsFollowApprovalStateAndExecutionWithoutChangingHistory() {
        val states = listOf(ToolApprovalState.Auto, ToolApprovalState.Pending, ToolApprovalState.Approved,
            ToolApprovalState.Denied("No"), ToolApprovalState.Answered("Yes"))
        states.forEach { state ->
            listOf(false, true).forEach { executed ->
                val tool = UIMessagePart.Tool("call", "root_exec", "{}",
                    output = if (executed) listOf(UIMessagePart.Text("Done")) else emptyList(), approvalState = state)
                val node = MessageNode.of(UIMessage.assistant("").copy(parts = listOf(tool)))
                val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))
                val item = presentChatList(conversation).items.filterIsInstance<ChatListItemPresentation.Message>().single()
                assertEquals(state == ToolApprovalState.Pending && !executed, item.pendingTools.isNotEmpty())
                assertSame(node, item.node)
                assertEquals(listOf(tool), item.node.currentMessage.parts)
            }
        }
    }

    @Test fun checkpointPendingPartsDoNotDescribeInvisibleToolInteractions() {
        val tools = listOf("root_exec", "ask_user").map {
            UIMessagePart.Tool("call_$it", it, "{}", approvalState = ToolApprovalState.Pending)
        }
        val node = MessageNode.of(UIMessage.user("Summary").copy(isContextCheckpoint = true, parts = tools))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))
        val item = presentChatList(conversation).items.filterIsInstance<ChatListItemPresentation.Message>().single()
        assertTrue(item.pendingTools.isEmpty())
        assertSame(node, item.node)
    }
}
