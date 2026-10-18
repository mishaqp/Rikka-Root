package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel

internal val assistantManagerActions = listOf("list", "get", "list_options", "create", "update", "duplicate", "switch", "delete")

/** Names and descriptions are shared by discovery, inspection and the approval dialog. */
internal fun assistantSettingsSchema(): JsonObject = buildJsonObject {
    fun field(key: String, title: String, type: String, description: String = title, nullable: Boolean = false,
              minimum: Number? = null, maximum: Number? = null, items: JsonObject? = null) {
        put(key, buildJsonObject {
            put("title", title); put("description", description)
            if (nullable) put("type", buildJsonArray { add(type); add("null") }) else put("type", type)
            minimum?.let { put("minimum", JsonPrimitive(it)) }; maximum?.let { put("maximum", JsonPrimitive(it)) }
            items?.let { put("items", it) }
        })
    }
    fun array(key: String, title: String, description: String = "Идентификаторы из list_options") =
        field(key, title, "array", description, items = buildJsonObject { put("type", "string") })
    field("name", "Имя", "string", "Отображаемое имя ассистента; при создании обязательно непустое.")
    field("chatModelId", "Модель", "string", "UUID модели из list_options.models; null — глобальная модель.", nullable = true)
    put("avatar", buildJsonObject {
        put("title", "Аватар"); put("description", "dummy — стандартный; emoji — content; image — url (URI или URL изображения).")
        put("type", "object"); put("additionalProperties", false)
        put("properties", buildJsonObject {
            put("type", buildJsonObject { put("description", "Тип аватара: dummy, emoji или image."); put("type", "string"); put("enum", buildJsonArray { add("dummy"); add("emoji"); add("image") }) })
            put("content", buildJsonObject { put("description", "Эмодзи для типа emoji."); put("type", "string") }); put("url", buildJsonObject { put("description", "URI или URL изображения для типа image."); put("type", "string") })
        }); put("required", buildJsonArray { add("type") })
    })
    field("useAssistantAvatar", "Показывать аватар ассистента", "boolean")
    array("tags", "Теги")
    field("systemPrompt", "Системный промпт", "string")
    field("temperature", "Температура", "number", nullable = true, minimum = 0, maximum = 2)
    field("topP", "Top P", "number", "Порог вероятности выборки токенов Top P; null — значение провайдера.", nullable = true, minimum = 0, maximum = 1)
    field("contextMessageLimit", "Лимит сообщений контекста", "integer", "0 — без ограничения; положительное число — последние сообщения.", minimum = 0)
    field("autoCompressContext", "Автоматическое сжатие контекста", "boolean")
    field("autoCompressionTokenThreshold", "Порог сжатия в токенах", "integer", minimum = 2048, maximum = 2000000)
    field("streamOutput", "Потоковый вывод", "boolean")
    field("enableMemory", "Память", "boolean")
    field("useGlobalMemory", "Общая память", "boolean")
    field("enableRecentChatsReference", "Использовать недавние чаты", "boolean")
    field("messageTemplate", "Шаблон сообщения", "string", "Шаблон пользовательского сообщения. {{ message }} подставляет его текст.")
    put("presetMessages", buildJsonObject {
        put("title", "Предустановленные сообщения"); put("description", "Начальные реплики и продолжения диалога; роль USER или ASSISTANT.")
        put("type", "array"); put("items", buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "UUID, при отсутствии создаётся автоматически.") })
                put("role", buildJsonObject { put("description", "Роль начальной реплики: USER или ASSISTANT."); put("type", "string"); put("enum", buildJsonArray { add("USER"); add("ASSISTANT") }) })
                put("content", buildJsonObject { put("description", "Текст начальной реплики."); put("type", "string") })
            }); put("required", buildJsonArray { add("role"); add("content") })
        })
    })
    array("quickMessageIds", "Быстрые сообщения")
    put("regexes", buildJsonObject {
        put("title", "Правила замены регулярными выражениями"); put("description", "Преобразования текста пользователя или ассистента; visualOnly меняет только отображение.")
        put("type", "array"); put("items", buildJsonObject {
            put("type", "object"); put("additionalProperties", false); put("properties", buildJsonObject {
                for ((key, description) in mapOf("id" to "UUID правила; при отсутствии создаётся автоматически.", "name" to "Отображаемое название правила.", "findRegex" to "Регулярное выражение для поиска.", "replaceString" to "Текст замены найденного фрагмента.")) put(key, buildJsonObject { put("description", description); put("type", "string") })
                for ((key, description) in mapOf("enabled" to "Включено ли правило.", "visualOnly" to "Менять только отображение, сохраняя исходный текст для модели.")) put(key, buildJsonObject { put("description", description); put("type", "boolean") })
                put("affectingScope", buildJsonObject { put("description", "Роли сообщений, к которым применяется правило: USER и/или ASSISTANT."); put("type", "array"); put("items", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("USER"); add("ASSISTANT") }) }) })
            })
        })
    })
    put("reasoningLevel", buildJsonObject {
        put("title", "Уровень рассуждения"); put("description", "Режим рассуждения модели."); put("type", "string")
        put("enum", buildJsonArray { ReasoningLevel.entries.forEach { add(Json.encodeToJsonElement(ReasoningLevel.serializer(), it)) } })
    })
    field("maxTokens", "Максимум токенов ответа", "integer", "null — лимит модели; положительное число — ограничение ответа.", nullable = true, minimum = 1)
    field("tokenBudgetSoftCap", "Мягкий бюджет токенов", "integer", nullable = true, minimum = 1)
    field("tokenBudgetHardCap", "Жёсткий бюджет токенов", "integer", nullable = true, minimum = 1)
    field("subAgentConcurrencyLimit", "Параллельные субагенты", "integer", minimum = 1, maximum = 8)
    for ((key, itemKey, title) in listOf(Triple("customHeaders", "name", "Пользовательские HTTP-заголовки"), Triple("customBodies", "key", "Пользовательское тело запроса"))) {
        put(key, buildJsonObject {
            put("title", title); put("description", "Несекретные параметры запроса. Секреты скрыты как ••••; их изменение доступно только вручную в настройках.")
            put("type", "array"); put("items", buildJsonObject {
                put("type", "object"); put("additionalProperties", false); put("properties", buildJsonObject {
                    put(itemKey, buildJsonObject { put("description", "Имя несекретного параметра запроса."); put("type", "string") })
                    put("value", if (key == "customHeaders") buildJsonObject { put("description", "Несекретное строковое значение заголовка."); put("type", "string") } else buildJsonObject {
                        put("description", "Любое значение JSON: строка, число (включая целое), boolean, null, массив или объект с произвольными полями. Типы сохраняются; секреты изменяйте только вручную.")
                        put("anyOf", buildJsonArray {
                            for (type in listOf("string", "number", "boolean", "null")) add(buildJsonObject { put("type", type) })
                            add(buildJsonObject { put("type", "array"); put("items", buildJsonObject {}) })
                            add(buildJsonObject { put("type", "object"); put("additionalProperties", true) })
                        })
                    })
                }); put("required", buildJsonArray { add(itemKey); add("value") })
            })
        })
    }
    array("mcpServers", "Серверы MCP")
    array("localTools", "Локальные инструменты", "Строковые serialName из list_options.localTools; изменяет доступные функции, не правила безопасности и подтверждений.")
    field("rootMode", "Всё через root", "boolean", "При доступном root заменяет инструменты телефона на root_exec со шпаргалкой; сохраняет выбранные инструменты и не меняет разрешения, подтверждения и защиту команд.")
    field("enableWebSearch", "Веб-поиск", "boolean")
    field("workspaceId", "Рабочая папка", "string", "UUID из list_options.workspaces; null — без папки.", nullable = true)
    field("background", "Фон чата", "string", "URI или URL изображения; null — без фонового изображения.", nullable = true)
    field("backgroundOpacity", "Непрозрачность фона", "number", minimum = 0, maximum = 1)
    field("useGradientBackground", "Градиентный фон", "boolean")
    array("modeInjectionIds", "Режимы инъекций")
    array("lorebookIds", "Лорбуки")
    array("enabledSkills", "Навыки", "Имена установленных навыков из list_options.skills.")
    field("enableTimeReminder", "Напоминание о времени", "boolean")
    field("timeReminderIntervalMinutes", "Интервал напоминаний в минутах", "integer", minimum = 1)
    field("allowConversationSystemPrompt", "Отдельный системный промпт для чата", "boolean")
}

internal fun assistantFieldDescriptions(): JsonObject = JsonObject(assistantSettingsSchema().mapValues { (_, schema) ->
    buildJsonObject { put("name", schema.jsonObject.getValue("title")); put("description", schema.jsonObject.getValue("description")) }
})
