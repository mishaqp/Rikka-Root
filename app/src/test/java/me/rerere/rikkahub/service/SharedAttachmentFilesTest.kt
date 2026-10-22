package me.rerere.rikkahub.service

import android.app.Application
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlin.uuid.Uuid

/** Public production operations, generated Room DAOs and real attachment bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SharedAttachmentFilesTest {
    private lateinit var rig: Rig
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); rig = Rig() }
    @After fun tearDown() { rig.close(); Dispatchers.resetMain() }

    @Test fun publicForkCopiesEveryNestedMediaAndInactiveCandidate() = runBlocking {
        val originals = listOf("image.png", "document.pdf", "video.mp4", "audio.wav").map(rig::file)
        val media = listOf(UIMessagePart.Image(originals[0].toUri().toString()),
            UIMessagePart.Document(originals[1].toUri().toString(), "kept.pdf", "application/pdf"),
            UIMessagePart.Video(originals[2].toUri().toString()), UIMessagePart.Audio(originals[3].toUri().toString()))
        val inactive = UIMessage.assistant("").copy(parts = listOf(nested(media + UIMessagePart.Image("https://example.com/remote.png"))))
        val source = rig.conversation(listOf(MessageNode(messages = listOf(inactive, UIMessage.assistant("selected")), selectIndex = 1)))
            .copy(customSystemPrompt = "kept prompt", modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
                workspaceCwd = "/kept", folderId = Uuid.random())
        rig.repo.insertConversation(source)
        val fork = rig.service.forkConversationAtMessage(source.id, inactive.id)
        val forkParts = fork.messageNodes.single().messages.first().parts
        val copied = forkParts.localFileUrls()
        assertEquals("All four nested media must remain present", 4, copied.size)
        assertTrue("Nested fork URLs must be independent", copied.intersect(media.localFileUrls()).isEmpty())
        assertEquals(originals.map { it.readText() }.toSet(), copied.map { it.toUri().toFile().readText() }.toSet())
        assertEquals(source.config, fork.config); assertEquals(source.customSystemPrompt, fork.customSystemPrompt)
        assertEquals(source.folderId, fork.folderId); assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.modeInjectionIds, fork.modeInjectionIds); assertEquals(source.lorebookIds, fork.lorebookIds)
        assertEquals(source.messageNodes.single().messages.map { it.id }, fork.messageNodes.single().messages.map { it.id })
        assertEquals(source.messageNodes.single().selectIndex, fork.messageNodes.single().selectIndex)
        assertNotEquals(source.messageNodes.single().id, fork.messageNodes.single().id)
        val outer = forkParts.single() as UIMessagePart.Tool
        assertEquals("outer", outer.toolCallId); assertEquals(ToolApprovalState.Approved, outer.approvalState)
        val inner = outer.output.single() as UIMessagePart.Tool
        assertEquals("inner", inner.toolCallId); assertEquals("{\"keep\":true}", inner.input)
        assertEquals(UIMessagePart.Image("https://example.com/remote.png"), inner.output.last())
        assertEquals("kept.pdf", (inner.output[1] as UIMessagePart.Document).fileName)
        rig.repo.deleteConversation(source)
        assertTrue(copied.all { it.toUri().toFile().exists() })
        assertTrue("Source bytes can be removed without touching independent fork files", originals.none { it.exists() })
        assertEquals(copied, rig.repo.getConversationById(fork.id)!!.messageNodes.single().messages.first().parts.localFileUrls())
    }

    @Test fun selectedVersionOnlyForkCopiesItsFilesAndLeavesOriginalVersionsIntact() = runBlocking {
        val inactiveFile = rig.file("inactive.png")
        val selectedFile = rig.file("selected.png")
        val inactive = rig.image(inactiveFile)
        val selected = rig.image(selectedFile)
        val source = rig.conversation(listOf(MessageNode(messages = listOf(inactive, selected), selectIndex = 1)))
        rig.repo.insertConversation(source)
        rig.settingsFlow.value = rig.settings.copy(displaySetting = rig.settings.displaySetting.copy(forkKeepMessageVersions = false))

        val fork = rig.service.forkConversationAtMessage(source.id, selected.id)
        val forkNode = fork.messageNodes.single()
        assertEquals(listOf(selected.id), forkNode.messages.map { it.id })
        assertEquals(0, forkNode.selectIndex)
        val copiedFile = forkNode.currentMessage.parts.localFileUrls().single().toUri().toFile()
        assertNotEquals(selectedFile, copiedFile)
        assertEquals(selectedFile.readText(), copiedFile.readText())
        assertEquals(source.config, fork.config)
        val original = rig.repo.getConversationById(source.id)!!
        assertEquals(listOf(inactive.id, selected.id), original.messageNodes.single().messages.map { it.id })
        assertTrue(inactiveFile.exists())
        rig.repo.deleteConversation(source)
        assertTrue(copiedFile.exists())
    }

    @Test fun assistantRegenerationTrimsLaterNodesOnlyWhenSettingIsEnabled() = runBlocking {
        val question = MessageNode.of(UIMessage.user("Вопрос"))
        val answer = MessageNode(messages = listOf(UIMessage.assistant("Вариант 1"), UIMessage.assistant("Вариант 2")), selectIndex = 1)
        val later = MessageNode.of(UIMessage.user("Следующий вопрос"))
        for (trim in listOf(false, true)) {
            val source = rig.conversation(listOf(question, answer, later))
            rig.repo.insertConversation(source)
            // No configured model: exercise the real mutation without any provider/network call.
            rig.settingsFlow.value = rig.settings.copy(providers = emptyList(),
                displaySetting = rig.settings.displaySetting.copy(regenerateDeleteTrailingMessages = trim))
            rig.service.initializeConversation(source.id)
            rig.service.regenerateAtMessage(source.id, answer.currentMessage)
            rig.service.getGenerationJobStateFlow(source.id).first()?.join()
            val saved = rig.repo.getConversationById(source.id)!!
            assertEquals(if (trim) listOf(question, answer) else listOf(question, answer, later), saved.messageNodes)
            assertEquals(source.config, saved.config)
        }
    }

    @Test fun ordinaryDeletePreservesPersistedNestedInactiveReferenceThenCleansLastReference() = runBlocking {
        val file = rig.file("shared.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val other = rig.conversation(listOf(MessageNode(messages = listOf(
            UIMessage.assistant("").copy(parts = listOf(nested(listOf(UIMessagePart.Image(file.toUri().toString()))))),
            UIMessage.assistant("selected")), selectIndex = 1)))
        rig.repo.insertConversation(source); rig.repo.insertConversation(other)
        rig.repo.deleteConversation(source)
        assertTrue("Deleting one chat must keep a shared nested inactive attachment", file.exists())
        assertTrue(rig.repo.hasFileReference(file.toUri().toString()))
        rig.repo.deleteConversation(other)
        assertFalse("The last persisted reference must release its file", file.exists())
    }

    @Test fun ordinaryDeleteUsesRawReferencesEvenWhenOtherRowCannotBeDecoded() = runBlocking {
        val file = rig.file("unloadable.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val other = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source); rig.repo.insertConversation(other)
        val row = rig.database.messageNodeDao().getNodesOfConversation(other.id.toString()).single()
        rig.database.messageNodeDao().update(row.copy(messages = "unloadable " + row.messages))
        rig.repo.deleteConversation(source)
        assertTrue("Raw persisted JSON reference must survive an unloadable other conversation", file.exists())
        assertEquals(1, rig.database.messageNodeDao().getNodesOfConversation(other.id.toString()).size)
    }

    @Test fun ordinaryDeleteRetainsBytesWhenReferenceQueryFails() = runBlocking {
        val file = rig.file("query-failure.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source); rig.queryFailure = true
        rig.repo.deleteConversation(source)
        assertFalse(rig.repo.existsConversationById(source.id))
        assertTrue("Unknown global references must retain the original bytes", file.exists())
    }

    @Test fun publicCandidateDeletePreservesAnotherChatsAttachment() = runBlocking {
        val file = rig.file("candidate-shared.png")
        val candidate = rig.image(file)
        val source = rig.conversation(listOf(MessageNode(messages = listOf(UIMessage.user("selected"), candidate))))
        val other = rig.conversation(listOf(UIMessage.assistant("").copy(parts = listOf(nested(candidate.parts))).toMessageNode()))
        rig.repo.insertConversation(source); rig.repo.insertConversation(other)
        rig.service.deleteMessage(source.id, candidate.id)
        assertTrue("Candidate removal must retain another chat's reference", file.exists())
        assertFalse(rig.repo.getConversationById(source.id)!!.messageNodes.single().messages.any { it.id == candidate.id })
    }

    @Test fun candidateDeletionRetainsFilesUntilPersistenceSucceeds() = runBlocking {
        val file = rig.file("failed-save.png")
        val candidate = rig.image(file)
        val source = rig.conversation(listOf(MessageNode(messages = listOf(UIMessage.user("keep"), candidate))))
        rig.repo.insertConversation(source)
        val rows = rig.database.messageNodeDao().getNodesOfConversation(source.id.toString())
        rig.writeFailure = true
        assertTrue(runCatching { rig.service.deleteMessage(source.id, candidate.id) }.isFailure)
        assertEquals(rows, rig.database.messageNodeDao().getNodesOfConversation(source.id.toString()))
        assertTrue("Failed save must not physically delete attachments", file.exists())
        rig.writeFailure = false
        rig.service.saveConversation(source.id, rig.service.getConversationFlow(source.id).value)
        assertFalse("A later successful save may release the last attachment", file.exists())
    }

    @Test fun memoryOnlyRemovalDoesNotDeleteBeforeARealSave() = runBlocking {
        val file = rig.file("memory-only.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source); rig.service.initializeConversation(source.id)
        rig.service.updateConversationState(source.id) { it.copy(messageNodes = emptyList()) }
        assertTrue("A transient history publication is not successful persistence", file.exists())
        rig.service.saveConversation(source.id, rig.service.getConversationFlow(source.id).value)
        assertFalse(file.exists())
    }

    @Test fun ordinaryDeleteProtectsActiveUnsavedNestedHistory() = runBlocking {
        val file = rig.file("active.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val active = rig.conversation(listOf(UIMessage.user("empty persisted history").toMessageNode()))
        rig.repo.insertConversation(source); rig.repo.insertConversation(active); rig.service.initializeConversation(active.id)
        rig.service.updateConversationState(active.id) { it.copy(messageNodes = listOf(
            UIMessage.assistant("").copy(parts = listOf(nested(listOf(UIMessagePart.Image(file.toUri().toString()))))).toMessageNode())) }
        rig.repo.deleteConversation(source)
        assertTrue("Active unsaved history must retain bytes", file.exists())
        rig.service.updateConversationState(active.id) { it.copy(messageNodes = emptyList()) }
        rig.service.saveConversation(active.id, rig.service.getConversationFlow(active.id).value)
        assertFalse(file.exists())
    }

    @Test fun ordinaryDeleteProtectsQueuedAttachmentsAndQueueRevokeCleansLastReference() = runBlocking {
        val file = rig.file("queued.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val active = rig.conversation(listOf(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("pending", "wait", "{}", approvalState = ToolApprovalState.Pending))).toMessageNode()))
        rig.repo.insertConversation(source); rig.repo.insertConversation(active); rig.service.initializeConversation(active.id)
        rig.service.sendMessage(active.id, listOf(UIMessagePart.Image(file.toUri().toString())), answer = false)
        val queued = rig.service.getMessageQueueFlow(active.id).value.messages.single()
        rig.repo.deleteConversation(source)
        assertTrue("An active queue must retain bytes", file.exists())
        val beforeJobs = rig.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet()
        rig.service.removeQueuedMessage(active.id, queued.id)
        // The cleanup is an app-scope job; observe its Room query boundary without timing sleeps.
        (rig.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet() - beforeJobs).forEach { it.join() }
        assertFalse(file.exists())
    }

    @Test fun ordinaryDeleteProtectsDequeuedSubmittingAttachment() = runBlocking {
        val file = rig.file("submitting.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val active = rig.conversation(listOf(UIMessage.user("ready").toMessageNode()))
        rig.repo.insertConversation(source); rig.repo.insertConversation(active); rig.service.initializeConversation(active.id)
        Dispatchers.setMain(StandardTestDispatcher())
        rig.service.sendMessage(active.id, listOf(UIMessagePart.Image(file.toUri().toString())), answer = false)
        assertTrue(rig.service.getMessageQueueFlow(active.id).value.messages.isEmpty())
        assertFalse(rig.service.getConversationFlow(active.id).value.files.contains(file.toUri()))
        rig.repo.deleteConversation(source)
        assertTrue("The dequeue-to-history submitting window must retain bytes", file.exists())
    }

    @Test(timeout = 15000) fun runtimeReferencePublishedDuringSuspendingQueryIsRechecked() = runBlocking {
        val file = rig.file("late-runtime.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val active = rig.conversation(listOf(UIMessage.user("ready").toMessageNode()))
        rig.repo.insertConversation(source); rig.repo.insertConversation(active); rig.service.initializeConversation(active.id)
        rig.queryRelease = CompletableDeferred()
        val deleting = async(Dispatchers.IO) { rig.repo.deleteConversation(source) }
        try {
            assertNotNull("Deletion must use the global reference query", withTimeoutOrNull(2000) { rig.queryObserved.await() })
            rig.service.updateConversationState(active.id) { it.copy(messageNodes = listOf(rig.image(file).toMessageNode())) }
        } finally { rig.queryRelease!!.complete(Unit) }
        deleting.await()
        assertTrue("Final runtime references must be reread after a suspended Room query", file.exists())
    }

    @Test fun deletingAnOpenConversationReleasesItsHistoryButKeepsItsQueue() = runBlocking {
        val historyFile = rig.file("open-history.png")
        val queuedFile = rig.file("open-queue.png")
        val source = rig.conversation(listOf(rig.image(historyFile).toMessageNode(), rig.image(queuedFile).toMessageNode(),
            UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("pending", "wait", "{}",
                approvalState = ToolApprovalState.Pending))).toMessageNode()))
        rig.repo.insertConversation(source); rig.service.initializeConversation(source.id)
        rig.service.sendMessage(source.id, listOf(UIMessagePart.Image(queuedFile.toUri().toString())), answer = false)
        rig.repo.deleteConversation(source)
        assertFalse("Deleted chat's lingering history must not pin its last file", historyFile.exists())
        assertTrue("The same chat's unsent queue remains a live reference", queuedFile.exists())
    }

    @Test(timeout = 15000) fun pendingRoomWriterRetainsAFileDuringFinalReferenceCheck() = runBlocking {
        val file = rig.file("pending-writer.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        val destination = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source)
        rig.queryRelease = CompletableDeferred()
        val deleting = async(Dispatchers.IO) { rig.repo.deleteConversation(source) }
        try {
            assertNotNull("Deletion must use the global reference query", withTimeoutOrNull(2000) { rig.queryObserved.await() })
            val inserting = async(start = CoroutineStart.UNDISPATCHED) { rig.repo.insertConversation(destination) }
            rig.queryRelease!!.complete(Unit)
            deleting.await(); inserting.await()
            assertTrue("A writer waiting for Room must reserve its attachment bytes", file.exists())
            assertEquals(destination.messageNodes, rig.repo.getConversationById(destination.id)!!.messageNodes)
        } finally { rig.queryRelease!!.complete(Unit) }
    }

    @Test(timeout = 15000) fun forkReservesSourceBytesWhileCopyAndPersistenceAreInProgress() = runBlocking {
        val file = rig.file("fork-race.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source)
        val copying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        every { rig.files.createChatFilesByContents(any()) } answers {
            copying.complete(Unit)
            runBlocking { release.await() }
            callOriginal()
        }
        val forking = async(Dispatchers.IO) { rig.service.forkConversationAtMessage(source.id, source.currentMessages.single().id) }
        try {
            copying.await()
            rig.repo.deleteConversation(source)
            assertTrue("Fork must reserve source bytes before copying", file.exists())
        } finally { release.complete(Unit) }
        val fork = forking.await()
        assertNotEquals(source.files, fork.files)
        assertEquals("bytes for fork-race.png", fork.files.single().toFile().readText())
    }

    @Test fun failedForkCopyRetainsTheSharedOriginalUntilTheForkIsDeleted() = runBlocking {
        val file = rig.file("copy-failure.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source)
        every { rig.files.createChatFilesByContents(any()) } returns emptyList()
        val fork = rig.service.forkConversationAtMessage(source.id, source.currentMessages.single().id)
        assertEquals(source.files, fork.files)
        rig.repo.deleteConversation(source)
        assertTrue("A failed copy leaves a genuine shared source reference", file.exists())
        rig.repo.deleteConversation(fork)
        assertFalse(file.exists())
    }

    @Test fun lateSuggestionResetDoesNotRestoreDeletedHistoryReferences() = runBlocking {
        val file = rig.file("late-suggestion.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
            .copy(chatSuggestions = listOf("old suggestion"))
        rig.repo.insertConversation(source)
        every { rig.files.createChatFilesByContents(any()) } returns emptyList()
        val fork = rig.service.forkConversationAtMessage(source.id, source.currentMessages.single().id)
        assertEquals(source.files, fork.files)
        rig.repo.deleteConversation(source)
        assertTrue(file.exists())

        rig.settingsFlow.value = rig.settings.copy(enableSuggestion = true)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        rig.providers.registerProvider("openai", object : me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting.OpenAI> {
            override suspend fun listModels(providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI) = providerSetting.models
            override suspend fun generateText(providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI,
                messages: List<UIMessage>, params: me.rerere.ai.provider.TextGenerationParams): me.rerere.ai.provider.TextGenerationResult {
                calls.incrementAndGet()
                return me.rerere.ai.provider.TextGenerationResult(id = "suggestion", model = params.model.modelId,
                    message = UIMessage.assistant("new suggestion"))
            }
            override suspend fun streamText(providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAI,
                messages: List<UIMessage>, params: me.rerere.ai.provider.TextGenerationParams): kotlinx.coroutines.flow.Flow<me.rerere.ai.ui.StreamChunk> =
                error("Suggestion must use the text provider boundary")
        })
        rig.service.generateSuggestion(source.id, source)
        assertEquals(1, calls.get())
        assertTrue("The late public suggestion operation must actually clear its old suggestions",
            rig.service.getConversationFlow(source.id).value.chatSuggestions.isEmpty())
        assertFalse(rig.repo.existsConversationById(source.id))
        rig.repo.deleteConversation(fork)
        assertFalse("Metadata-only publication must not pin the last shared file through deleted history", file.exists())
    }

    @Test fun realUnsavedHistoryChangesRestoreDeletedSessionsAttachmentReferences() = runBlocking {
        val file = rig.file("new-unsaved-history.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source)
        every { rig.files.createChatFilesByContents(any()) } returns emptyList()
        val fork = rig.service.forkConversationAtMessage(source.id, source.currentMessages.single().id)
        rig.repo.deleteConversation(source)
        rig.service.updateConversationState(source.id) {
            it.copy(messageNodes = it.messageNodes + UIMessage.user("new unsaved turn").toMessageNode())
        }
        rig.repo.deleteConversation(fork)
        assertTrue("A genuine new unsaved history still owns its attachment references", file.exists())
        rig.service.updateConversationState(source.id) { it.copy(messageNodes = emptyList()) }
        rig.service.saveConversation(source.id, rig.service.getConversationFlow(source.id).value)
        assertFalse("Clearing unsaved references must not recreate the deleted chat", rig.repo.existsConversationById(source.id))
        assertFalse(file.exists())
    }

    @Test(timeout = 15000) fun forkProtectsSourceBytesBeforeLoadedHistoryIsPublished() = runBlocking {
        val file = rig.file("fork-load-race.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source)
        rig.pageReadEntered = CompletableDeferred()
        rig.settingsFlow.value = rig.settings.copy(init = true)
        val forking = async(Dispatchers.IO) { rig.service.forkConversationAtMessage(source.id, source.currentMessages.single().id) }
        try {
            assertNotNull("Fork must reach the source page boundary", withTimeoutOrNull(2000) { rig.pageReadEntered!!.await() })
            assertNotNull("Deletion must finish while loaded settings delay publication", withTimeoutOrNull(2000) { rig.repo.deleteConversation(source); Unit })
            assertTrue("Fork initialization must reserve bytes before source URLs are known", file.exists())
        } finally { rig.settingsFlow.value = rig.settings }
        val fork = withTimeoutOrNull(2000) { forking.await() }
        assertNotNull("Fork must finish after publishing loaded settings", fork)
        assertNotEquals(source.files, fork!!.files)
        assertEquals("bytes for fork-load-race.png", fork.files.single().toFile().readText())
    }

    @Test fun incompleteForkAndDeleteFailBeforeAnyFileOrRowEffects() = runBlocking {
        val file = rig.file("incomplete.png")
        val source = rig.conversation(listOf(rig.image(file).toMessageNode()))
        rig.repo.insertConversation(source); rig.pageFailure = true
        val incomplete = rig.repo.getConversationById(source.id)!!
        assertEquals(HistoryLoadState.INCOMPLETE, incomplete.historyLoadState)
        val rows = rig.database.messageNodeDao().getNodesOfConversation(source.id.toString())
        val beforeFiles = file.parentFile!!.listFiles()!!.map { it.name }.toSet()
        assertTrue(runCatching { rig.service.forkConversationAtMessage(source.id, source.currentMessages.single().id) }.isFailure)
        assertTrue(runCatching { rig.repo.deleteConversation(incomplete) }.isFailure)
        assertEquals(1, rig.repo.countConversations()); assertEquals(rows, rig.database.messageNodeDao().getNodesOfConversation(source.id.toString()))
        assertEquals(beforeFiles, file.parentFile!!.listFiles()!!.map { it.name }.toSet()); assertTrue(file.exists())
    }

    private fun nested(parts: List<UIMessagePart>) = UIMessagePart.Tool("outer", "outer-tool", "{}",
        output = listOf(UIMessagePart.Tool("inner", "inner-tool", "{\"keep\":true}", output = parts,
            approvalState = ToolApprovalState.Approved)), approvalState = ToolApprovalState.Approved)

    private class Rig : AutoCloseable {
        val context: Application = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = AppScope()
        val settings = me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures.settings
        var queryFailure = false
        var writeFailure = false
        var pageFailure = false
        var pageReadEntered: CompletableDeferred<Unit>? = null
        var queryRelease: CompletableDeferred<Unit>? = null
        val queryObserved = CompletableDeferred<Unit>()
        val dao = object : MessageNodeDAO by database.messageNodeDao() {
            override suspend fun hasFileReference(encodedFileUrl: String): Boolean {
                queryObserved.complete(Unit)
                if (queryFailure) error("injected reference query failure")
                val result = database.messageNodeDao().hasFileReference(encodedFileUrl)
                queryRelease?.await()
                return result
            }
            override suspend fun insertAll(nodes: List<MessageNodeEntity>) {
                if (writeFailure) error("injected row persistence failure")
                database.messageNodeDao().insertAll(nodes)
            }
            override suspend fun getNodesOfConversationPaged(conversationId: String, limit: Int, offset: Int): List<MessageNodeEntity> {
                if (pageFailure) throw android.database.sqlite.SQLiteBlobTooBigException("injected unreadable source page")
                val result = database.messageNodeDao().getNodesOfConversationPaged(conversationId, limit, offset)
                pageReadEntered?.complete(Unit)
                return result
            }
        }
        val files = spyk(FilesManager(context, FilesRepository(database.managedFileDao()), scope))
        val fts = me.rerere.rikkahub.data.db.fts.MessageFtsManager(database)
        val repo = ConversationRepository(database.conversationDao(), dao, database.favoriteDao(), database, files, fts)
        val settingsFlow = MutableStateFlow(settings)
        val store = mockk<SettingsStore>(relaxed = true).also {
            every { it.settingsFlow } returns settingsFlow
            every { it.settingsFlowRaw } returns MutableStateFlow(settings)
        }
        val providers = me.rerere.ai.provider.ProviderManager(okhttp3.OkHttpClient(), context)
        private val approvalFile = File(context.cacheDir, "${Uuid.random()}.preferences_pb")
        val approvals = me.rerere.rikkahub.data.preferences.ToolApprovalPreferences(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope, produceFile = { approvalFile }))
        val service = ChatService(context, scope, me.rerere.rikkahub.data.event.AppEventBus(), store, repo,
            MemoryRepository(database.memoryDao()), me.rerere.rikkahub.data.ai.GenerationLoop(context, providers, JsonInstant),
            me.rerere.rikkahub.data.ai.TranslationHandler(providers),
            me.rerere.rikkahub.data.ai.transformers.TemplateTransformer(io.pebbletemplates.pebble.PebbleEngine.Builder()
                .loader(me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader(store)).build(), store),
            providers, mockk(relaxed = true), mockk(relaxed = true), files, mockk(relaxed = true),
            FolderRepository(database.folderDao(), database.conversationDao()), approvals,
            me.rerere.rikkahub.costguards.TokenBudgetStore(File(context.cacheDir, "files-budget-${Uuid.random()}")),
            me.rerere.rikkahub.subagent.SubAgentRegistry())
        init { database.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)") }
        fun file(name: String) = File(context.filesDir, "upload/${Uuid.random()}-$name").apply { parentFile!!.mkdirs(); writeText("bytes for $name") }
        fun image(file: File) = UIMessage.user("").copy(parts = listOf(UIMessagePart.Image(file.toUri().toString())))
        fun conversation(nodes: List<MessageNode>) = Conversation(assistantId = settings.assistantId,
            title = "Исходный чат", messageNodes = nodes, config = ConversationConfig(chatModelId = settings.chatModelId))
        override fun close() { service.cleanup(); scope.cancel(); database.close(); approvalFile.delete() }
    }
}
