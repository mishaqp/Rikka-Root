package me.rerere.rikkahub.data.repository

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class DrawerConversationQueryTest {
    private lateinit var database: AppDatabase
    private val assistant = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
    private val today = 1_000_000_000L
    private val day = 86_400_000L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun oldestSortingHappensBeforePagingAndDoesNotOnlyReorderTheLoadedPage() = runBlocking {
        repeat(70) { insert(id = "chat-$it", title = "Title $it", update = today + it) }
        val result = load(query(oldest = true), size = 20)
        assertEquals((0 until 20).map { "chat-$it" }, result.map { it.id })
    }

    @Test fun titleSortingHappensGloballyBeforePaging() = runBlocking {
        repeat(70) { insert(id = "chat-$it", title = "Title ${it.toString().padStart(2, '0')}", update = today + it) }
        val result = load(query(alphabetical = true), size = 20)
        assertEquals((0 until 20).map { "chat-$it" }, result.map { it.id })
    }

    @Test fun titleOrderHandlesRussianNamesWithMixedCase() = runBlocking {
        insert("last", title = "Яблоко")
        insert("middle", title = "берёза")
        insert("first", title = "Арбуз")
        assertEquals(listOf("Арбуз", "берёза", "Яблоко"),
            load(query(alphabetical = true)).map { it.title })
    }

    @Test fun allUnfiledAndUserFolderFiltersRemainDistinctAndAssistantIsolated() = runBlocking {
        insert("unfiled")
        insert("folder-chat", folder = "folder")
        insert("other-assistant", owner = "other")
        assertEquals(setOf("unfiled", "folder-chat"), load(query()).map { it.id }.toSet())
        assertEquals(listOf("unfiled"), load(query(all = false)).map { it.id })
        assertEquals(listOf("folder-chat"), load(query(all = false, folder = "folder")).map { it.id })
    }

    @Test fun dateBucketsStayContiguousWhenSortingByName() = runBlocking {
        insert("today-b", title = "B", update = today)
        insert("today-a", title = "A", update = today + 1)
        insert("yesterday-a", title = "A", update = today - 1)
        insert("week-a", title = "A", update = today - 3 * day)
        insert("earlier-a", title = "A", update = today - 8 * day)
        assertEquals(listOf("today-a", "today-b", "yesterday-a", "week-a", "earlier-a"),
            load(query(alphabetical = true)).map { it.id })
    }

    @Test fun pinnedChatsStayInThePinnedSectionUnderAllSortModes() = runBlocking {
        insert("recent", update = today + 1)
        insert("pinned", update = today - 8 * day, pinned = true)
        assertEquals("pinned", load(query()).first().id)
        assertEquals("pinned", load(query(oldest = true)).first().id)
        assertEquals("pinned", load(query(alphabetical = true)).first().id)
    }

    @Test fun previewUsesOnlyTheSelectedLastMessageAndDoesNotLoadPreviousHistory() = runBlocking {
        insert("chat")
        node("chat", 0, listOf(UIMessage.user("старое сообщение")))
        node("chat", 1, listOf(UIMessage.assistant("скрытая ветка"), UIMessage.assistant("последнее сообщение")), selected = 1)
        val row = load(query()).single()
        assertEquals("последнее сообщение", row.previewText)
        assertFalse(row.hasTools)
    }

    @Test fun executedToolsAndWorkflowsAreDistinguishedFromPendingOrInactiveBranches() = runBlocking {
        for (id in listOf("pending", "executed", "inactive", "workflow")) insert(id)
        val pending = UIMessagePart.Tool("call", "root_exec", "{}")
        val executed = pending.copy(output = listOf(UIMessagePart.Text("ok")))
        node("pending", 0, listOf(UIMessage.assistant("").copy(parts = listOf(pending))))
        node("executed", 0, listOf(UIMessage.assistant("").copy(parts = listOf(executed))))
        node("inactive", 0, listOf(UIMessage.assistant("").copy(parts = listOf(executed)), UIMessage.assistant("selected")), selected = 1)
        node("workflow", 0, listOf(UIMessage.assistant("").copy(parts = listOf(executed.copy(toolName = "workflow_run")))))
        val rows = load(query()).associateBy { it.id }
        assertFalse(rows.getValue("pending").hasTools)
        assertFalse(rows.getValue("inactive").hasTools)
        assertTrue(rows.getValue("executed").hasTools)
        assertFalse(rows.getValue("executed").hasWorkflows)
        assertTrue(rows.getValue("workflow").hasWorkflows)
    }

    @Test fun toolOnlyLastMessageHasAReadablePreviewWithoutExposingArgumentsOrResults() = runBlocking {
        insert("tool-only")
        val tool = UIMessagePart.Tool("call", "root_exec", "private arguments",
            output = listOf(UIMessagePart.Text("private result")))
        node("tool-only", 0, listOf(UIMessage.assistant("").copy(parts = listOf(tool))))
        val row = load(query()).single()
        assertEquals("Вызов инструмента", row.previewText)
        assertTrue(row.hasTools)
    }

    @Test fun completedAndFailedServerToolsCountAsExecutedWhileInProgressDoesNot() = runBlocking {
        for (status in ServerToolStatus.entries) {
            insert(status.name)
            val tool = UIMessagePart.ServerTool("server-call", "web_search", status = status)
            node(status.name, 0, listOf(UIMessage.assistant("").copy(parts = listOf(tool))))
        }
        val rows = load(query()).associateBy { it.id }
        assertEquals("Вызов инструмента", rows.getValue(ServerToolStatus.FAILED.name).previewText)
        assertTrue(rows.getValue(ServerToolStatus.COMPLETED.name).hasTools)
        assertTrue(rows.getValue(ServerToolStatus.FAILED.name).hasTools)
        assertFalse(rows.getValue(ServerToolStatus.IN_PROGRESS.name).hasTools)
    }

    @Test fun malformedJsonOrAnInvalidSelectedBranchDoesNotBreakTheDrawer() = runBlocking {
        for (id in listOf("malformed", "invalid-index", "malformed-part")) insert(id)
        database.messageNodeDao().insert(MessageNodeEntity("bad", "malformed", 0, "not JSON", 0))
        node("invalid-index", 0, listOf(UIMessage.user("hidden")), selected = -1)
        database.messageNodeDao().insert(MessageNodeEntity("bad-part", "malformed-part", 0,
            "[{\"parts\":[\"invalid JSON value\"]}]", 0))
        val result = load(query())
        assertEquals(3, result.size)
        assertTrue(result.all { it.previewText.isEmpty() && !it.hasTools })
    }

    @Test fun mediaPreviewIsReadableAndDoesNotContainTheAttachmentUrl() = runBlocking {
        insert("image")
        node("image", 0, listOf(UIMessage.user("").copy(parts = listOf(UIMessagePart.Image("file:///private/image.png")))))
        assertEquals("Изображение", load(query()).single().previewText)
    }

    @Test fun sqliteBuildsWithoutJsonCanStillLoadTheMetadataOnlyProjection() = runBlocking {
        insert("chat")
        val row = load(query(previews = false)).single()
        assertEquals("chat", row.id)
        assertEquals("", row.previewText)
        assertFalse(row.hasTools)
    }

    @Test fun renamingChangesOnlyTheTitleAndKeepsConversationConfigurationAndMessages() = runBlocking {
        insert("chat", folder = "folder")
        node("chat", 0, listOf(UIMessage.user("message to preserve")))
        val before = database.conversationDao().getConversationById("chat")!!
        val messages = database.messageNodeDao().getNodesOfConversation("chat")
        database.conversationDao().updateTitle("chat", "Новое название")
        val after = database.conversationDao().getConversationById("chat")!!
        assertEquals(before.copy(title = "Новое название"), after)
        assertEquals(messages, database.messageNodeDao().getNodesOfConversation("chat"))
    }

    private suspend fun insert(id: String, title: String = id, update: Long = today,
        folder: String = "", owner: String = assistant, pinned: Boolean = false) {
        database.conversationDao().insert(ConversationEntity(
            id = id, assistantId = owner, title = title, nodes = "[]", createAt = update,
            updateAt = update, chatSuggestions = "[]", isPinned = pinned,
            folderId = folder, config = "{\"fixture\":\"retained\"}",
        ))
    }

    private suspend fun node(id: String, index: Int, messages: List<UIMessage>, selected: Int = 0) {
        database.messageNodeDao().insert(MessageNodeEntity("$id-$index", id, index,
            JsonInstant.encodeToString(messages), selected))
    }

    private fun query(oldest: Boolean = false, alphabetical: Boolean = false, all: Boolean = true,
        folder: String? = null, previews: Boolean = true): SimpleSQLiteQuery = drawerConversationQuery(
        assistantId = assistant, folderId = folder, includeAllFolders = all,
        sortByCreateTime = false, oldestFirst = oldest, alphabetical = alphabetical,
        todayMillis = today, yesterdayMillis = today - day, weekStartMillis = today - 4 * day,
        includePreviews = previews,
    )

    private suspend fun load(query: SimpleSQLiteQuery, size: Int = 40): List<DrawerConversationEntity> {
        val source = database.conversationDao().getDrawerConversationsPaging(query)
        try {
            return when (val result = source.load(PagingSource.LoadParams.Refresh(null, size, false))) {
                is PagingSource.LoadResult.Page -> result.data
                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> error("Drawer query unexpectedly invalidated")
            }
        } finally {
            source.invalidate()
        }
    }
}
