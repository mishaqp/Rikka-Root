package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.MessageQueueState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatVmInitializationFailureTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ChatVM>()

    @Before fun setupMain() { Dispatchers.setMain(dispatcher) }
    @After fun cleanupMain() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private class Rig {
        val assistant = Assistant()
        val history = Conversation(assistantId = assistant.id, messageNodes = listOf(
            UIMessage.user("Сохранённый вопрос").toMessageNode(), UIMessage.assistant("Сохранённый ответ").toMessageNode()))
        val conversation = MutableStateFlow(history.copy(messageNodes = emptyList(), historyLoadState = HistoryLoadState.NOT_LOADED))
        val errors = MutableStateFlow<List<ChatError>>(emptyList())
        val service = mockk<ChatService>(relaxed = true)
        val store = mockk<SettingsStore>(relaxed = true)
        init {
            every { store.settingsFlow } returns MutableStateFlow(Settings(init = true, assistants = listOf(assistant), assistantId = assistant.id))
            every { service.getConversationFlow(history.id) } returns conversation
            every { service.getGenerationJobStateFlow(history.id) } returns flowOf(null)
            every { service.getProcessingStatusFlow(history.id) } returns MutableStateFlow(null)
            every { service.getConversationJobs() } returns flowOf(emptyMap())
            every { service.getMessageQueueFlow(history.id) } returns MutableStateFlow(MessageQueueState(paused = true))
            every { service.generationDoneFlow } returns MutableSharedFlow()
            every { service.errors } returns errors
            every { service.addError(any(), any(), any(), any()) } answers {
                errors.value = errors.value + ChatError(error = firstArg(), conversationId = secondArg())
            }
        }
    }

    private fun createVm(rig: Rig) = ChatVM(rig.history.id.toString(), RuntimeEnvironment.getApplication(),
        rig.store, mockk(relaxed = true), rig.service, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        .also { viewModels += it }

    @Test fun queueRecoveryFailureKeepsLoadedHistoryReadableAndReportsRussianErrorWithoutStartingGeneration() = runTest(dispatcher) {
        val rig = Rig()
        val failure = IOException("Не удалось восстановить очередь сообщений. Данные сохранены; повторите запуск приложения.")
        coEvery { rig.service.initializeConversation(rig.history.id) } coAnswers {
            // ChatService loads Room history before restoring its encrypted pending queue.
            rig.conversation.value = rig.history
            throw failure
        }
        val vm = createVm(rig)
        advanceUntilIdle()
        assertEquals(rig.history, vm.conversation.value)
        assertEquals(HistoryLoadState.COMPLETE, vm.conversation.value.historyLoadState)
        assertEquals(listOf("Сохранённый вопрос", "Сохранённый ответ"), vm.conversation.value.currentMessages.map { it.toText() })
        assertSame(failure, vm.errors.value.single().error)
        assertEquals(rig.history.id, vm.errors.value.single().conversationId)
        assertTrue(vm.errors.value.single().error.message.orEmpty().contains("Данные сохранены"))
        assertTrue(vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertNull(vm.conversationJob.value)
        assertTrue(vm.messageQueue.value.paused)
        verify(exactly = 0) { rig.service.sendMessage(any(), any(), any()) }
        verify(exactly = 0) { rig.service.resumeMessageQueue(any()) }
    }

    @Test fun startupCancellationRemainsCancellationAndDoesNotBecomeAVisibleFailure() = runTest(dispatcher) {
        val rig = Rig()
        coEvery { rig.service.initializeConversation(rig.history.id) } throws CancellationException("closed")
        val vm = createVm(rig)
        advanceUntilIdle()
        assertTrue(vm.errors.value.isEmpty())
        assertNull(vm.conversationJob.value)
        verify(exactly = 0) { rig.service.addError(any(), any(), any(), any()) }
    }

    @Test fun initializationFailureAlreadyReportedByTheServiceIsNotReportedTwice() = runTest(dispatcher) {
        val rig = Rig()
        val failure = IOException("Не удалось восстановить очередь сообщений. Данные сохранены.")
        coEvery { rig.service.initializeConversation(rig.history.id) } coAnswers {
            rig.conversation.value = rig.history
            rig.service.addError(failure, rig.history.id)
            throw failure
        }
        val vm = createVm(rig)
        advanceUntilIdle()
        assertEquals(1, vm.errors.value.size)
        assertSame(failure, vm.errors.value.single().error)
        assertEquals(rig.history, vm.conversation.value)
    }
}
