package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun response(value: JsonElement): List<UIMessagePart> = listOf(UIMessagePart.Text(value.toString()))
private fun error(code: String) = response(buildJsonObject { put("error", code) })
internal fun encodeRun(run: SubAgentRun): JsonObject = buildJsonObject {
    put("id", run.id); put("label", run.label); put("status", run.status.name)
    put("model_id", run.modelId); put("started_at_ms", run.startedAtMs)
    run.finishedAtMs?.let { put("finished_at_ms", it) }
    if (run.noResult) put("result_suppressed", true) else run.result?.let { put("result", it) }
    run.errorCode?.let { put("error", it) }
    put("tokens_in", run.tokensIn); put("tokens_out", run.tokensOut); put("trip_count", run.tripCount)
}
private fun property(type: String, description: String? = null) = buildJsonObject {
    put("type", type); description?.let { put("description", it) }
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
        put("task", property("string")); put("label", property("string"))
        put("model_id", property("string")); put("system_prompt", property("string"))
        put("tools", buildJsonObject { put("type", "array"); put("items", property("string")) })
        put("run_in_background", property("boolean")); put("no_result", property("boolean"))
        put("timeout_seconds", property("integer")); put("max_trips", property("integer"))
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
    parameters = { InputSchema.Obj(buildJsonObject { put("active_only", property("boolean")) }) },
    execute = { args ->
        val active = runCatching { args.jsonObject.boolean("active_only", false) }.getOrNull()
        if (active == null) error("invalid_arguments") else response(buildJsonObject {
            put("runs", JsonArray(registry.list(owner, active).map { encodeRun(it) }))
        })
    },
)
fun subagentGetTool(registry: SubAgentRegistry, owner: SubAgentOwner): Tool = Tool(
    name = "subagent_get", description = "Статус и итог собственного субагента по ID.",
    parameters = { InputSchema.Obj(buildJsonObject { put("id", property("string")) }, required = listOf("id")) },
    execute = { args ->
        val id = runCatching { args.jsonObject.string("id") }.getOrNull()
        val run = id?.let { registry.get(owner, it) }
        if (run == null) error("unknown_run") else response(encodeRun(run))
    },
)
fun subagentCancelTool(registry: SubAgentRegistry, owner: SubAgentOwner): Tool = Tool(
    name = "subagent_cancel", description = "Остановить генерацию собственного субагента. Уже выполненные действия не отменяются.",
    parameters = { InputSchema.Obj(buildJsonObject { put("id", property("string")) }, required = listOf("id")) },
    needsApproval = { true },
    execute = { args ->
        val id = runCatching { args.jsonObject.string("id") }.getOrNull()
        if (id == null || registry.get(owner, id) == null) error("unknown_run") else response(buildJsonObject {
            put("id", id); put("ok", registry.cancel(owner, id))
        })
    },
)
