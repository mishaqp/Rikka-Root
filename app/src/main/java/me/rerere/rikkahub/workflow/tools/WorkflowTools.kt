package me.rerere.rikkahub.workflow.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.execution.workflowSummaryPrefix
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowConstants
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.ai.tools.local.deviceToolFailureReason
import kotlinx.coroutines.CancellationException

/**
 * Phase 12 — the seven `workflow_*` tools the LLM uses to author and manage workflows.
 *
 * `knownToolNamesProvider` is a lambda over the assistant's currently-registered tool
 * names (matches the cron-job pattern at LocalTools.kt:579-580). The list is built by
 * the caller after registering everything else, so workflow_create can validate that
 * action tools actually exist on the assistant before persistence.
 *
 * Approval semantics: workflow_create / workflow_update / workflow_delete /
 * workflow_set_enabled / workflow_run are all in ToolApprovalDefaults.ALWAYS_ASK with the
 * approval prompt rendered by [WorkflowApprovalRenderer] (so the user sees readable
 * "Create workflow X — when WiFi connects, run …" instead of raw JSON). workflow_list
 * and workflow_get are read-only — no approval needed.
 */

fun workflowCreateTool(
    repository: WorkflowRepository,
    knownToolNamesProvider: () -> List<String>,
    callerAssistantId: String?,
    callerConversationId: String? = null,
    callerConversationConfig: ConversationConfig? = null,
): Tool = Tool(
    name = "workflow_create",
    description = "Создать повторяющийся событийный сценарий текущего ассистента: trigger запускает проверку всех conditions, затем actions выполняются по порядку с действующими разрешениями и HARDLINE. Однократный запуск по времени создавайте через schedule_job с schedule_type=once. time_cron поддерживает cron либо time_of_day с days_of_week; остальные типы и параметры перечислены в definition. Геозоны, уведомления и отслеживание приложений, условия заката/рассвета и активного приложения в Root недоступны: такие сценарии можно хранить только выключенными. workflow_run внутри actions запрещён; интерактивные действия и обязательные подтверждения недоступны в фоне.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("definition", workflowDefinitionSchema(requireId = false))
            },
            required = listOf("definition"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val definitionEl = json.jsonObject["definition"]
            ?: return@Tool errorResponse("missing_definition", "Укажите definition как объект с описанием сценария.")
        workflowSchemaTypeError(definitionEl, workflowDefinitionSchema(requireId = false), "definition")?.let {
            return@Tool errorResponse("invalid_definition", it)
        }
        val parsed = WorkflowJson.parse(definitionEl.toString(), knownToolNamesProvider().toSet())
        when (parsed) {
            is WorkflowJson.ParseResult.Err -> errorResponse(parsed.error, workflowParseErrorDetail(parsed.error, parsed.detail))
            is WorkflowJson.ParseResult.Ok -> {
                if (callerAssistantId == null) return@Tool errorResponse("missing_owner", "Не удалось определить ассистента-владельца сценария.")
                if (repository.getById(parsed.definition.id) != null) return@Tool errorResponse("already_exists", "Сценарий уже существует; используйте workflow_update.")
                // Stamp the authoring assistant from the caller context — the engine reads
                // this back at fire time to resolve the right tool surface deterministically.
                // Root obtains the owner from trusted invocation state; model-supplied ids
                // never transfer tool authority.
                val def = parsed.definition.copy(
                    authoringAssistantId = callerAssistantId,
                    authoringConversationId = callerConversationId,
                    callerConversationConfig = callerConversationConfig,
                )
                runCatching { repository.upsert(def) }.fold(
                    onSuccess = {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("ok", true)
                            put("id", def.id)
                            put("name", def.name)
                        }.toString()))
                    },
                    onFailure = {
                        if (it is CancellationException) throw it
                        errorResponse("persist_failed", deviceToolFailureReason(it))
                    }
                )
            }
        }
    }
).withWorkflowErrors()

