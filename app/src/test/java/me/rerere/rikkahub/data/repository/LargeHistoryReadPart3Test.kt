package me.rerere.rikkahub.data.repository

import android.app.Application
import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.uuid.Uuid

/** Real SQLite/Room, with a precise per-cell platform adapter, not a claimed device threshold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LargeHistoryReadPart3Test {
    @Test fun largeCurrentToolResultReopensLosslesslyAndRemainsWritable() = runBlocking {
        Fixture().use { f ->
            val payload = "данные🙂\\\"\n".repeat(20_000)
            val message = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("large-call", "http_request", "{}", output = listOf(UIMessagePart.Text(payload)))))
            val node = MessageNode(Uuid.random(), listOf(message), 0)
            f.seed("[]", listOf(node))
            f.assertAdapterRejectsFullCell("SELECT messages FROM message_node")
            val outcome = runCatching { f.repository.getConversationById(f.id) }
            assertTrue("Большой результат должен читаться полностью: ${outcome.exceptionOrNull()?.javaClass?.simpleName}", outcome.isSuccess)
            val loaded = outcome.getOrThrow()!!
            assertEquals(HistoryLoadState.COMPLETE, loaded.historyLoadState)
            assertEquals(listOf(node), loaded.messageNodes)
            assertEquals(payload, ((loaded.messageNodes.single().messages.single().parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text)
            f.repository.updateConversation(loaded.copy(title = "сохранено после чтения"))
            assertEquals(loaded.copy(title = "сохранено после чтения"), f.repository.getConversationById(f.id))
        }
    }

    @Test fun alreadyUpgradedLargeLegacyColumnRecoversEveryBranchAndSelection() = runBlocking {
        Fixture().use { f ->
            val node = MessageNode(Uuid.random(), listOf(UIMessage.user("первая ветка"), UIMessage.assistant("история🙂".repeat(20_000))), 1)
            val raw = JsonInstant.encodeToString(listOf(node))
            f.seed(raw)
            f.assertAdapterRejectsFullCell("SELECT nodes FROM ConversationEntity")
            val result = runCatching { f.repository.getConversationById(f.id) }
            assertTrue("Остаток legacy.nodes в текущей v29 должен читаться: ${result.exceptionOrNull()?.javaClass?.simpleName}", result.isSuccess)
            val loaded = result.getOrThrow()!!
            assertEquals(HistoryLoadState.COMPLETE, loaded.historyLoadState)
            assertEquals(listOf(node), loaded.messageNodes)
            assertEquals(node.messages[1], loaded.currentMessages.single())
            f.repository.updateConversation(loaded.copy(title = "после восстановления"))
            val reopened = f.repository.getConversationById(f.id)!!
            assertEquals(listOf(node), reopened.messageNodes)
            assertEquals(node.messages[1], reopened.currentMessages.single())
        }
    }

    @Test fun smallLegacyRemainderCannotBeSilentlyReplacedByAnEmptySuccessfulSave() = runBlocking {
        Fixture().use { f ->
            val node = MessageNode(Uuid.random(), listOf(UIMessage.user("последняя копия старой истории")), 0)
            f.seed(JsonInstant.encodeToString(listOf(node)))
            val loaded = f.repository.getConversationById(f.id)!!
            assertEquals("Непустой legacy nodes нельзя объявить пустой COMPLETE историей", listOf(node), loaded.messageNodes)
            f.repository.updateConversation(loaded.copy(title = "обычная правка"))
            assertEquals(listOf(node), f.repository.getConversationById(f.id)!!.messageNodes)
        }
    }

    @Test fun conflictingLegacyAndCurrentHistoriesCannotOverwriteEitherCopy() = runBlocking {
        Fixture().use { f ->
            val legacy = JsonInstant.encodeToString(listOf(MessageNode(Uuid.random(), listOf(UIMessage.user("legacy copy")), 0)))
            val current = MessageNode(Uuid.random(), listOf(UIMessage.user("different current copy")), 0)
            f.seed(legacy, listOf(current))
            val loaded = runCatching { f.repository.getConversationById(f.id) }
            if (loaded.isSuccess) {
                val write = runCatching { f.repository.updateConversation(loaded.getOrThrow()!!.copy(title = "не записывать")) }
                assertTrue("Две разные копии требуют явного восстановления, а не стирания одной", write.isFailure)
            }
            assertEquals(legacy, f.database.conversationDao().getConversationById(f.id.toString())!!.nodes)
            assertEquals(JsonInstant.encodeToString(current.messages), f.database.messageNodeDao().getNodesOfConversation(f.id.toString()).single().messages)
        }
    }

    @Test fun damagedLegacyRemainderIsPreservedOnReadAndAttemptedSave() = runBlocking {
        Fixture().use { f ->
            val raw = "[{\"messages\":[\"исходная recoverable запись\""
            f.seed(raw)
            val result = runCatching { f.repository.getConversationById(f.id) }
            if (result.isSuccess) assertTrue(runCatching { f.repository.updateConversation(result.getOrThrow()!!.copy(title = "не сохранять")) }.isFailure)
            assertEquals(raw, f.database.conversationDao().getConversationById(f.id.toString())!!.nodes)
        }
    }

    @Test fun queryCancellationRemainsCancellationAndLeavesStoredPayloadUnchanged() = runBlocking {
        Fixture().use { f ->
            val node = MessageNode(Uuid.random(), listOf(UIMessage.user("не менять")), 0)
            f.seed("[]", listOf(node))
            f.adapter.cancelMessageRead = true
            val result = runCatching { f.repository.getConversationById(f.id) }
            assertTrue("Отмена не должна превращаться в успешную пустую историю", result.exceptionOrNull() is CancellationException)
            f.adapter.cancelMessageRead = false
            assertEquals(JsonInstant.encodeToString(node.messages), f.database.messageNodeDao().getNodesOfConversation(f.id.toString()).single().messages)
        }
    }

    @Test fun callerSuppliedCompleteSnapshotCannotDiscardUnreadLegacyBytes() = runBlocking {
        Fixture().use { f ->
            val legacy = JsonInstant.encodeToString(listOf(MessageNode(Uuid.random(), listOf(UIMessage.user("непрочитанная история")), 0)))
            f.seed(legacy)
            val entity = f.database.conversationDao().getConversationById(f.id.toString())!!
            val supplied = me.rerere.rikkahub.data.model.Conversation.ofId(f.id, Uuid.parse(entity.assistantId))
            val outcome = runCatching { f.repository.updateConversation(supplied.copy(title = "поддельная полнота")) }
            assertTrue("COMPLETE вызывающего не доказывает прочтение legacy", outcome.isFailure)
            assertEquals(entity, f.database.conversationDao().getConversationById(f.id.toString()))
        }
    }

    @Test fun recoveryCannotReplaceNodeOwnedByAnotherConversation() = runBlocking {
        Fixture().use { f ->
            val id = Uuid.random()
            val legacy = JsonInstant.encodeToString(listOf(MessageNode(id, listOf(UIMessage.user("legacy")), 0)))
            f.seed(legacy)
            val other = Uuid.random().toString()
            f.database.conversationDao().insert(ConversationEntity(other, Uuid.random().toString(), "другой чат", "[]", 1, 2, "[]", false))
            val foreign = MessageNodeEntity(id.toString(), other, 0, JsonInstant.encodeToString(listOf(UIMessage.user("неизменяемая чужая история"))), 0)
            f.database.messageNodeDao().insert(foreign)
            val result = runCatching { f.repository.getConversationById(f.id) }
            if (result.isSuccess) assertTrue(runCatching { f.repository.updateConversation(result.getOrThrow()!!) }.isFailure)
            assertEquals(legacy, f.database.conversationDao().getConversationById(f.id.toString())!!.nodes)
            assertEquals(listOf(foreign), f.database.messageNodeDao().getNodesOfConversation(other))
        }
    }

    private class Fixture : AutoCloseable {
        val adapter = BoundedCellFactory()
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .openHelperFactory(adapter).allowMainThreadQueries().build()
        val id = Uuid.random()
        private val owner = Uuid.random()
        val repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(), database,
            mockk(relaxed = true), MessageFtsManager(database))
        suspend fun seed(legacy: String, nodes: List<MessageNode> = emptyList()) {
            database.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at TEXT)")
            database.conversationDao().insert(ConversationEntity(id.toString(), owner.toString(), "исходное", legacy, 1L, 2L, "[]", false))
            database.messageNodeDao().insertAll(nodes.mapIndexed { index, node -> MessageNodeEntity(node.id.toString(), id.toString(), index, JsonInstant.encodeToString(node.messages), node.selectIndex) })
        }
        fun assertAdapterRejectsFullCell(sql: String) {
            val probe = runCatching { database.openHelper.readableDatabase.query(sql).use { c -> assertTrue(c.moveToFirst()); c.getString(0) } }
            assertTrue("Контроль: fixture обязан отвергать именно полный большой TEXT", probe.exceptionOrNull() is SQLiteBlobTooBigException)
        }
        override fun close() = database.close()
    }

    private class BoundedCellFactory : SupportSQLiteOpenHelper.Factory {
        @Volatile var cancelMessageRead = false
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val delegate = FrameworkSQLiteOpenHelperFactory().create(configuration)
            fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, arguments ->
                if (method.name == "query") {
                    val query = arguments?.firstOrNull()
                    val sql = if (query is SupportSQLiteQuery) query.sql else query?.toString().orEmpty()
                    if (cancelMessageRead && sql.contains("message_node", ignoreCase = true)) throw CancellationException("Адресная отмена чтения истории")
                }
                val result = try { method.invoke(database, *(arguments ?: emptyArray())) }
                    catch (error: InvocationTargetException) { throw error.targetException }
                if (method.name == "query" && result is Cursor) object : CursorWrapper(result) {
                    override fun getBlob(columnIndex: Int): ByteArray {
                        val value = super.getBlob(columnIndex)
                        if (value.size > 65_536) throw SQLiteBlobTooBigException("Тестовая граница одного BLOB: 65536 bytes")
                        return value
                    }
                    override fun getString(columnIndex: Int): String? {
                        val value = super.getString(columnIndex)
                        if (value != null && value.length > 65_536) throw SQLiteBlobTooBigException("Тестовая граница одного TEXT: 65536 UTF-16 units")
                        return value
                    }
                } else result
            } as SupportSQLiteDatabase
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
}
