package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.uuid.Uuid

/** Real SQLite onUpgrade calls the production migration; no migration/result mock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ForwardHistoryPreservationPart3Test {
    @get:Rule val temporary = TemporaryFolder()
    private data class Row(val id: String, val messages: String, val index: Int, val selected: Int)
    private fun assistant(call: String = "call1") = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.ToolCall(call, "tool", "{}")))
    private fun result(call: String, text: String) = UIMessage(role = MessageRole.TOOL,
        parts = listOf(UIMessagePart.ToolResult(call, "tool", JsonPrimitive(text), JsonPrimitive("{}"))))
    private fun row(index: Int, vararg messages: UIMessage, selected: Int = 0) =
        Row(Uuid.random().toString(), JsonInstant.encodeToString(messages.toList()), index, selected)

    @Test fun unmatchedToolResultSurvivesForwardUpgradeOrEntireUpgradeRollsBack() = preserve(listOf(
        row(0, assistant()), row(1, result("other", "непривязанный результат")),
    ))
    @Test fun orphanToolResultSurvivesForwardUpgradeWithItsIdentityAndSelection() = preserve(listOf(
        row(0, UIMessage.user("вопрос")), row(1, result("orphan", "единственная копия результата")),
    ))
    @Test fun mixedRoleAlternativeNodeRetainsAllMessagesAndSelectedAlternative() = preserve(listOf(
        row(0, assistant()), row(1, result("call1", "результат инструмента"), UIMessage.user("другая ветка"), selected = 1),
    ))
    @Test fun alternativeToolResultsRetainBothPayloadsAndSelectedAlternative() = preserve(listOf(
        row(0, assistant()), row(1, result("call1", "первый ответ"), result("call1", "выбранный второй ответ"), selected = 1),
    ))

    private fun preserve(rows: List<Row>) {
        val path = temporary.newFile("${Uuid.random()}.db").absolutePath
        helper(path, 15, { db ->
            db.execSQL("CREATE TABLE message_node (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL)")
        }).use { handle -> rows.forEach { row ->
            handle.writableDatabase.execSQL("INSERT INTO message_node VALUES (?, 'chat', ?, ?, ?)", arrayOf<Any?>(row.id, row.index, row.messages, row.selected))
        } }
        val attempt = helper(path, 16, {}, { db, old, new ->
            assertEquals(15, old); assertEquals(16, new); Migration_15_16.migrate(db)
        })
        val upgrade = try { runCatching { attempt.writableDatabase.version } } finally { attempt.close() }
        val version = if (upgrade.isSuccess) 16 else 15
        helper(path, version, {}).use { handle ->
            val actual = readRows(handle.readableDatabase)
            assertEquals("Неоднозначные данные должны сохраниться целиком либо миграция должна откатиться", rows, actual)
            rows.forEachIndexed { index, source ->
                val expectedMessages = JsonInstant.decodeFromString<List<UIMessage>>(source.messages)
                val actualMessages = JsonInstant.decodeFromString<List<UIMessage>>(actual[index].messages)
                assertEquals(expectedMessages.map { it.id }, actualMessages.map { it.id })
                assertEquals(expectedMessages[source.selected], actualMessages[actual[index].selected])
            }
        }
    }

    @Test fun ordinaryMatchedToolResultStillMigratesSuccessfully() {
        val path = temporary.newFile("matched.db").absolutePath
        val a = row(0, assistant())
        val b = row(1, result("call1", "точный результат"))
        helper(path, 15, { it.execSQL("CREATE TABLE message_node (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL)") }).use { h ->
            listOf(a, b).forEach { r -> h.writableDatabase.execSQL("INSERT INTO message_node VALUES (?, 'chat', ?, ?, ?)", arrayOf<Any?>(r.id, r.index, r.messages, r.selected)) }
        }
        helper(path, 16, {}, { db, _, _ -> Migration_15_16.migrate(db) }).use { h ->
            val rows = readRows(h.writableDatabase)
            assertEquals(16, h.writableDatabase.version)
            assertEquals(1, rows.size)
            assertEquals(a.id, rows.single().id)
            assertEquals(0, rows.single().selected)
            val message = JsonInstant.decodeFromString<List<UIMessage>>(rows.single().messages).single()
            assertEquals(JsonInstant.decodeFromString<List<UIMessage>>(a.messages).single().id, message.id)
            val tool = message.parts.single() as UIMessagePart.Tool
            assertEquals("call1", tool.toolCallId)
            assertEquals("\"точный результат\"", (tool.output.single() as UIMessagePart.Text).text)
        }
    }

    @Test fun version6LegacyDiscriminatorsUpgradeForwardWithoutLosingMessages() {
        for (legacy in listOf("Text", "UIMessagePart.Text", "me.rerere.ai.ui.UIMessagePart.Text")) {
            val path = temporary.newFile("${Uuid.random()}.db").absolutePath
            val message = UIMessage.user("старый текст $legacy")
            val original = JsonInstant.encodeToString(listOf(message)).replace("\"type\":\"text\"", "\"type\":\"$legacy\"")
            assertTrue(original.contains("\"type\":\"$legacy\""))
            val id = Uuid.random().toString()
            val owner = Uuid.random().toString()
            helper(path, 6, { db ->
                db.execSQL("CREATE TABLE ConversationEntity (id TEXT NOT NULL PRIMARY KEY, assistant_id TEXT NOT NULL, title TEXT NOT NULL, messages TEXT NOT NULL, usage TEXT, create_at INTEGER NOT NULL, update_at INTEGER NOT NULL, truncate_index INTEGER NOT NULL)")
                db.execSQL("INSERT INTO ConversationEntity VALUES (?, ?, 'старый чат', ?, NULL, 11, 22, -1)", arrayOf(id, owner, original))
            }).use { it.writableDatabase }
            val upgraded = helper(path, 7, {}, { db, old, new ->
                assertEquals(6, old); assertEquals(7, new); Migration_6_7.migrate(db)
            })
            try {
                val result = runCatching { upgraded.writableDatabase }
                assertTrue("Известный legacy discriminator должен поддерживаться: $legacy; ошибка=${result.exceptionOrNull()?.javaClass?.simpleName}", result.isSuccess)
                result.getOrThrow().query("SELECT id, assistant_id, nodes, create_at, update_at FROM ConversationEntity").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(id, c.getString(0)); assertEquals(owner, c.getString(1))
                    val nodes = JsonInstant.decodeFromString<List<me.rerere.rikkahub.data.model.MessageNode>>(c.getString(2))
                    assertEquals(listOf(message), nodes.single().messages)
                    assertEquals(0, nodes.single().selectIndex)
                    assertEquals(11L, c.getLong(3)); assertEquals(22L, c.getLong(4))
                }
            } finally { upgraded.close() }
        }
    }

    private fun readRows(db: SupportSQLiteDatabase) = db.query("SELECT id, messages, node_index, select_index FROM message_node ORDER BY node_index").use { c ->
        buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3))) }
    }
    private fun helper(path: String, version: Int, create: (SupportSQLiteDatabase) -> Unit,
        upgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> error("Неожиданный переход версии") }): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(path).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = upgrade(db, oldVersion, newVersion)
            }).build())
}