fun workflowListTool(repository: WorkflowRepository): Tool = Tool(
    name = "workflow_list",
    description = "Список сценариев: id, name, enabled, trigger_type и состояние последнего запуска. enabled_only=true оставляет включённые сценарии; по умолчанию false.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("enabled_only", buildJsonObject {
                    put("type", "boolean"); put("default", false)
                    put("description", "Только включённые сценарии: true или false без кавычек; по умолчанию false.")
                })
            },
        )
    },
    needsApproval = { true },
    execute = { json ->
        val enabledOnly = workflowBoolean(json.jsonObject, "enabled_only", false)
        val all = if (enabledOnly) repository.listEnabled() else repository.listAll()
        val payload = buildJsonObject {
            put("count", all.size)
            put("items", buildJsonArray {
                for (loaded in all) {
                    val e = loaded.entity
                    val def = loaded.definition
                    add(buildJsonObject {
                        put("id", e.id)
                        put("name", e.name)
                        put("enabled", e.enabled)
                        put("trigger_type", triggerTypeKey(def))
                        put("last_run_at_ms", e.lastRunAtMs?.let(::JsonPrimitive) ?: JsonNull)
                        put("last_run_status", JsonPrimitive(e.lastRunStatus))
                        e.lastRunStatus?.takeIf { it != "SUCCESS" }?.let { put("last_run_message", workflowRunMessage(it, e.lastRunError)) }
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
).withWorkflowErrors()

fun workflowGetTool(repository: WorkflowRepository): Tool = Tool(
    name = "workflow_get",
    description = "Получить полное описание сценария и до 10 последних запусков. Используйте id из workflow_list перед изменением. Время Unix и длительность возвращаются числами в миллисекундах; отсутствующие значения — null. history_count — выданные запуски, history_truncated — есть более старые, history_total — точное число либо null. Продолжение истории через инструмент недоступно; карточка сценария показывает до 20 последних запусков.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Строковый id сценария из workflow_list.")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val id = workflowString(json.jsonObject, "id")
            ?: return@Tool errorResponse("missing_id", "Укажите id сценария из workflow_list.")
        val loaded = repository.getById(id)
            ?: return@Tool errorResponse("not_found", "Сценарий с id=$id не найден. Выберите существующий id из workflow_list.")
        val fetchedRuns = repository.lastRuns(id, limit = 11)
        val runs = fetchedRuns.take(10)
        val historyTruncated = fetchedRuns.size > 10
        val payload = buildJsonObject {
            put("ok", true)
            put("definition", WorkflowJson.parseStored(loaded.entity.definitionJson)?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(WorkflowJson.encode(it))
            } ?: JsonPrimitive(loaded.entity.definitionJson))
            put("last_run_at_ms", loaded.entity.lastRunAtMs?.let(::JsonPrimitive) ?: JsonNull)
            put("last_run_status", JsonPrimitive(loaded.entity.lastRunStatus))
            put("last_run_error", JsonPrimitive(loaded.entity.lastRunError))
            loaded.entity.lastRunStatus?.takeIf { it != "SUCCESS" }?.let { put("last_run_message", workflowRunMessage(it, loaded.entity.lastRunError)) }
            put("runs_today_count", loaded.entity.runsTodayCount)
            put("runs_today_date", loaded.entity.runsTodayDate)
            put("history_count", runs.size)
            put("history_truncated", historyTruncated)
            put("history_total", if (historyTruncated) JsonNull else JsonPrimitive(runs.size))
            if (historyTruncated) put("history_message", "Показаны 10 последних запусков. Карточка сценария в настройках показывает до 20 последних запусков; продолжение более старой истории через workflow_get недоступно, повтор возвращает ту же первую часть.")
            put("history", buildJsonArray {
                for (r in runs) {
                    add(buildJsonObject {
                        put("fired_at_ms", JsonPrimitive(r.firedAtMs))
                        put("status", r.status.name)
                        put("duration_ms", JsonPrimitive(r.durationMs))
                        put("error", JsonPrimitive(r.errorMessage))
                        if (r.status.name != "SUCCESS") put("message", workflowRunMessage(r.status.name, r.errorMessage))
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
).withWorkflowErrors()

fun workflowUpdateTool(
    repository: WorkflowRepository,
    knownToolNamesProvider: () -> List<String>,
    callerAssistantId: String?,
    callerConversationId: String? = null,
    callerConversationConfig: ConversationConfig? = null,
): Tool = Tool(
    name = "workflow_update",
    description = "Полностью заменить описание существующего сценария текущего ассистента. Передайте definition с id из workflow_get; остальные поля имеют ту же схему, что workflow_create. Владелец и привязка к исходному чату сохраняются.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("definition", workflowDefinitionSchema(requireId = true))
            },
            required = listOf("definition"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val definitionEl = json.jsonObject["definition"]
            ?: return@Tool errorResponse("missing_definition", "Укажите definition как объект с описанием сценария.")
        workflowSchemaTypeError(definitionEl, workflowDefinitionSchema(requireId = false), "definition")?.let {
            return@Tool errorResponse("invalid_definition", it)
        }
        val parsed = WorkflowJson.parse(definitionEl.toString(), knownToolNamesProvider().toSet())
        when (parsed) {
            is WorkflowJson.ParseResult.Err -> errorResponse(parsed.error, workflowParseErrorDetail(parsed.error, parsed.detail))
            is WorkflowJson.ParseResult.Ok -> {
                val existing = repository.getById(parsed.definition.id)
                    ?: return@Tool errorResponse("not_found", "Сценарий с id=${parsed.definition.id} не найден. Для создания используйте workflow_create.")
                if (callerAssistantId == null || existing.definition.authoringAssistantId != callerAssistantId)
                    return@Tool errorResponse("owner_mismatch", "Изменять сценарий может только ассистент, создавший его.")
                // Preserve the existing authoring assistant — workflow_update is for body
                // edits, not for transferring ownership. If the LLM tries to change it,
                // we reject other-owner calls and keep the original settings snapshot.
                val def = parsed.definition.copy(
                    authoringAssistantId = existing.definition.authoringAssistantId,
                    authoringConversationId = existing.definition.authoringConversationId,
                    callerConversationConfig = existing.definition.callerConversationConfig,
                )
                runCatching { repository.upsert(def) }.fold(
                    onSuccess = {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("ok", true)
                            put("id", def.id)
                            put("name", def.name)
                        }.toString()))
                    },
                    onFailure = {
                        if (it is CancellationException) throw it
                        errorResponse("persist_failed", deviceToolFailureReason(it))
                    }
                )
            }
        }
    }
).withWorkflowErrors()

fun workflowDeleteTool(repository: WorkflowRepository, callerAssistantId: String?): Tool = Tool(
    name = "workflow_delete",
    description = "Удалить сценарий текущего ассистента вместе с историей запусков и отменить его обработчики событий. Передайте id из workflow_list.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Строковый id удаляемого сценария из workflow_list.")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val id = workflowString(json.jsonObject, "id")
            ?: return@Tool errorResponse("missing_id", "Укажите id сценария из workflow_list.")
        val existing = repository.getById(id) ?: return@Tool errorResponse("not_found", "Сценарий не найден.")
        if (callerAssistantId == null || existing.definition.authoringAssistantId != callerAssistantId)
            return@Tool errorResponse("owner_mismatch", "Удалять сценарий может только ассистент, создавший его.")
        val ok = repository.deleteCascading(id)
        if (ok) listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", id)
        }.toString()))
        else errorResponse("not_found", "Сценарий с id=$id не найден. Выберите существующий id из workflow_list.")
    }
).withWorkflowErrors()

fun workflowSetEnabledTool(repository: WorkflowRepository, callerAssistantId: String?): Tool = Tool(
    name = "workflow_set_enabled",
    description = "Включить или выключить сценарий текущего ассистента. Выключение сохраняет описание и историю, но отменяет обработчики событий. Укажите enabled как boolean без кавычек.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Строковый id сценария из workflow_list.")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true — включить, false — выключить; boolean без кавычек.")
                })
            },
            required = listOf("id", "enabled"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val obj = json.jsonObject
        val id = workflowString(obj, "id")
            ?: return@Tool errorResponse("missing_id", "Укажите id сценария из workflow_list.")
        if ("enabled" !in obj) return@Tool errorResponse("missing_enabled", "Укажите enabled: true или false без кавычек.")
        val enabled = workflowBoolean(obj, "enabled", false)
        if (repository.getById(id) == null) {
            return@Tool errorResponse("not_found", "Сценарий с id=$id не найден. Выберите существующий id из workflow_list.")
        }
        val existing = repository.getById(id) ?: return@Tool errorResponse("not_found", "Сценарий не найден.")
        if (callerAssistantId == null || existing.definition.authoringAssistantId != callerAssistantId)
            return@Tool errorResponse("owner_mismatch", "Переключать сценарий может только ассистент, создавший его.")
        try { repository.setEnabled(id, enabled) } catch (e: IllegalArgumentException) {
            return@Tool errorResponse("trigger_unavailable", deviceToolFailureReason(e))
        } catch (e: IllegalStateException) {
            return@Tool errorResponse("trigger_unavailable", deviceToolFailureReason(e))
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", true); put("id", id); put("enabled", enabled)
        }.toString()))
    }
).withWorkflowErrors()

