package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.service.CronExpressionParser
import me.rerere.rikkahub.service.CronJobScheduler
import java.time.ZoneId
import kotlin.uuid.Uuid

val CRON_TOOL_NAMES = setOf("schedule_job", "list_jobs", "get_job_history", "delete_job", "pause_job", "resume_job", "trigger_job_now")

object ScheduleJobValidator {
    private val keys = setOf("name", "mode", "prompt", "actions", "schedule_type", "at_unix_ms", "cron_expression", "timezone", "start_at_unix_ms", "end_at_unix_ms", "max_runs", "catchup")
    fun validate(input: JsonObject, knownToolNames: Set<String>, now: Long): String? {
        fun text(key: String) = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun number(key: String) = (input[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (input.keys.any { it !in keys }) return "unsupported_field"
        if (text("name").isNullOrBlank() || text("name")!!.length > 80) return "invalid_name"
        val mode = text("mode")
        when (mode) {
            "llm" -> if (text("prompt").isNullOrBlank() || text("prompt")!!.length > 4000 || "actions" in input) return "invalid_payload"
            "direct" -> {
                if ("prompt" in input) return "invalid_payload"
                val actions = input["actions"] as? JsonArray ?: return "invalid_actions"
                if (actions.size !in 1..12) return "invalid_actions"
                for (action in actions) {
                    val obj = action as? JsonObject ?: return "invalid_actions"
                    if (obj.keys != setOf("tool", "args")) return "invalid_actions"
                    val name = (obj["tool"] as? JsonPrimitive)?.contentOrNull ?: return "invalid_actions"
                    if (name !in knownToolNames || name in CRON_TOOL_NAMES || name in setOf("subagent_dispatch", "subagent_cancel")) return "tool_unavailable"
                    if (obj["args"] !is JsonObject) return "invalid_actions"
                }
            }
            else -> return "invalid_mode"
        }
        val maxHorizon = if (now <= Long.MAX_VALUE - 315360000000L) now + 315360000000L else Long.MAX_VALUE
        val start = number("start_at_unix_ms")
        val end = number("end_at_unix_ms")
        for (key in listOf("start_at_unix_ms", "end_at_unix_ms", "max_runs", "at_unix_ms")) if (key in input && number(key) == null) return "invalid_number"
        if (start != null && start !in 0..maxHorizon) return "invalid_bounds"
        if (end != null && (end <= now || end > maxHorizon || (start != null && end <= start))) return "invalid_bounds"
        when (text("schedule_type")) {
            "once" -> {
                val at = number("at_unix_ms") ?: return "invalid_time"
                if (at <= now || at > maxHorizon || "cron_expression" in input || (start != null && at < start) || (end != null && at > end)) return "invalid_time"
            }
            "cron" -> if ("at_unix_ms" in input || text("cron_expression")?.let { CronExpressionParser.parse(it).isSuccess } != true) return "invalid_cron"
            else -> return "invalid_schedule"
        }
        if ("timezone" in input && (text("timezone").isNullOrBlank() || runCatching { ZoneId.of(text("timezone")) }.isFailure)) return "invalid_timezone"
        val max = number("max_runs")
        if (max != null && max !in 1..100000) return "invalid_run_limit"
        if ("catchup" in input && text("catchup") !in setOf("skip", "fire_once", "fire_all")) return "invalid_catchup"
        if (input.toString().toByteArray(Charsets.UTF_8).size > 60000) return "payload_too_large"
        return null
    }
}

/** Every operation uses the caller's stored owner, including personal reads. */
fun createCronJobTools(
    repo: ScheduledJobRepository,
    runRepo: ScheduledJobRunRepository,
    scheduler: CronJobScheduler,
    callerConversation: Conversation,
    knownToolNames: () -> Set<String>,
): List<Tool> {
    val owner = callerConversation.assistantId.toString()
    fun result(payload: JsonObject) = listOf(UIMessagePart.Text(payload.toString()))
    fun error(code: String) = result(buildJsonObject { put("status", "error"); put("code", code) })
    fun schema(vararg names: String, required: List<String> = emptyList()) = InputSchema.Obj(
        properties = buildJsonObject { names.forEach { name -> put(name, buildJsonObject { put("type", if (name in setOf("at_unix_ms", "start_at_unix_ms", "end_at_unix_ms", "max_runs", "limit")) "integer" else if (name == "actions") "array" else "string"); if (name == "actions") put("items", buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("tool", buildJsonObject { put("type", "string") }); put("args", buildJsonObject { put("type", "object") }) }); put("required", buildJsonArray { add("tool"); add("args") }) }) }) } }, required = required)
    fun summary(job: ScheduledJobEntity) = buildJsonObject {
        put("id", job.id)
        val payload = runCatching { repo.payload(job) }.getOrNull()
        put("name", payload?.name ?: "Задание недоступно после восстановления")
        put("mode", job.mode); put("schedule_type", job.scheduleType); put("enabled", job.enabled)
        job.cronExpression?.let { put("cron_expression", it) }; job.atUnixMs?.let { put("at_unix_ms", it) }
        put("timezone", job.timezone); job.nextRunAtMs?.let { put("next_run_at_ms", it) }
        job.maxRuns?.let { put("max_runs", it) }; put("total_runs", job.totalRuns); put("total_succeeded", job.totalSucceeded)
        put("total_failed", job.totalFailed); put("total_blocked", job.totalBlocked); put("total_cancelled", job.totalCancelled); put("total_indeterminate", job.totalIndeterminate)
    }
    val schedule = Tool(
        name = "schedule_job", needsApproval = { true },
        description = "Создать задание текущего помощника: mode llm с prompt или direct с actions [{tool,args}]. schedule_type once с at_unix_ms либо cron с cron_expression (пять полей, @daily, @every 30m). timezone — IANA. catchup skip/fire_once/fire_all (не более 20). max_runs ограничивает число попыток независимо от истории. Запуск WorkManager приблизительный; фоновые действия требуют действующих разрешений и могут быть заблокированы. Не передавайте пароли или ключи: используйте ссылки на AndroidKeyStore.",
        parameters = { schema("name", "mode", "prompt", "actions", "schedule_type", "at_unix_ms", "cron_expression", "timezone", "start_at_unix_ms", "end_at_unix_ms", "max_runs", "catchup", required = listOf("name", "mode", "schedule_type")) },
        execute = { input ->
            val obj = input as? JsonObject ?: return@Tool error("invalid_arguments")
            val allowed = knownToolNames() - CRON_TOOL_NAMES - setOf("subagent_dispatch", "subagent_cancel")
            val now = System.currentTimeMillis()
            ScheduleJobValidator.validate(obj, allowed, now)?.let { return@Tool error(it) }
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            fun number(key: String) = (obj[key] as? JsonPrimitive)?.longOrNull
            val job = ScheduledJobEntity(
                id = Uuid.random().toString(), ownerAssistantId = owner, creatorConversationId = callerConversation.id.toString(), payloadCiphertext = "",
                mode = text("mode")!!, scheduleType = text("schedule_type")!!, atUnixMs = number("at_unix_ms"), cronExpression = text("cron_expression"),
                timezone = text("timezone") ?: ZoneId.systemDefault().id, startAtMs = number("start_at_unix_ms"), endAtMs = number("end_at_unix_ms"), maxRuns = number("max_runs"), catchup = text("catchup") ?: "fire_once", createdAtMs = now,
            )
            val next = CronJobScheduler.nextScheduled(job, now) ?: return@Tool error("no_future_occurrence")
            val actions = (obj["actions"] as? JsonArray)?.map { el -> val action = el.jsonObject; CronAction(action["tool"]!!.jsonPrimitive.content, action["args"]!!.jsonObject) } ?: emptyList()
            if (callerConversation.config == null) return@Tool error("missing_conversation_snapshot")
            val payload = ScheduledJobPayload(text("name")!!, text("prompt"), actions, callerConversation.config, callerConversation.workspaceCwd, allowed)
            try {
                val created = repo.create(job.copy(nextRunAtMs = next), payload)
                val enqueued = scheduler.schedule(created)
                result(buildJsonObject { put("status", if (enqueued) "scheduled" else "blocked"); if (!enqueued) put("code", "enqueue_failed"); put("job", summary(repo.getById(created.id) ?: created)) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error("schedule_failed") }
        },
    )
    val list = Tool(name = "list_jobs", needsApproval = { true }, description = "Список заданий текущего помощника без промптов, аргументов и результатов действий.", parameters = { schema() }, execute = {
        result(buildJsonObject { put("jobs", buildJsonArray { repo.listOwned(owner).forEach { add(summary(it)) } }) })
    })
    val history = Tool(name = "get_job_history", needsApproval = { true }, description = "История задания текущего помощника: queued/running/succeeded/failed/blocked/cancelled/indeterminate, время и безопасные коды. Не содержит ответов или секретов.", parameters = { schema("id", "limit", required = listOf("id")) }, execute = { input ->
        val obj = input as? JsonObject ?: return@Tool error("invalid_arguments")
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@Tool error("missing_id")
        repo.getOwned(id, owner) ?: return@Tool error("not_found")
        val limit = (obj["limit"] as? JsonPrimitive)?.intOrNull ?: 20
        if (limit !in 1..100) return@Tool error("invalid_limit")
        result(buildJsonObject { put("runs", buildJsonArray { runRepo.recent(id, limit).forEach { run -> add(buildJsonObject {
            put("occurrence_id", run.occurrenceId); put("status", run.status); put("scheduled_at_ms", run.scheduledAtMs)
            run.startedAtMs?.let { put("started_at_ms", it) }; run.finishedAtMs?.let { put("finished_at_ms", it) }; run.outcomeCode?.let { put("code", it) }
        }) } }) })
    })
    fun mutation(name: String, description: String, action: suspend (String) -> List<UIMessagePart>): Tool = Tool(
        name = name, needsApproval = { true }, description = description, parameters = { schema("id", required = listOf("id")) }, execute = { input ->
            val id = ((input as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@Tool error("missing_id")
            repo.getOwned(id, owner) ?: return@Tool error("not_found")
            try { action(id) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error("mutation_failed") }
        })
    val delete = mutation("delete_job", "Удалить задание текущего помощника и его историю; отменить обычные, ручные и пропущенные запуски.") { id ->
        if (!repo.deleteOwned(id, owner)) error("not_found") else { scheduler.cancel(id); runRepo.deleteForJob(id); result(buildJsonObject { put("status", "deleted"); put("id", id) }) }
    }
    val pause = mutation("pause_job", "Приостановить задание текущего помощника и отменить все его запуски.") { id ->
        if (repo.setEnabled(id, owner, false) == null) error("not_found") else { scheduler.cancel(id); result(buildJsonObject { put("status", "paused"); put("id", id) }) }
    }
    val resume = mutation("resume_job", "Возобновить задание текущего помощника с новым будущим запуском.") { id ->
        val job = repo.setEnabled(id, owner, true)
        if (job == null) error("not_found") else { scheduler.cancel(id); val enqueued = scheduler.schedule(job); result(buildJsonObject { put("status", if (enqueued) "resumed" else "blocked"); if (!enqueued) put("code", "job_unavailable"); put("id", id) }) }
    }
    val trigger = mutation("trigger_job_now", "Поставить ручной запуск задания текущего помощника в очередь WorkManager. Возвращает настоящий occurrence_id и статус, без обещания успеха.") { id ->
        val run = scheduler.triggerNow(id, owner)
        if (run == null) error("job_unavailable") else result(buildJsonObject { put("status", run.status); put("occurrence_id", run.occurrenceId); put("scheduled_at_ms", run.scheduledAtMs) })
    }
    return listOf(schedule, list, history, delete, pause, resume, trigger)
}
