package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Задать пользователю вопросы для уточнения задачи, получения информации или подтверждения.
        Можно предложить варианты, но пользователь всегда может ответить своим текстом.
        При множественном выборе можно сочетать выбранные варианты и свой ответ.
        Ответ возвращается JSON-объектом, где ID вопросов соответствуют ответам пользователя.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "Вопросы, которые нужно показать пользователю для уточнения задачи или подтверждения.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Уникальный ID вопроса для сопоставления с ответом пользователя.")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "Текст вопроса, который увидит пользователь.")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Необязательный список предлагаемых вариантов; пользователь также может ввести свой ответ."
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Вид ответа: text — свободный текст (обычный режим), single — один вариант или свой текст, multi — несколько вариантов и/или свой текст."
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