fun workflowRunTool(
    engine: WorkflowEngine,
    repository: WorkflowRepository,
    callerAssistantId: String?,
): Tool = Tool(
    name = "workflow_run",
    description = "Запустить включённый сценарий текущего ассистента вручную и дождаться результата. Условия, cooldown, суточный лимит, разрешения и HARDLINE сохраняют силу. output_summary ограничен 2000 символами; при truncated подробности запусков доступны в workflow_get.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Строковый id запускаемого сценария из workflow_list.")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val id = workflowString(json.jsonObject, "id")
            ?: return@Tool errorResponse("missing_id", "Укажите id сценария из workflow_list.")
        val existing = repository.getById(id)
            ?: return@Tool errorResponse("not_found", "Сценарий с id=$id не найден. Выберите существующий id из workflow_list.")
        if (callerAssistantId == null || existing.definition.authoringAssistantId != callerAssistantId)
            return@Tool errorResponse("owner_mismatch", "Запускать сценарий может только ассистент, создавший его.")
        val outcome = engine.fire(id)
        val summaryTruncated = outcome.summaryTruncated == true || outcome.summary.length > 2000
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", outcome.status.name == "SUCCESS")
            put("status", outcome.status.name)
            put("error", JsonPrimitive(outcome.error))
            val warning = if (outcome.status.name != "SUCCESS") workflowRunMessage(outcome.status.name, outcome.error) else null
            val clipping = if (summaryTruncated) "Сводка сокращена: до 200 символов на действие и 2000 суммарно. Состояние и историю проверьте через workflow_get с этим id; полный вывод действий может не сохраняться. Не повторяйте действия автоматически ради полного вывода." else null
            listOfNotNull(warning, clipping).takeIf { it.isNotEmpty() }?.let { put("message", it.joinToString(" ")) }
            put("output_summary", workflowSummaryPrefix(outcome.summary, 2000))
            put("truncated", summaryTruncated)
        }.toString()))
    }
).withWorkflowErrors()

