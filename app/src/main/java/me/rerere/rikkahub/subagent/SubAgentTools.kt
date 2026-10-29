package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun response(value: JsonElement): List<UIMessagePart> = listOf(UIMessagePart.Text(value.toString()))
private fun subagentErrorMessage(code: String): String = when (code) {
    "invalid_arguments" -> "Параметры субагента неверны. Проверьте task, id и типы остальных аргументов."
    "invalid_task" -> "Задача субагента пуста или слишком длинная. Укажите task длиной 1–100000 символов."
    "invalid_timeout", "invalid_limits" -> "Лимиты субагента неверны. Укажите timeout_seconds от 1 до 480 и max_trips от 1 до 32."
    "invalid_max_trips" -> "Лимит шагов неверен. Укажите max_trips от 1 до 32."
    "invalid_model", "unknown_or_ambiguous_model", "model_unavailable" -> "Модель субагента не найдена или неоднозначна. Укажите точный model_id из настроек или уберите его для наследования модели чата."
    "invalid_label" -> "Метка субагента слишком длинная. Сократите label до 60 символов."
    "invalid_system_prompt" -> "Системный промпт субагента слишком длинный. Сократите system_prompt до 32000 символов."
    "invalid_tools", "tool_not_allowed", "tool_unavailable", "interactive_tool", "mandatory_confirmation" -> "Набор инструментов субагента недоступен. Выберите разрешённые инструменты без обязательного диалога; не более 256 имён."
    "nested_dispatch_disabled" -> "Субагент не может запускать других субагентов. Вызовите subagent_dispatch из основного диалога."
    "invalid_owner", "unknown_owner" -> "Текущий ассистент или диалог недоступен. Откройте основной чат и повторите вызов."
    "feature_disabled", "tool_feature_disabled" -> "Функция субагента или его инструмента выключена. Включите её в настройках ассистента и повторите вызов."
    "concurrency_limit" -> "Достигнут лимит параллельных субагентов. Дождитесь завершения или остановите свой запуск через subagent_cancel."
    "unknown_run" -> "Субагент текущего диалога не найден. Вызовите subagent_list и укажите доступный id."
    "android_permission_required", "approval_required", "root_interaction_required", "root_approval_required", "root_not_granted" -> "Для действия субагента нужен доступ на устройстве. Откройте приложение и проверьте разрешения инструмента."
    "token_budget_exceeded", "hard_budget_exhausted", "step_limit" -> "Достигнут лимит выполнения субагента. Проверьте бюджет токенов и max_trips или сократите задачу."
    "result_storage_unavailable" -> "Задача завершена, но результат не удалось сохранить. Скопируйте его из чата и проверьте свободное место."
    "process_interrupted" -> "Прервано: приложение было закрыто. Часть действий могла выполниться. Проверьте результат и запустите субагента вручную."
    "cancelled", "execution_cancelled" -> "Субагент остановлен. Проверьте выполненные действия перед повторным запуском."
    "root_output_incomplete" -> "Вывод Root-команды может быть неполным. Проверьте результат на устройстве перед повторным запуском."
    "execution_interrupted", "deadline_exceeded", "command_timeout", "root_cleanup_unconfirmed", "root_execution_indeterminate", "tool_result_indeterminate" -> "Выполнение субагента прервано; результат действий может быть неполным. Проверьте его на устройстве перед повторным запуском."
    "root_command_blocked", "web_content_blocked" -> "Действие субагента заблокировано проверкой безопасности. Проверьте команду и источники данных."
    "workspace_unavailable" -> "Рабочая среда субагента недоступна. Откройте текущий workspace и проверьте его готовность."
    else -> "Не удалось выполнить задачу субагента. Проверьте параметры и доступность инструментов, затем повторите запуск."
}
private fun error(code: String) = response(buildJsonObject { put("error", code); put("message", subagentErrorMessage(code)) })
internal fun encodeRun(run: SubAgentRun): JsonObject = buildJsonObject {
    put("id", run.id); put("label", run.label); put("status", run.status.name)
    put("model_id", run.modelId); put("started_at_ms", run.startedAtMs)
    run.finishedAtMs?.let { put("finished_at_ms", it) }
    if (run.noResult) put("result_suppressed", true) else {
        run.result?.let { put("result", it) }
        run.resultTruncated?.let { put("result_truncated", it) }
        run.resultTotalChars?.let { put("result_total_chars", it) }
        if (run.resultTruncated == true) {
            val hint = "Результат усечён до 65536 единиц UTF-16 без разрыва пары символов. subagent_get не может вернуть пропущенную часть. Не запускайте задачу с побочными действиями заново вслепую; при необходимости создайте узкую задачу чтения или задачу с сохранением полного вывода в файл."
            put("truncation_message", hint)
            if (run.errorCode == null) put("message", hint)
        }
    }
    run.errorCode?.let { put("error", it); put("message", subagentErrorMessage(it)) }
    put("tokens_in", run.tokensIn); put("tokens_out", run.tokensOut); put("trip_count", run.tripCount)
}
private fun property(type: String, description: String? = null, extra: JsonObject? = null) = buildJsonObject {
    put("type", type); description?.let { put("description", it) }
    extra?.forEach { (name, value) -> put(name, value) }
}
private fun JsonObject.string(name: String): String? {
    val value = get(name) ?: return null
    require(value is JsonPrimitive && value.isString)
    return value.content
}
private fun JsonObject.boolean(name: String, default: Boolean): Boolean {
    val value = get(name) ?: return default
    require(value is JsonPrimitive && !value.isString)
    return requireNotNull(value.booleanOrNull)
}
private fun JsonObject.integer(name: String, default: Int): Int {
    val value = get(name) ?: return default
    require(value is JsonPrimitive && !value.isString)
    return requireNotNull(value.intOrNull)
}
fun subagentDispatchTool(engine: SubAgentEngine, caller: SubAgentCaller): Tool = Tool(
    name = "subagent_dispatch",
    description = "Запустить субагента с отдельным контекстом для самостоятельной задачи. Укажите model_id (UUID, точный идентификатор модели или однозначное название), либо наследуется модель текущего диалога. Субагент не видит историю диалога; укажите весь контекст в task. run_in_background возвращает ID для опроса. Фоновые субагенты работают только пока жив процесс приложения. Интерактивные инструменты недоступны; разрешения проверяются заново. Лимит 1–480 секунд и 1–32 шага.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("task", property("string", "Задача субагента со всем необходимым контекстом: 1–100000 символов.", buildJsonObject { put("minLength", 1); put("maxLength", 100000) })); put("label", property("string", "Необязательная краткая метка запуска: до 60 символов.", buildJsonObject { put("maxLength", 60) }))
        put("model_id", property("string", "Необязательный непустой UUID, точный идентификатор или однозначное название модели; без него наследуется модель текущего диалога.", buildJsonObject { put("minLength", 1) })); put("system_prompt", property("string", "Необязательный системный промпт субагента: до 32000 символов.", buildJsonObject { put("maxLength", 32000) }))
        put("tools", buildJsonObject { put("type", "array"); put("description", "Необязательный список доступных субагенту инструментов: не более 256 точных имён из разрешённого набора текущего запуска."); put("maxItems", 256); put("items", property("string", "Точное непустое имя разрешённого инструмента текущего запуска.", buildJsonObject { put("minLength", 1) })) })
        put("run_in_background", property("boolean", "Запустить в фоне и сразу вернуть ID для проверки состояния; по умолчанию false.", buildJsonObject { put("default", false) })); put("no_result", property("boolean", "Не возвращать итоговый текст субагента; по умолчанию false.", buildJsonObject { put("default", false) }))
        put("timeout_seconds", property("integer", "Лимит выполнения в секундах: 1–480, по умолчанию 300.", buildJsonObject { put("minimum", 1); put("maximum", 480); put("default", 300) })); put("max_trips", property("integer", "Лимит шагов генерации: 1–32, по умолчанию 12.", buildJsonObject { put("minimum", 1); put("maximum", 32); put("default", 12) }))
    }, required = listOf("task")) },
    needsApproval = { true },
    execute = { args ->
        val request = runCatching {
            val obj = args.jsonObject
            SubAgentRequest(
                task = requireNotNull(obj.string("task")), label = obj.string("label"),
                modelId = obj.string("model_id"), systemPrompt = obj.string("system_prompt"),
                tools = obj["tools"]?.jsonArray?.map { value ->
                    require(value is JsonPrimitive && value.isString); value.content
                },
                runInBackground = obj.boolean("run_in_background", false), noResult = obj.boolean("no_result", false),
                timeoutSeconds = obj.integer("timeout_seconds", 300), maxTrips = obj.integer("max_trips", 12),
            )
        }.getOrNull()
        if (request == null) error("invalid_arguments") else when (val result = engine.dispatch(caller, request)) {
            is SubAgentEngine.DispatchResult.Ok -> response(encodeRun(result.run))
            is SubAgentEngine.DispatchResult.Reject -> error(result.error)
        }
    },
)
fun subagentListTool(registry: SubAgentRegistry, owner: SubAgentOwner): Tool = Tool(
    name = "subagent_list", description = "Список субагентов только текущего ассистента и диалога.",
    parameters = { InputSchema.Obj(buildJsonObject { put("active_only", property("boolean", "Показать только активные запуски; по умолчанию false.", buildJsonObject { put("default", false) })) }) },
    execute = { args -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val active = runCatching { args.jsonObject.boolean("active_only", false) }.getOrNull()
        if (active == null) error("invalid_arguments") else response(buildJsonObject {
            put("runs", JsonArray(registry.list(owner, active).map { encodeRun(it) }))
        })
    } },
)
fun subagentGetTool(registry: SubAgentRegistry, owner: SubAgentOwner): Tool = Tool(
    name = "subagent_get", description = "Статус и итог собственного субагента по ID.",
    parameters = { InputSchema.Obj(buildJsonObject { put("id", property("string", "Точный ID собственного запуска из subagent_dispatch или subagent_list.")) }, required = listOf("id")) },
    execute = { args -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = runCatching { args.jsonObject.string("id") }.getOrNull()
        val run = id?.let { registry.get(owner, it) }
        if (run == null) error("unknown_run") else response(encodeRun(run))
    } },
)
fun subagentCancelTool(registry: SubAgentRegistry, owner: SubAgentOwner): Tool = Tool(
    name = "subagent_cancel", description = "Остановить генерацию собственного субагента. Уже выполненные действия не отменяются.",
    parameters = { InputSchema.Obj(buildJsonObject { put("id", property("string", "Точный ID собственного запуска из subagent_dispatch или subagent_list.")) }, required = listOf("id")) },
    needsApproval = { true },
    execute = { args -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = runCatching { args.jsonObject.string("id") }.getOrNull()
        if (id == null || registry.get(owner, id) == null) error("unknown_run") else response(buildJsonObject {
            val cancelled = registry.cancel(owner, id)
            put("id", id); put("ok", cancelled)
            if (!cancelled) put("message", "Субагент уже завершён. Проверьте его результат через subagent_get.")
        })
    } },
)
