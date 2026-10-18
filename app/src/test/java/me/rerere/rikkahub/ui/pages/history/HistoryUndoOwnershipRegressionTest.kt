package me.rerere.rikkahub.ui.pages.history

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.AssistantOwnershipGuard
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HistoryUndoOwnershipRegressionTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository
    private lateinit var vm: HistoryVM
    private lateinit var live: MutableStateFlow<Settings>
    private val current = Assistant(name = "Текущий получатель")
    private val missing = Assistant(name = "Отсутствующий владелец")

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        live = MutableStateFlow(Settings(assistants = listOf(current), assistantId = current.id))
        val settings = mockk<SettingsStore> {
            every { settingsFlow } returns live
            every { settingsFlowRaw } returns live
        }
        val guard = AssistantOwnershipGuard { id -> live.value.assistants.any { it.id.toString() == id } }
        repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            mockk(relaxed = true), mockk(relaxed = true), ownershipGuard = guard)
        vm = HistoryVM(repo, settings, mockk<ChatService>(relaxed = true))
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); db.close(); Dispatchers.resetMain() }

    @Test fun `undo of a just deleted legacy orphan restores exact snapshot under selected live owner`() = runBlocking {
        val original = seed(missing)
        assertTrue(vm.deleteConversation(original))
        assertNull(repo.getConversationById(original.id))
        restore(original.copy(title = "Forged caller payload"))
        val restored = repo.getConversationById(original.id)
        assertNotNull("Undo must restore the deleted legacy snapshot", restored)
        assertEquals(original.copy(assistantId = current.id), restored)
        assertFalse(live.value.assistants.any { it.id == missing.id })
    }

    @Test fun `undo of a normal conversation keeps its original live owner`() = runBlocking {
        val owner = Assistant(name = "Другой живой владелец")
        live.value = live.value.copy(assistants = listOf(current, owner))
        val original = seed(owner)
        assertTrue(vm.deleteConversation(original))
        restore(original)
        assertEquals(original, repo.getConversationById(original.id))
    }

    @Test fun `undo rejects arbitrary snapshots without a successful delete receipt`() = runBlocking {
        val arbitrary = Conversation(assistantId = current.id, messageNodes = listOf(node()), title = "Не удаляли")
        restore(arbitrary)
        assertNull(repo.getConversationById(arbitrary.id))
        assertTrue(db.messageNodeDao().getNodesOfConversation(arbitrary.id.toString()).isEmpty())
    }

    @Test fun `failed undo keeps the snapshot and chosen target for an explicit retry`() = runBlocking {
        val original = seed(missing)
        assertTrue(vm.deleteConversation(original))
        val replacement = Assistant(name = "Не выбирать молча")
        live.value = Settings(assistants = listOf(replacement), assistantId = replacement.id)
        restore(original)
        assertNull(repo.getConversationById(original.id))
        live.value = Settings(assistants = listOf(current, replacement), assistantId = replacement.id)
        restore(original)
        assertEquals(original.copy(assistantId = current.id), repo.getConversationById(original.id))
    }

    @Test fun `a successful undo is one shot and cannot overwrite later conversation edits`() = runBlocking {
        val original = seed(current)
        assertTrue(vm.deleteConversation(original))
        restore(original)
        val edited = repo.getConversationById(original.id)!!.copy(title = "После восстановления")
        repo.updateConversation(edited)
        restore(original)
        assertEquals(edited, repo.getConversationById(original.id))
    }

    @Test fun `ordinary insert still rejects the old missing owner`() = runBlocking {
        val proposed = Conversation(assistantId = missing.id, title = "Новая привязка", messageNodes = listOf(node()))
        assertTrue(runCatching { repo.insertConversation(proposed) }.exceptionOrNull() is IllegalStateException)
        assertNull(repo.getConversationById(proposed.id))
    }

    private suspend fun restore(snapshot: Conversation) {
        val previous = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        vm.restoreConversation(snapshot)
        vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in previous }.toList().joinAll()
    }
    private suspend fun seed(owner: Assistant): Conversation {
        val value = Conversation(assistantId = owner.id, title = "Сохранить", messageNodes = listOf(node()))
        db.conversationDao().insert(repo.conversationToConversationEntity(value))
        db.messageNodeDao().insertAll(value.messageNodes.mapIndexed { index, node -> MessageNodeEntity(
            node.id.toString(), value.id.toString(), index, JsonInstant.encodeToString(node.messages), node.selectIndex) })
        return repo.getConversationById(value.id)!!
    }
    private fun node() = MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("История"))))
}