private fun triggerTypeKey(def: WorkflowDefinition): String {
    val flat = WorkflowJson.encode(def)
    return runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(flat).jsonObject["trigger"]
            ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: "unknown"
}

private fun errorResponse(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
        put("message", workflowErrorMessage(code))
    }.toString()))

/** Only WorkflowJson's own diagnostics enter here; user data and execution errors stay untouched. */
private fun workflowParseErrorDetail(code: String, detail: String): String {
    if (code == "unknown_tool") {
        Regex("""action ([0-9]+) tool '(.*)' is not registered for this assistant""").matchEntire(detail)?.let {
            return "Действие ${it.groupValues[1]}: инструмент «${it.groupValues[2]}» не зарегистрирован для текущего ассистента. Выберите доступный инструмент."
        }
    }
    val action = Regex("""^action ([0-9]+)(?:\s|:)""").find(detail)?.groupValues?.get(1)
    val condition = Regex("""^condition(?:\[([0-9]+)\]:| ([0-9]+) )""").find(detail)
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    val location = when {
        action != null -> "Действие $action: "
        condition != null -> "Условие $condition: "
        else -> ""
    }
    // Decoder exceptions may contain external input. The stable code is enough to provide
    // an authored diagnostic without exposing or trying to translate that raw exception text.
    return location + workflowErrorMessage(code)
}

