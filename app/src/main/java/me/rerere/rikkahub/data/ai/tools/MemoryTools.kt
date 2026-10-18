package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.deviceToolParts
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryNotFoundException
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = "Память хранит сведения между чатами текущего ассистента; при включённой общей памяти используется общая область. create добавляет content; edit меняет content записи по id; delete удаляет запись по id. Объединяйте похожие записи, предпочитая edit. Записи появятся в следующих чатах в memories. Сохраняйте предпочтения, планы и рабочие заметки; не сохраняйте чувствительные сведения и не раскрывайте память без просьбы пользователя. Сегодня ${LocalDate.now().toLocalString(true)}.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Операция: create — создать, edit — изменить, delete — удалить.")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Целочисленный id существующей записи из памяти; обязателен для edit и delete, без кавычек.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Текст записи памяти; обязателен для create и edit.")
                    })
                },
                required = listOf("action")
            )
        },
        execute = { deviceToolParts {
            val params = it as? kotlinx.serialization.json.JsonObject ?: error("Укажите параметры памяти как JSON-объект.")
            val action = (params["action"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: error("Укажите action: create, edit или delete для работы с памятью.")
            val payload = try {
                when (action) {
                    "create" -> {
                        val content = (params["content"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: error("Укажите content — текст записи памяти.")
                        json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
                    }

                    "edit" -> {
                        val id = (params["id"] as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull ?: error("Укажите id существующей записи памяти как целое число без кавычек.")
                        val content = (params["content"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull ?: error("Укажите content — текст записи памяти.")
                        json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                    }

                    "delete" -> {
                        val id = (params["id"] as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull ?: error("Укажите id существующей записи памяти как целое число без кавычек.")
                        onDelete(id)
                        buildJsonObject {
                            put("success", true)
                            put("id", id)
                        }
                    }

                    else -> error("Укажите action: create, edit или delete для работы с памятью.")
                }
            } catch (notFound: MemoryNotFoundException) {
                buildJsonObject {
                    put("code", "not_found")
                    put("error", notFound.message)
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } }
    )
)
