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
 */
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
): SimpleSQLiteQuery {
    val folderCondition = when {
        includeAllFolders -> ""
        folderId == null -> " AND c.folder_id = ''"
        else -> " AND c.folder_id = ?"
    }
    val dateColumn = if (sortByCreateTime) "c.create_at" else "c.update_at"
    val direction = if (oldestFirst) "ASC" else "DESC"
    val groupDirection = if (oldestFirst) "DESC" else "ASC"
    val titleOrder = if (alphabetical) "c.title COLLATE LOCALIZED ASC, " else ""
    val previewProjection = if (includePreviews) {
        """
            COALESCE((SELECT substr(group_concat(
                CASE WHEN json_valid(part.value) THEN
                    CASE json_extract(part.value, '$.type')
                        WHEN 'text' THEN json_extract(part.value, '$.text')
                        WHEN 'image' THEN 'Изображение'
                        WHEN 'video' THEN 'Видео'
                        WHEN 'audio' THEN 'Аудио'
                        WHEN 'document' THEN 'Документ'
                        WHEN 'tool' THEN 'Вызов инструмента'
                        WHEN 'tool_call' THEN 'Вызов инструмента'
                        WHEN 'tool_result' THEN 'Вызов инструмента'
                        WHEN 'server_tool' THEN 'Вызов инструмента'
                    END
                END, ' '), 1, 240)
                FROM message_node mn, json_each($SELECTED_PARTS_JSON) part
                WHERE mn.id = (SELECT last.id FROM message_node last
                    WHERE last.conversation_id = c.id ORDER BY last.node_index DESC LIMIT 1)
            ), '') AS previewText,
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
        add(todayMillis)
        add(yesterdayMillis)
        add(weekStartMillis)
    }.toTypedArray()
    return SimpleSQLiteQuery(
        """
            SELECT c.id, c.assistant_id AS assistantId, c.title, c.is_pinned AS isPinned,
                c.create_at AS createAt, c.update_at AS updateAt, c.folder_id AS folderId,
                $previewProjection
            FROM conversationentity c
            WHERE c.assistant_id = ?$folderCondition
            ORDER BY c.is_pinned DESC,
                CASE WHEN $dateColumn >= ? THEN 0
                    WHEN $dateColumn >= ? THEN 1
                    WHEN $dateColumn >= ? THEN 2 ELSE 3 END $groupDirection,
                $titleOrder$dateColumn $direction, c.id ASC
        """.trimIndent(),
        arguments,
    )
}

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