private fun workflowErrorMessage(code: String): String = when (code) {
    "invalid_arguments" -> "Параметры сценария неверны. Проверьте типы полей в detail и схеме инструмента; числа и boolean передавайте без кавычек."
    "invalid_definition", "missing_definition", "not_an_object", "invalid_json" -> "Описание сценария отсутствует или неверно. Укажите definition как JSON-объект с name, trigger и actions."
    "missing_id" -> "Не указан ID сценария. Вызовите workflow_list и укажите id нужного сценария."
    "missing_name", "invalid_name" -> "Имя сценария отсутствует или неверно. Укажите name длиной 1–${WorkflowConstants.MAX_NAME_LENGTH} символов."
    "missing_trigger", "missing_trigger_type", "unknown_trigger_type", "invalid_trigger" -> "Условие запуска сценария неверно. Проверьте trigger.type и параметры запуска по схеме workflow_create."
    "missing_condition_type", "unknown_condition_type", "bad_condition_shape", "invalid_condition" -> "Условие сценария неверно. Проверьте conditions и параметры каждого условия."
    "missing_actions", "empty_actions", "too_many_actions", "bad_action_shape", "missing_tool" -> "Действия сценария отсутствуют или неверны. Укажите от 1 до ${WorkflowConstants.MAX_ACTIONS} действий в actions с tool и args."
    "unknown_tool", "workflow_chaining_disabled" -> "Инструмент действия недоступен. Выберите зарегистрированный инструмент; workflow_run внутри сценария запрещён."
    "invalid_timeout" -> "Время действия неверно. Укажите timeout_seconds от ${WorkflowConstants.MIN_ACTION_TIMEOUT_S} до ${WorkflowConstants.MAX_ACTION_TIMEOUT_S} секунд."
    "invalid_cooldown" -> "Пауза между запусками неверна. Укажите cooldown_seconds от 0 до ${WorkflowConstants.MAX_COOLDOWN_S} секунд."
    "invalid_daily_cap" -> "Суточный лимит неверен. Укажите max_runs_per_day от ${WorkflowConstants.MAX_RUNS_PER_DAY_FLOOR} до ${WorkflowConstants.MAX_RUNS_PER_DAY_CEIL}."
    "missing_enabled", "invalid_enabled" -> "Не указано допустимое состояние сценария. Укажите enabled: true или false."
    "not_found" -> "Сценарий не найден. Вызовите workflow_list и выберите существующий id."
    "already_exists" -> "Сценарий уже существует. Используйте workflow_update для изменения."
    "missing_owner" -> "Не удалось определить ассистента текущего чата. Откройте чат и повторите создание сценария."
    "owner_mismatch" -> "Текущий ассистент не может управлять сценарием. Откройте чат ассистента, создавшего его."
    "persist_failed" -> "Не удалось сохранить сценарий. Проверьте доступность хранилища и повторите вызов."
    "trigger_unavailable" -> "Запуск сценария недоступен на устройстве. Проверьте trigger и conditions; неподдерживаемый сценарий сохраняйте выключенным."
    else -> "Не удалось выполнить операцию со сценарием. Проверьте его параметры и повторите вызов."
}

