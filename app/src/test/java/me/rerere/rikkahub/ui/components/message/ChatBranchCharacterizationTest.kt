package me.rerere.rikkahub.ui.components.message

import java.time.Instant
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.createForkConversation
import me.rerere.ai.core.ReasoningLevel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Calls the production conversation tree; no test-only branch implementation. */
class ChatBranchCharacterizationTest {
    private val first = UIMessage.user("First question")
    private val answer = UIMessage.assistant("First answer")
    private val alternative = UIMessage.assistant("Alternative answer")
    private val userNode = MessageNode.of(first)
    private val answerNode = MessageNode(messages = listOf(answer, alternative), selectIndex = 1, isFavorite = true)
    private val conversation = Conversation(
        id = Uuid.parse("51000000-0000-0000-0000-000000000001"),
        assistantId = Uuid.parse("51000000-0000-0000-0000-000000000002"),
        title = "Characterization",
        messageNodes = listOf(userNode, answerNode),
        createAt = Instant.parse("2026-01-02T03:04:05Z"),
        updateAt = Instant.parse("2026-01-02T04:05:06Z"),
    )

    @Test fun selectedBranchesAreTheOnlyCurrentConversationMessages() {
        assertEquals(listOf(first, alternative), conversation.currentMessages)
        assertSame(alternative, answerNode.currentMessage)
        assertEquals(listOf(first, answer), conversation.copy(
            messageNodes = listOf(userNode, answerNode.copy(selectIndex = 0)),
        ).currentMessages)
    }

    @Test fun editingAnExistingSelectedMessagePreservesOtherBranchesAndNodeIdentity() {
        val edited = alternative.copy(parts = listOf(UIMessagePart.Text("Edited answer")))
        val updated = conversation.updateCurrentMessages(listOf(first, edited))
        assertEquals(listOf(answer, edited), updated.messageNodes[1].messages)
        assertEquals(1, updated.messageNodes[1].selectIndex)
        assertEquals(answerNode.id, updated.messageNodes[1].id)
        assertEquals(true, updated.messageNodes[1].isFavorite)
        assertSame(userNode.messages.first(), updated.currentMessages.first())
        assertEquals(conversation.id, updated.id)
        assertEquals(conversation.title, updated.title)
        assertEquals(conversation.createAt, updated.createAt)
        assertEquals(conversation.updateAt, updated.updateAt)
        assertEquals(listOf(answer, alternative), conversation.messageNodes[1].messages)
    }

    @Test fun editingAnUnselectedBranchDoesNotChangeTheSelectedBranch() {
        val edited = answer.copy(parts = listOf(UIMessagePart.Text("Edited hidden answer")))
        val updated = conversation.updateCurrentMessages(listOf(first, edited))
        assertEquals(listOf(edited, alternative), updated.messageNodes[1].messages)
        assertEquals(1, updated.messageNodes[1].selectIndex)
        assertEquals(alternative, updated.currentMessages[1])
    }

    @Test fun regenerationAppendsAndSelectsANewAlternativeWithoutReplacingHistory() {
        val regenerated = UIMessage.assistant("Regenerated")
        val updated = conversation.updateCurrentMessages(listOf(first, regenerated))
        assertEquals(listOf(answer, alternative, regenerated), updated.messageNodes[1].messages)
        assertEquals(2, updated.messageNodes[1].selectIndex)
        assertEquals(answerNode.id, updated.messageNodes[1].id)
        assertEquals(regenerated, updated.currentMessages[1])
    }

    @Test fun appendingMessagesCreatesOneSelectedBranchPerNewNode() {
        val nextQuestion = UIMessage.user("Next question")
        val nextAnswer = UIMessage.assistant("Next answer")
        val updated = conversation.updateCurrentMessages(listOf(first, alternative, nextQuestion, nextAnswer))
        assertEquals(4, updated.messageNodes.size)
        assertEquals(listOf(nextQuestion), updated.messageNodes[2].messages)
        assertEquals(listOf(nextAnswer), updated.messageNodes[3].messages)
        assertEquals(listOf(0, 0), updated.messageNodes.drop(2).map { it.selectIndex })
        assertFalse(updated.messageNodes[2].isFavorite)
    }

    @Test fun shorterAndEmptyUpdatesKeepTrailingNodesAsExistingBehavior() {
        assertEquals(conversation.messageNodes, conversation.updateCurrentMessages(emptyList()).messageNodes)
        assertEquals(conversation.messageNodes, conversation.updateCurrentMessages(listOf(first)).messageNodes)
    }

    @Test fun nodeLookupIncludesUnselectedAlternativesAndMatchesIdAfterEditing() {
        assertSame(answerNode, conversation.getMessageNodeByMessage(answer))
        assertSame(answerNode, conversation.getMessageNodeByMessage(alternative))
        val edited = answer.copy(parts = listOf(UIMessagePart.Text("Different contents, same id")))
        assertNull(conversation.getMessageNodeByMessage(edited))
        assertSame(answerNode, conversation.getMessageNodeByMessageId(edited.id))
        assertNull(conversation.getMessageNodeByMessageId(Uuid.random()))
    }

