package me.rerere.rikkahub.data.db.dao

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class LegacyConversationTitleDaoTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun `compare and set backfills a legacy title once and preserves all other data`() = runBlocking {
        val source = conversation("Новый чат")
        val messages = MessageNodeEntity("node", source.id, 0,
            JsonInstant.encodeToString(listOf(UIMessage.user("Первый запрос"))), 0)
        val dao = database.conversationDao()
        dao.insert(source)
        database.messageNodeDao().insert(messages)

        assertEquals(1, dao.updateTitleIfUnchanged(source.id, source.title, "Первый запрос"))
        assertEquals(0, dao.updateTitleIfUnchanged(source.id, source.title, "Первый запрос"))
        assertEquals(source.copy(title = "Первый запрос"), dao.getConversationById(source.id))
        assertEquals(listOf(messages), database.messageNodeDao().getNodesOfConversation(source.id))
    }

    @Test fun `a rename after the drawer row was loaded prevents legacy backfill`() = runBlocking {
        val dao = database.conversationDao()
        for ((index, oldTitle) in listOf("", "Новый чат").withIndex()) {
            val source = conversation(oldTitle).copy(id = "chat-$index")
            dao.insert(source)
            val observedTitle = dao.getConversationById(source.id)!!.title
            dao.updateTitle(source.id, "Ручное название")

            assertEquals(0, dao.updateTitleIfUnchanged(source.id, observedTitle, "Первый запрос"))
            assertEquals(source.copy(title = "Ручное название"), dao.getConversationById(source.id))
        }
    }

    @Test fun `an AI title written after list loading also prevents legacy backfill`() = runBlocking {
        val source = conversation("")
        val dao = database.conversationDao()
        dao.insert(source)
        dao.updateTitle(source.id, "Заголовок ИИ")
        assertEquals(0, dao.updateTitleIfUnchanged(source.id, source.title, "Первый запрос"))
        assertEquals("Заголовок ИИ", dao.getConversationById(source.id)!!.title)
    }

    @Test fun `a deleted chat is never recreated by title backfill`() = runBlocking {
        assertEquals(0, database.conversationDao().updateTitleIfUnchanged("missing", "", "Первый запрос"))
        assertEquals(0, database.conversationDao().countAll())
    }

    private fun conversation(title: String) = ConversationEntity(
        id = "chat", assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", title = title,
        nodes = "[]", createAt = 1_000, updateAt = 2_000, chatSuggestions = "[\"suggestion\"]",
        isPinned = true, customSystemPrompt = "system prompt", folderId = "folder",
        config = "{\"fixture\":\"retained\"}", workspaceCwd = "/workspace/chat",
    )
}