internal fun workflowRunMessage(status: String, error: String?): String {
    if (error == "process_interrupted") {
        return "Прервано: приложение было закрыто. Часть действий могла выполниться. Проверьте результат и запустите сценарий вручную."
    }
    if (error?.contains("execution_interrupted") == true) {
        return "Выполнение сценария остановлено. Часть действий могла выполниться. Проверьте состояние перед повторным запуском; автоматически не повторяйте действия."
    }
    if (error?.contains("root_output_incomplete") == true) {
        return "Вывод Root-команды может быть неполным. Проверьте состояние перед новым запуском; автоматически не повторяйте действия."
    }
    if (error != null && listOf("root_cleanup_unconfirmed", "root_execution_indeterminate", "tool_result_indeterminate").any { it in error }) {
        return "Часть действий могла выполниться; результат неизвестен. Проверьте состояние перед новым запуском; автоматически не повторяйте действия."
    }
    return when (status) {
        "RUNNING" -> "Сценарий выполняется. Дождитесь завершения."
        "SKIPPED_CONDITIONS" -> "Условия сценария не выполнены. Проверьте conditions и состояние устройства."
        "SKIPPED_COOLDOWN" -> "С момента прошлого запуска прошло мало времени. Дождитесь окончания cooldown_seconds."
        "SKIPPED_DAILY_CAP" -> "Достигнут суточный лимит сценария. Дождитесь следующего дня или проверьте max_runs_per_day."
        "SKIPPED_DISABLED" -> "Сценарий выключен. Включите его через workflow_set_enabled перед запуском."
        else -> "Сценарий завершился с ошибкой. Проверьте историю, разрешения и параметры действий перед повторным запуском."
    }
}

private fun Tool.withWorkflowErrors(): Tool = copy(execute = { input ->
    try { execute(input) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: IllegalArgumentException) { errorResponse("invalid_arguments", deviceToolFailureReason(error)) }
})

