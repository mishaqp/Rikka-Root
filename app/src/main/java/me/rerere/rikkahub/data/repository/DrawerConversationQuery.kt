package me.rerere.rikkahub.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import me.rerere.rikkahub.data.model.Conversation

/** Read-only drawer projection. It is not a Room entity or a serialized conversation. */
data class DrawerConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String,
    val previewText: String,
    val hasTools: Boolean,
    val hasWorkflows: Boolean,
)

data class DrawerConversationSummary(
    val conversation: Conversation,
    val previewText: String,
    val hasTools: Boolean,
    val hasWorkflows: Boolean,
)

/**
 * Only the presentation query changes: mutations, persistent sort settings and older paging
 * consumers continue to use their existing APIs. Sorting happens before LIMIT/OFFSET in SQLite.
 * The legacy relative-date arguments remain for compatibility with drawer callers.
 */
@Suppress("UNUSED_PARAMETER")
internal fun drawerConversationQuery(
    assistantId: String,
    folderId: String?,
    includeAllFolders: Boolean,
    sortByCreateTime: Boolean,
    oldestFirst: Boolean,
    alphabetical: Boolean,
    todayMillis: Long,
    yesterdayMillis: Long,
    weekStartMillis: Long,
    includePreviews: Boolean = true,
    searchQuery: String = "",
): SimpleSQLiteQuery {
    val folderCondition = when {
        includeAllFolders -> ""
        folderId == null -> " AND c.folder_id = ''"
        else -> " AND c.folder_id = ?"
    }
    val dateColumn = if (sortByCreateTime) "c.create_at" else "c.update_at"
    val searchCondition = if (searchQuery.isBlank()) "" else " AND c.title GLOB ?"
    val direction = if (oldestFirst) "ASC" else "DESC"
    // SQLite localtime follows the device zone, matching the Java system zone used by headers.
    // Pinned chats share one section, so their title order must not be split into days.
    val dayOrder = """
        CASE WHEN c.is_pinned = 0 THEN
            strftime('%Y-%m-%d', $dateColumn / 1000.0, 'unixepoch', 'localtime')
        END $direction
    """.trimIndent()
    val titleOrder = if (alphabetical) "c.title COLLATE LOCALIZED ASC, " else ""
    val previewProjection = if (includePreviews) {
        """
            COALESCE((SELECT substr(json_extract(part.value, '$.text'), 1, 240)
                FROM message_node mn, json_each($SELECTED_PARTS_JSON) part
                WHERE mn.conversation_id = c.id
                    AND CASE WHEN json_valid(mn.messages) AND mn.select_index >= 0 THEN
                        json_extract(mn.messages, '$[' || mn.select_index || '].role') IN ('user', 'assistant')
                        ELSE 0 END
                    AND CASE WHEN part.type = 'object' AND json_valid(part.value) THEN
                        json_extract(part.value, '$.type') = 'text'
                        AND json_type(part.value, '$.text') = 'text'
                        -- Match Kotlin isNotBlank in the live conversation projection.
                        AND trim(json_extract(part.value, '$.text'),
                            char(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760,
                                8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202,
                                8232, 8233, 8239, 8287, 12288)) != ''
                        ELSE 0 END
                ORDER BY mn.node_index DESC, CAST(part.key AS INTEGER) DESC LIMIT 1
            ), 'Работа с инструментами') AS previewText,
            EXISTS(SELECT 1 FROM message_node mn, json_each($SELECTED_PARTS_JSON) part
                WHERE mn.conversation_id = c.id AND ($EXECUTED_TOOL_SQL)) AS hasTools,
            EXISTS(SELECT 1 FROM message_node mn, json_each($SELECTED_PARTS_JSON) part
                WHERE mn.conversation_id = c.id AND ($EXECUTED_TOOL_SQL)
                AND CASE WHEN json_valid(part.value) THEN
                    json_extract(part.value, '$.toolName') LIKE 'workflow\_%' ESCAPE '\'
                    ELSE 0 END) AS hasWorkflows
        """.trimIndent()
    } else {
        "'' AS previewText, 0 AS hasTools, 0 AS hasWorkflows"
    }
    val arguments = buildList<Any> {
        add(assistantId)
        if (!includeAllFolders && folderId != null) add(folderId)
        if (searchQuery.isNotBlank()) add(drawerTitleSearchPattern(searchQuery.trim()))
    }.toTypedArray()
    return SimpleSQLiteQuery(
        """
            SELECT c.id, c.assistant_id AS assistantId, c.title, c.is_pinned AS isPinned,
                c.create_at AS createAt, c.update_at AS updateAt, c.folder_id AS folderId,
                $previewProjection
            FROM conversationentity c
            WHERE c.assistant_id = ?$folderCondition$searchCondition
            ORDER BY c.is_pinned DESC,
                $dayOrder,
                $titleOrder$dateColumn $direction, c.id ASC
        """.trimIndent(),
        arguments,
    )
}

/** SQLite NOCASE only folds ASCII; explicit letter pairs also match Russian topic names. */
internal fun drawerTitleSearchPattern(query: String): String = buildString {
    append('*')
    query.forEach { character ->
        when (character) {
            '*' -> append("[*]")
            '?' -> append("[?]")
            '[' -> append("[[]")
            ']' -> append("[]]")
            else -> {
                val pair = drawerTitleSearchCasePair(character)
                if (pair.lower != pair.upper) append("[${pair.lower}${pair.upper}]") else append(character)
            }
        }
    }
    append('*')
}

/**
 * The same literal substring and exact character pairs as [drawerTitleSearchPattern].
 * Broad equalsIgnoreCase folds additional variants (ς/σ, ı/İ/i, ſ/s) that SQLite's GLOB does not.
 * Selection must never add those invisible rows. UTF-16 pairs retain supplementary literals too.
 */
internal fun drawerTitleMatchesSearchQuery(title: String, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return true
    if (trimmed.length > title.length) return false
    val pairs = trimmed.map(::drawerTitleSearchCasePair)
    return (0..title.length - pairs.size).any { start ->
        pairs.indices.all { offset ->
            val actual = title[start + offset]
            val pair = pairs[offset]
            actual == pair.lower || actual == pair.upper
        }
    }
}

private data class DrawerTitleSearchCasePair(val lower: Char, val upper: Char)

private fun drawerTitleSearchCasePair(character: Char): DrawerTitleSearchCasePair =
    DrawerTitleSearchCasePair(character.lowercaseChar(), character.uppercaseChar())

// Guard malformed legacy JSON and invalid selected branches before evaluating JSON paths.
private const val SELECTED_PARTS_JSON =
    "CASE WHEN json_valid(mn.messages) AND mn.select_index >= 0 THEN " +
        "CASE WHEN json_type(mn.messages, '$[' || mn.select_index || '].parts') = 'array' " +
        "THEN json_extract(mn.messages, '$[' || mn.select_index || '].parts') ELSE '[]' END " +
        "ELSE '[]' END"

private const val EXECUTED_TOOL_SQL =
    "CASE WHEN json_valid(part.value) THEN CASE json_extract(part.value, '$.type') " +
        "WHEN 'tool' THEN CASE WHEN json_type(part.value, '$.output') = 'array' " +
        "THEN json_array_length(part.value, '$.output') > 0 ELSE 0 END " +
        "WHEN 'tool_result' THEN 1 " +
        "WHEN 'server_tool' THEN json_extract(part.value, '$.status') IN ('completed', 'failed') " +
        "ELSE 0 END ELSE 0 END"