    @Test fun messageNodeFactoriesKeepRoleMessageAndInitialSelection() {
        MessageRole.entries.forEach { role ->
            val message = UIMessage(role = role, parts = emptyList())
            listOf(MessageNode.of(message), message.toMessageNode()).forEach { node ->
                assertEquals(role, node.role)
                assertEquals(0, node.selectIndex)
                assertSame(message, node.currentMessage)
            }
        }
        assertEquals(MessageRole.USER, MessageNode(messages = emptyList()).role)
        // A node's role is its first alternative's role, even if malformed mixed roles are stored.
        assertEquals(MessageRole.USER, MessageNode(messages = listOf(first, answer), selectIndex = 1).role)
    }

    @Test fun invalidCurrentSelectionFailsWithUsefulDiagnostics() {
        listOf(
            MessageNode(messages = emptyList()),
            MessageNode(messages = listOf(first), selectIndex = -1),
            MessageNode(messages = listOf(first), selectIndex = 1),
        ).forEach { node ->
            val error = assertThrows(IllegalStateException::class.java) { node.currentMessage }
            assertEquals(
                "MessageNode has no valid current message: messages.size=${node.messages.size}, selectIndex=${node.selectIndex}",
                error.message,
            )
        }
    }

    @Test fun forkPreservesTheChosenBranchAndConversationConfigButStartsFreshMetadata() {
        val source = conversation.copy(
            title = "Characterization(2)", isPinned = true,
            customSystemPrompt = "Keep this system instruction",
            config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
            workspaceCwd = "/workspace/project", folderId = Uuid.random(),
            modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
            chatSuggestions = listOf("Continue"),
        )
        val branch = listOf(userNode, answerNode.copy(selectIndex = 0))
        val fork = createForkConversation(source, branch, setOf("Characterization(3)"))
        assertEquals("Characterization(4)", fork.title)
        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(branch, fork.messageNodes)
        assertEquals(listOf(first, answer), fork.currentMessages)
        assertEquals(source.config, fork.config)
        assertEquals(source.customSystemPrompt, fork.customSystemPrompt)
        assertEquals(source.modeInjectionIds, fork.modeInjectionIds)
        assertEquals(source.lorebookIds, fork.lorebookIds)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertFalse(fork.isPinned)
        assertEquals(emptyList<String>(), fork.chatSuggestions)
    }

    @Test fun deletingTheOnlyAlternativeRemovesItsNodeAndKeepsConversationConfiguration() {
        val source = conversation.copy(config = ConversationConfig(chatModelId = Uuid.random()), workspaceCwd = "/workspace/project")
        val deleted = deleteFromTree(source, first.id)!!
        assertEquals(listOf(answerNode), deleted.messageNodes)
        assertEquals(source.copy(messageNodes = listOf(answerNode)), deleted)
        assertEquals(source.config, deleted.config)
        assertEquals(source.workspaceCwd, deleted.workspaceCwd)
        assertNull(deleteFromTree(source, Uuid.random()))
    }

    @Test fun deletingSelectedLastAlternativeClampsToTheRemainingBranch() {
        val deleted = deleteFromTree(conversation, alternative.id)!!
        assertEquals(listOf(answer), deleted.messageNodes[1].messages)
        assertEquals(0, deleted.messageNodes[1].selectIndex)
        assertEquals(answer, deleted.currentMessages[1])
        assertEquals(answerNode.id, deleted.messageNodes[1].id)
        assertEquals(true, deleted.messageNodes[1].isFavorite)
    }

    @Test fun deletingBeforeSelectedAlternativeCurrentlyKeepsItsNumericIndexAndChangesBranch() {
        val third = UIMessage.assistant("Third answer")
        val source = conversation.copy(messageNodes = listOf(userNode, answerNode.copy(messages = listOf(answer, alternative, third))))
        val deleted = deleteFromTree(source, answer.id)!!
        assertEquals(listOf(alternative, third), deleted.messageNodes[1].messages)
        assertEquals(1, deleted.messageNodes[1].selectIndex)
        // Characterization of the existing selected-index defect, not the desired future policy.
        assertEquals(third, deleted.currentMessages[1])
        assertEquals(alternative, source.currentMessages[1])
    }

    private fun deleteFromTree(source: Conversation, messageId: Uuid): Conversation? {
        // The production helper is private and pure. MockK avoids constructing the service's
        // database/network collaborators; callOriginal executes its real tree transformation.
        val service = mockk<ChatService>()
        every { service["buildConversationAfterMessageDelete"](any<Conversation>(), any<Uuid>()) } answers { callOriginal() }
        val method = ChatService::class.java.declaredMethods.single { it.name.startsWith("buildConversationAfterMessageDelete") }
            .apply { isAccessible = true }
        return method.invoke(service, source, messageId) as Conversation?
    }
}
