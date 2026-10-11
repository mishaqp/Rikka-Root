package me.rerere.rikkahub.service

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LegacyConversationTitleBackfillTest {
    @Test(timeout = 10_000)
    fun `a session created during CAS waits for its stale initialization then receives only the committed title`() = runBlocking {
        val source = conversation("")
        val initializedSnapshot = source.copy(
            config = ConversationConfig(chatModelId = Uuid.random()),
            customSystemPrompt = "Сохранить инструкцию",
            isPinned = true,
            messageNodes = source.messageNodes + UIMessage.assistant("Сохранить ответ").toMessageNode(),
        )
        var createdSession: ConversationSession? = null
        val allowCas = CompletableDeferred<Unit>()
        val enteredReconciliation = CompletableDeferred<Unit>()
        val staleLoad = CompletableDeferred<Conversation>()
        val backfill = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(backfillLoadedLegacyConversationTitle(source, source.title, session = null,
                onCommitted = { title ->
                    enteredReconciliation.complete(Unit)
                    createdSession?.reconcileLegacyBackfilledTitle(source.title, title)
                },
            ) { allowCas.await(); true })
        }
        val session = ConversationSession(source.id, Conversation.ofId(source.id), this, {})
        createdSession = session
        val initialization = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { staleLoad.await() }
        }
        allowCas.complete(Unit)
        enteredReconciliation.await()
        assertFalse(backfill.isCompleted)
        assertFalse(session.isInitialized)
        staleLoad.complete(initializedSnapshot)
        initialization.join()
        backfill.join()
        assertEquals(initializedSnapshot.copy(title = "Первое сообщение"), session.state.value)
    }

    @Test(timeout = 10_000)
    fun `a manual rename in a session created during CAS wins over committed title reconciliation`() = runBlocking {
        val source = conversation("").copy(config = ConversationConfig(chatModelId = Uuid.random()))
        var persistedTitle = source.title
        var createdSession: ConversationSession? = null
        val allowCas = CompletableDeferred<Unit>()
        val allowManualSave = CompletableDeferred<Unit>()
        val enteredReconciliation = CompletableDeferred<Unit>()
        val backfill = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(backfillLoadedLegacyConversationTitle(source, source.title, session = null,
                onCommitted = { title ->
                    enteredReconciliation.complete(Unit)
                    createdSession?.reconcileLegacyBackfilledTitle(source.title, title)
                },
            ) { title ->
                allowCas.await()
                persistedTitle = title
                true
            })
        }
        val session = ConversationSession(source.id, Conversation.ofId(source.id), this, {})
        createdSession = session
        session.initialize { source }
        val rename = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                update = { it.copy(title = "Ручное название") },
                persist = { allowManualSave.await(); persistedTitle = it.title },
            )
        }
        allowCas.complete(Unit)
        enteredReconciliation.await()
        assertFalse(backfill.isCompleted)
        allowManualSave.complete(Unit)
        rename.join()
        backfill.join()
        assertEquals("Ручное название", persistedTitle)
        assertEquals(source.copy(title = "Ручное название"), session.state.value)
    }

    @Test fun `a rejected no session CAS never reconciles an uncommitted title`() = runBlocking {
        val source = conversation("")
        assertFalse(backfillLoadedLegacyConversationTitle(source, source.title, session = null,
            onCommitted = { error("A rejected CAS has no committed title to publish") },
        ) { false })
    }

    @Test fun `an unloaded legacy chat writes its local title without creating or initializing a session`() = runBlocking {
        val source = conversation("Новый чат")
        var writtenTitle: String? = null
        assertTrue(backfillLoadedLegacyConversationTitle(source, source.title, session = null) {
            writtenTitle = it
            true
        })
        assertEquals("Первое сообщение", writtenTitle)
        assertEquals("Новый чат", source.title)
        assertEquals(null, source.config)
    }

    @Test fun `an uninitialized existing session is left for the normal chat initialization path`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, Conversation.ofId(source.id), this, {})
        assertFalse(backfillLoadedLegacyConversationTitle(source, source.title, session) {
            error("Background upkeep must not initialize the normal chat session")
        })
        assertFalse(session.isInitialized)
        assertTrue(session.state.value.messageNodes.isEmpty())
        session.initialize { source }
        assertTrue(backfillLoadedLegacyConversationTitle(source, source.title, session) { true })
        assertEquals(source.copy(title = "Первое сообщение"), session.state.value)
    }

    @Test fun `legacy backfill uses first USER locally and writes only once`() = runBlocking {
        val source = conversation(title = "Новый чат").copy(messageNodes = listOf(
            UIMessage.assistant("Не название").toMessageNode(),
            UIMessage.user("  Первый\n\tзапрос\u00a0пользователя ").toMessageNode(),
            UIMessage.user("Следующий запрос").toMessageNode(),
        ))
        val session = ConversationSession(source.id, source, this, {})
        var writes = 0
        assertTrue(session.backfillLegacyTitle("Новый чат") { title ->
            assertEquals("Первый запрос пользователя", title)
            writes++
            true
        })
        assertFalse(session.backfillLegacyTitle("Новый чат") { writes++; true })
        assertEquals(1, writes)
        assertEquals(source.copy(title = "Первый запрос пользователя"), session.state.value)
    }

    @Test fun `blank legacy title uses the same local date fallback for an attachment only first USER`() = runBlocking {
        val source = conversation(title = "  \n").copy(messageNodes = listOf(
            UIMessage.user("").copy(parts = listOf(UIMessagePart.Image("content://photo/1"))).toMessageNode(),
            UIMessage.user("Позднее сообщение").toMessageNode(),
        ))
        val expected = localConversationTitle(source)
        val session = ConversationSession(source.id, source, this, {})
        assertTrue(session.backfillLegacyTitle(source.title) { it == expected })
        assertEquals(expected, session.state.value.title)
    }

    @Test fun `backfill preserves an existing title and a title changed since the loaded row`() = runBlocking {
        for (title in listOf("Моё название", "Заголовок ИИ", "Новый чат о работе")) {
            val source = conversation(title)
            val session = ConversationSession(source.id, source, this, {})
            assertFalse(session.backfillLegacyTitle(title) { error("A named chat must not be updated") })
            assertFalse(session.backfillLegacyTitle("") { error("A stale row must not be updated") })
            assertEquals(source, session.state.value)
        }
    }

    @Test fun `a rejected database compare and set leaves memory untouched`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        assertFalse(session.backfillLegacyTitle("") { false })
        assertEquals(source, session.state.value)
    }

    @Test fun `a new conversation waiting for its background title is deferred`() = runBlocking {
        val source = conversation("").copy(newConversation = true)
        val session = ConversationSession(source.id, source, this, {})
        assertFalse(session.backfillLegacyTitle("") { error("A new chat keeps its background title path") })
        assertEquals(source, session.state.value)
    }

    @Test fun `viewing an idle legacy chat does not prevent its local backfill`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        session.acquire()
        try {
            assertTrue(session.isInUse)
            assertTrue(session.backfillLegacyTitle("") { true })
            assertEquals("Первое сообщение", session.state.value.title)
        } finally {
            session.cleanup()
        }
    }

    @Test fun `successful title persistence retains messages and metadata updated during compare and set`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        val allowSave = CompletableDeferred<Unit>()
        val backfill = launch(start = CoroutineStart.UNDISPATCHED) {
            session.backfillLegacyTitle("") { allowSave.await(); true }
        }
        val latest = source.copy(isPinned = true,
            messageNodes = source.messageNodes + UIMessage.assistant("Новый ответ").toMessageNode())
        session.updateConversation(latest)
        allowSave.complete(Unit)
        backfill.join()
        assertEquals(latest.copy(title = "Первое сообщение"), session.state.value)
    }

    @Test fun `a manual rename concurrent with backfill wins in memory and persistence`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        var persistedTitle = source.title
        val enteredSave = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        val backfill = launch(start = CoroutineStart.UNDISPATCHED) {
            session.backfillLegacyTitle("") {
                enteredSave.complete(Unit)
                allowSave.await()
                persistedTitle = it
                true
            }
        }
        enteredSave.await()
        val rename = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                update = { it.copy(title = "Ручное название") },
                persist = { persistedTitle = it.title },
            )
        }
        assertFalse(rename.isCompleted)
        allowSave.complete(Unit)
        backfill.join()
        rename.join()
        assertEquals("Ручное название", persistedTitle)
        assertEquals("Ручное название", session.state.value.title)
        assertEquals(source.messageNodes, session.state.value.messageNodes)
    }

    @Test fun `an AI title published while compare and set is suspended is never overwritten in memory`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        val allowSave = CompletableDeferred<Unit>()
        val backfill = launch(start = CoroutineStart.UNDISPATCHED) {
            session.backfillLegacyTitle("") { allowSave.await(); true }
        }
        val renamed = source.copy(title = "Заголовок ИИ", isPinned = true)
        session.updateConversation(renamed)
        allowSave.complete(Unit)
        backfill.join()
        assertEquals(renamed, session.state.value)
    }

    @Test fun `generation starting during compare and set keeps its pending AI title in memory`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        val allowSave = CompletableDeferred<Unit>()
        val backfill = launch(start = CoroutineStart.UNDISPATCHED) {
            session.backfillLegacyTitle("") { allowSave.await(); true }
        }
        val generation = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(generation)
        try {
            allowSave.complete(Unit)
            backfill.join()
            assertEquals(source, session.state.value)
        } finally {
            session.cleanup()
            generation.join()
        }
    }

    @Test fun `new chats still generating are deferred without a title write`() = runBlocking {
        val source = conversation("")
        val session = ConversationSession(source.id, source, this, {})
        val generation = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        session.setJob(generation)
        try {
            assertFalse(session.backfillLegacyTitle("") { error("An active new chat keeps its AI title generation") })
            assertEquals(source, session.state.value)
        } finally {
            session.cleanup()
            generation.join()
        }
    }

    private fun conversation(title: String) = Conversation(
        assistantId = Uuid.random(),
        title = title,
        messageNodes = listOf(UIMessage.user("Первое сообщение").toMessageNode()),
        createAt = Instant.parse("2026-10-11T12:00:00Z"),
    )
}
