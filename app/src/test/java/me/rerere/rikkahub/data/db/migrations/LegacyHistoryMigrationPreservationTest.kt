package me.rerere.rikkahub.data.db.migrations

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Upgrade must preserve undecodable history for recovery, or reject and roll back the upgrade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyHistoryMigrationPreservationTest {
    @Test fun version11MalformedHistoryIsNotReplacedByAnEmptyArray() {
        database { db ->
            db.execSQL("CREATE TABLE ConversationEntity (id TEXT NOT NULL PRIMARY KEY, nodes TEXT NOT NULL)")
            // A truncated historical JSON record is still the only recoverable copy of this text.
            val original = """[{"id":"legacy-node","messages":[{"role":"USER","parts":[{"type":"text","text":"recoverable original message"}]}]"""
            db.execSQL("INSERT INTO ConversationEntity(id, nodes) VALUES (?, ?)", arrayOf("chat", original))

            runCatching { Migration_11_12.migrate(db) }

            db.query("SELECT nodes FROM ConversationEntity WHERE id = 'chat'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("An unreadable source history must survive migration or a rolled-back failure", original, cursor.getString(0))
            }
        }
    }

    @Test fun version11UnsupportedNodeStructureIsNotSilentlyDiscarded() {
        val unsupportedHistories = listOf(
            """["recoverable non-object node"]""",
            """[{"id":"legacy-node","recoverable_metadata":"message field is missing"}]""",
            """[{"id":"legacy-node","messages":{"recoverable_text":"messages must be an array"}}]""",
        )
        unsupportedHistories.forEach { original ->
            database { db ->
                db.execSQL("CREATE TABLE ConversationEntity (id TEXT NOT NULL PRIMARY KEY, nodes TEXT NOT NULL)")
                db.execSQL("INSERT INTO ConversationEntity(id, nodes) VALUES (?, ?)", arrayOf("chat", original))

                runCatching { Migration_11_12.migrate(db) }

                db.query("SELECT nodes FROM ConversationEntity WHERE id = 'chat'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Unsupported node structure must not erase its recoverable source: $original", original, cursor.getString(0))
                }
            }
        }
    }

    @Test fun supportedVersion11HistoryStillMigratesThroughVersion16() {
        database { db ->
            db.execSQL("CREATE TABLE ConversationEntity (id TEXT NOT NULL PRIMARY KEY, nodes TEXT NOT NULL)")
            val messages = JsonInstant.encodeToString(listOf(UIMessage.user("normal old backup message")))
            val nodes = """[{"id":"legacy-node","messages":$messages,"selectIndex":0}]"""
            db.execSQL("INSERT INTO ConversationEntity(id, nodes) VALUES (?, ?)", arrayOf("chat", nodes))

            Migration_11_12.migrate(db)
            Migration_15_16.migrate(db)

            db.query("SELECT messages, node_index, select_index FROM message_node WHERE conversation_id = 'chat'").use { cursor ->
                assertTrue("Normal old history must remain importable", cursor.moveToFirst())
                assertEquals(messages, cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertTrue("Only the original node should be imported", !cursor.moveToNext())
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun version15ToolMigrationDoesNotDeleteAnUndecodableNeighbourNode() {
        database { db ->
            db.execSQL("CREATE TABLE message_node (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL)")
            val legacyTool = JsonInstant.encodeToString(listOf(UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ToolCall("call", "get_time_info", "{}")),
            )))
            // Syntactically valid JSON with a part that this installed build cannot decode.
            val opaqueHistory = JsonInstant.encodeToString(listOf(UIMessage.user("original attachment metadata")))
                .replace("\"type\":\"text\"", "\"type\":\"unsupported_legacy_part\"")
            assertTrue("Fixture must contain an unsupported part", opaqueHistory.contains("unsupported_legacy_part"))
            db.execSQL("INSERT INTO message_node VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>("legacy", "chat", 0, legacyTool, 0))
            db.execSQL("INSERT INTO message_node VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>("opaque", "chat", 1, opaqueHistory, 0))

            runCatching { Migration_15_16.migrate(db) }

            db.query("SELECT messages, node_index, select_index FROM message_node WHERE id = 'opaque'").use { cursor ->
                assertTrue("Migrating a neighbouring ToolCall must not delete the original undecodable node", cursor.moveToFirst())
                assertEquals(opaqueHistory, cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
        }
    }

    private fun database(block: (SupportSQLiteDatabase) -> Unit) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try { block(helper.writableDatabase) } finally { helper.close() }
    }
}