private fun workflowString(obj: JsonObject, key: String): String? {
    if (key !in obj) return null
    return (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Укажите $key как строку.")
}
private fun workflowBoolean(obj: JsonObject, key: String, default: Boolean): Boolean {
    if (key !in obj) return default
    return (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("Укажите $key: true или false без кавычек.")
}

/** Validate authoring types before the legacy parser can coerce/default them. Stored rows stay untouched. */
private fun workflowSchemaTypeError(value: JsonElement, schema: JsonObject, path: String): String? {
    val types = when (val type = schema["type"]) {
        is JsonArray -> type.map { it.jsonPrimitive.content }
        is JsonPrimitive -> listOf(type.content)
        else -> emptyList()
    }
    val matches = types.isEmpty() || types.any { type -> when (type) {
        "null" -> value == JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        else -> true
    } }
    if (!matches) return "Неверный тип $path: требуется ${types.joinToString(" или ")}. Числа и boolean передавайте без кавычек."
    if (value is JsonObject) {
        val properties = schema["properties"] as? JsonObject ?: return null
        for ((key, child) in value) {
            val childSchema = properties[key] as? JsonObject ?: continue
            workflowSchemaTypeError(child, childSchema, "$path.$key")?.let { return it }
        }
    }
    if (value is JsonArray) {
        val items = schema["items"] as? JsonObject ?: return null
        for ((index, child) in value.withIndex()) workflowSchemaTypeError(child, items, "$path[$index]")?.let { return it }
    }
    return null
}

private fun workflowField(type: String, description: String, nullable: Boolean = false,
                          default: JsonElement? = null, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
    if (nullable) put("type", buildJsonArray { add(type); add("null") }) else put("type", type)
    put("description", description)
    default?.let { put("default", it) }; minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }
}
private fun workflowArray(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array"); put("description", description); put("items", items)
}
private fun workflowDefinitionSchema(requireId: Boolean): JsonObject = buildJsonObject {
    put("type", "object"); put("description", "Полное описание сценария. При обновлении id обязателен; owner и исходный чат назначает приложение.")
    put("properties", buildJsonObject {
        put("id", workflowField("string", "id существующего сценария при обновлении; при создании автоматически назначается, если отсутствует."))
        put("name", workflowField("string", "Непустое название сценария, до ${WorkflowConstants.MAX_NAME_LENGTH} символов."))
        put("description", workflowField("string", "Описание; сохраняются первые ${WorkflowConstants.MAX_DESCRIPTION_LENGTH} символов.", nullable = true))
        put("enabled", workflowField("boolean", "Включён ли сценарий; неподдерживаемые на устройстве сценарии сохраняйте с false.", default = JsonPrimitive(true)))
        put("trigger", workflowEventSchema(condition = false))
        put("conditions", workflowArray("Дополнительные условия, объединённые И; по умолчанию пустой массив.", workflowEventSchema(condition = true)))
        put("actions", buildJsonObject {
            put("type", "array"); put("description", "Действия выполняются по порядку; от 1 до ${WorkflowConstants.MAX_ACTIONS}.")
            put("minItems", 1); put("maxItems", WorkflowConstants.MAX_ACTIONS)
            put("items", buildJsonObject {
                put("type", "object"); put("properties", buildJsonObject {
                    put("tool", workflowField("string", "Имя зарегистрированного инструмента; workflow_run внутри сценария запрещён."))
                    put("args", buildJsonObject { put("type", "object"); put("additionalProperties", true); put("description", "Аргументы выбранного инструмента по его схеме; по умолчанию пустой объект.") })
                    put("timeout_seconds", workflowField("integer", "Время одного действия в секундах; целое число без кавычек. Фоновый исполнитель дополнительно ограничен 480 секундами.", default = JsonPrimitive(60), minimum = WorkflowConstants.MIN_ACTION_TIMEOUT_S, maximum = WorkflowConstants.MAX_ACTION_TIMEOUT_S))
                }); put("required", buildJsonArray { add("tool") })
            })
        })
        put("cooldown_seconds", workflowField("integer", "Минимальная пауза между фактическими запусками в секундах.", default = JsonPrimitive(0), minimum = 0, maximum = WorkflowConstants.MAX_COOLDOWN_S))
        put("max_runs_per_day", workflowField("integer", "Число фактических запусков (включая неудачные) за местный день; null или отсутствие — без лимита.", nullable = true, minimum = WorkflowConstants.MAX_RUNS_PER_DAY_FLOOR, maximum = WorkflowConstants.MAX_RUNS_PER_DAY_CEIL))
    })
    put("required", buildJsonArray { add("name"); add("trigger"); add("actions"); if (requireId) add("id") })
}

/** Canonical nested params and existing flat arguments advertise the same typed properties. */
private fun workflowEventSchema(condition: Boolean): JsonObject {
    val names = if (condition) listOf("time_between", "time_after_sunset", "time_before_sunrise", "day_of_week_in", "wifi_ssid_is", "wifi_ssid_in", "battery_above", "battery_below", "is_charging", "is_not_charging", "foreground_app_is", "foreground_app_in", "screen_is_on", "screen_is_off")
        else listOf("time_cron", "wifi_connected", "wifi_disconnected", "bluetooth_device_connected", "bluetooth_device_disconnected", "headphones_plugged", "headphones_unplugged", "power_connected", "power_disconnected", "battery_below", "battery_above", "geofence_enter", "geofence_exit", "app_launched", "app_closed", "notification_received", "boot_completed", "screen_on", "screen_off", "manual")
    val fields = buildJsonObject {
        if (condition) {
            put("invert", workflowField("boolean", "Инвертировать результат этого условия; по умолчанию false.", default = JsonPrimitive(false)))
            put("start", workflowField("string", "Начало интервала time_between в HH:mm; обязательно для этого типа."))
            put("end", workflowField("string", "Конец интервала time_between в HH:mm; переход через полночь разрешён."))
            put("offset_minutes", workflowField("integer", "Смещение заката/рассвета в минутах, по умолчанию 0. Эти условия в Root недоступны.", default = JsonPrimitive(0), minimum = -720, maximum = 720))
            put("days", workflowArray("Дни для day_of_week_in: целые числа ISO 1–7, понедельник — 1.", workflowField("integer", "День недели ISO.", minimum = 1, maximum = 7)))
            put("ssid", workflowField("string", "Имя сети для wifi_ssid_is; обязательно для этого типа."))
            put("ssids", workflowArray("Имена сетей для wifi_ssid_in.", workflowField("string", "Имя сети Wi-Fi.")))
            put("percent", workflowField("integer", "Уровень заряда для battery_above/battery_below; обязателен для этих типов.", minimum = 1, maximum = 100))
            put("package_name", workflowField("string", "Пакет приложения для foreground_app_is; условие в Root недоступно."))
            put("package_names", workflowArray("Пакеты для foreground_app_in; условие в Root недоступно.", workflowField("string", "Имя пакета приложения.")))
        } else {
            put("cron", workflowField("string", "Расписание time_cron из пяти полей или @every; не используйте вместе с time_of_day.", nullable = true))
            put("time_of_day", workflowField("string", "Ежедневное время HH:mm для time_cron вместо cron.", nullable = true))
            put("days_of_week", workflowArray("Дни ISO 1–7 для time_of_day; пустой массив или отсутствие — ежедневно.", workflowField("integer", "День недели ISO.", minimum = 1, maximum = 7)))
            put("timezone", workflowField("string", "Часовой пояс IANA для time_cron; null или отсутствие — пояс устройства.", nullable = true))
            put("ssid", workflowField("string", "Имя Wi-Fi для wifi_connected/wifi_disconnected; null или отсутствие — любая сеть.", nullable = true))
            put("device_address", workflowField("string", "Адрес Bluetooth-устройства; null или отсутствие — любое устройство.", nullable = true))
            put("threshold_percent", workflowField("integer", "Порог для battery_below/battery_above; событие возникает при пересечении порога.", minimum = 1, maximum = 100))
            put("lat", workflowField("number", "Широта геозоны от -90 до 90; геозоны в Root недоступны."))
            put("lng", workflowField("number", "Долгота геозоны от -180 до 180; геозоны в Root недоступны."))
            put("radius_m", workflowField("integer", "Радиус геозоны в метрах.", minimum = WorkflowConstants.MIN_GEOFENCE_RADIUS_M, maximum = WorkflowConstants.MAX_GEOFENCE_RADIUS_M))
            put("label", workflowField("string", "Необязательное название геозоны.", nullable = true))
            put("package_name", workflowField("string", "Пакет для app_launched/app_closed либо фильтр notification_received; эти события в Root недоступны.", nullable = true))
            for (key in listOf("title_contains", "text_contains", "title_matches", "text_matches")) put(key, workflowField("string",
                "Фильтр notification_received: contains — подстрока без учёта регистра, matches — Java regex find; фильтры объединены И, нужен хотя бы один фильтр или package_name.", nullable = true))
        }
    }
    return buildJsonObject {
        put("type", "object"); put("description", if (condition) "Условие: type и параметры в params; также поддерживается прежняя плоская форма." else "Событие запуска: type и параметры в params; также поддерживается прежняя плоская форма. manual запускается вручную, прочие типы повторяются.")
        put("properties", buildJsonObject {
            put("type", buildJsonObject { put("type", "string"); put("description", "Точный тип из списка допустимых значений."); put("enum", JsonArray(names.map(::JsonPrimitive))) })
            put("params", buildJsonObject { put("type", "object"); put("description", "Параметры выбранного type; для типа без параметров можно не передавать."); put("properties", fields) })
            fields.forEach { (key, value) -> put(key, value) }
        }); put("required", buildJsonArray { add("type") })
    }
}
