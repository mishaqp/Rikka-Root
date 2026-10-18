package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.deviceToolParts
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/** Reads retain the invoking assistant's scope; one extra row reveals actual truncation. */
fun createConversationTools(conversationRepo: ConversationRepository, assistantId: Uuid): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = "Недавние чаты текущего ассистента: заголовки и даты, сначала закреплённые, затем по времени изменения. Для содержимого используйте conversation_search. Ответ содержит items, count, total (null, если полный размер неизвестен) и truncated; при ограничении выдачи следуйте message.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("limit", conversationLimitSchema(10, 30))
        }) },
        execute = { input -> deviceToolParts {
            val args = input as? JsonObject ?: error("Укажите параметры как JSON-объект.")
            val limit = conversationLimit(args, 10, 30)
            val rows = conversationRepo.getRecentConversations(assistantId, limit + 1)
            val items = JsonArray(rows.take(limit).map { conversation -> buildJsonObject {
                put("id", conversation.id.toString()); put("title", conversation.title.ifBlank { "Без названия" })
                put("last_chat", conversation.updateAt.toLocalDate())
            } })
            conversationResults(items, rows.size > limit, rows.size,
                "Показана часть недавних чатов. Увеличьте limit до 30 или используйте conversation_search с уточняющими словами.")
        } },
    ),
    Tool(
        name = "conversation_search",
        description = "Полнотекстовый поиск только в чатах текущего ассистента. Укажите точные ключевые слова; совпадения выделены [скобками]. Ответ items содержит conversation_id, title, snippet, date; count — число выданных строк, total — точное число найденных строк либо null, если достигнут предел. При truncated уточните query или увеличьте limit до 50.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("query", buildJsonObject { put("type", "string"); put("description", "Ключевые слова для поиска в сообщениях текущего ассистента; обязательная строка.") })
            put("limit", conversationLimitSchema(15, 50))
        }, listOf("query")) },
        execute = { input -> deviceToolParts {
            val args = input as? JsonObject ?: error("Укажите параметры как JSON-объект с query.")
            val query = (args["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Укажите query — строку с ключевыми словами для поиска.")
            val limit = conversationLimit(args, 15, 50)
            val rows = conversationRepo.searchMessages(query, MessageSearchSort.RELEVANCE, assistantId, limit + 1)
            val items = JsonArray(rows.take(limit).map { row -> buildJsonObject {
                put("conversation_id", row.conversationId); put("title", row.title.ifBlank { "Без названия" })
                put("snippet", row.snippet); put("date", row.updateAt.toLocalDate())
            } })
            conversationResults(items, rows.size > limit, rows.size,
                "Найдены дополнительные совпадения. Уточните query или увеличьте limit до 50; повтор того же запроса возвращает ту же первую часть.")
        } },
    ),
)

private fun conversationLimitSchema(default: Int, max: Int) = buildJsonObject {
    put("type", "integer"); put("default", default)
    put("description", "Число строк без кавычек; по умолчанию $default. Значение ограничивается диапазоном 1–$max.")
}
private fun conversationLimit(args: JsonObject, default: Int, max: Int): Int =
    (if ("limit" !in args) default else (args["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: error("Укажите limit как целое число без кавычек." )).coerceIn(1, max)

private fun conversationResults(items: JsonArray, truncated: Boolean, knownRows: Int, hint: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("items", items); put("count", items.size); put("truncated", truncated)
        put("total", if (truncated) JsonNull else JsonPrimitive(knownRows))
        if (truncated) put("message", hint)
    }.toString()))
