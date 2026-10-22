package me.rerere.rikkahub.service

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.pages.history.HistoryVM
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryUndoAttachmentLeaseAuditTest : ChatAuditServiceFixture() {
    private val models = ViewModelStore()
    private fun history() = HistoryVM(repo, store, service).also { models.put("history", it) }
    @After fun clearHistoryModels() { models.clear() }
    private suspend fun restore(vm: HistoryVM, source: me.rerere.rikkahub.data.model.Conversation) {
        val existing = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.restoreConversation(source)
        vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in existing }.toList().joinAll()
    }

    @Test(timeout = 30_000) fun deleteThenUndoKeepsExactAttachmentBytesThroughoutTheUndoWindow() = runBlocking {
        val file = imageFile()
        val source = seed(nodes = listOf(UIMessage.user("").copy(parts = listOf(image(file))).toMessageNode()))
        val vm = history()
        assertTrue(vm.deleteConversation(source))
        assertNull(repo.getConversationById(source.id))
        assertTrue("An offered Undo must still own its attachment bytes", file.exists())
        assertEquals("EXACT ATTACHMENT BYTES", file.readText())
        restore(vm, source)
        assertEquals(source.messageNodes, repo.getConversationById(source.id)!!.messageNodes)
        assertEquals("EXACT ATTACHMENT BYTES", file.readText())
    }

    @Test(timeout = 30_000) fun discardUndoReleasesItsLeaseSoLastAttachmentCanBeCollected() = runBlocking {
        val file = imageFile()
        val source = seed(nodes = listOf(UIMessage.user("").copy(parts = listOf(image(file))).toMessageNode()))
        val vm = history(); assertTrue(vm.deleteConversation(source))
        assertTrue("Lease is active while Undo is offered", file.exists())
        vm.discardUndo(source.id)
        repo.cleanupChatFiles(listOf(file.toUri()))
        assertFalse("A discarded Undo must not reserve the file forever", file.exists())
    }

    @Test(timeout = 30_000) fun clearingHistoryViewModelReleasesPendingUndoAttachmentLeases() = runBlocking {
        val file = imageFile()
        val source = seed(nodes = listOf(UIMessage.user("").copy(parts = listOf(image(file))).toMessageNode()))
        val vm = history(); assertTrue(vm.deleteConversation(source))
        assertTrue(file.exists())
        models.clear()
        repo.cleanupChatFiles(listOf(file.toUri()))
        assertFalse("Leaving the History lifecycle must release pending Undo leases", file.exists())
    }

    @Test(timeout = 30_000) fun abandonedUndoExpiresWithinTwoMinutesAndCannotRestoreMissingBytes() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val file = imageFile()
        val source = seed(nodes = listOf(UIMessage.user("").copy(parts = listOf(image(file))).toMessageNode()))
        val vm = history(); assertTrue(vm.deleteConversation(source))
        assertTrue(file.exists())
        scheduler.advanceTimeBy(120_001); scheduler.runCurrent()
        repo.cleanupChatFiles(listOf(file.toUri()))
        assertFalse("Abandoned Undo reservations must have a bounded lifetime", file.exists())
        restore(vm, source)
        assertNull("An expired receipt cannot reinsert broken media URLs", repo.getConversationById(source.id))
    }

    @Test(timeout = 30_000) fun sameUuidExternalInsertRemainsUntouchedWhenUndoConflicts() = runBlocking {
        val source = seed(); val vm = history()
        assertTrue(vm.deleteConversation(source))
        val replacement = source.copy(title = "EXTERNAL INSERT", messageNodes = listOf(UIMessage.user("EXTERNAL EXACT").toMessageNode()))
        repo.insertConversation(replacement)
        restore(vm, source)
        val actual = repo.getConversationById(source.id)!!
        assertEquals(replacement.title, actual.title)
        assertEquals(replacement.messageNodes, actual.messageNodes)
    }
}
