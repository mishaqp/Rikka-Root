package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HeadlessToolPolicy
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.service.CronExpressionParser
import me.rerere.rikkahub.service.CronJobScheduler
import java.time.ZoneId
import kotlin.uuid.Uuid

val CRON_TOOL_NAMES = setOf("schedule_job", "list_jobs", "get_job_history", "delete_job", "pause_job", "resume_job", "trigger_job_now")

private fun scheduleJobMessage(code: String): String = when (code) {
    "invalid_arguments", "unsupported_field" -> "Параметры задания неверны. Проверьте поля вызова и повторите его."
    "invalid_name" -> "Имя задания отсутствует или слишком длинное. Укажите name длиной 1–80 символов."
    "invalid_mode" -> "Режим задания неверен. Укажите mode: llm или direct."
    "invalid_payload" -> "Содержимое задания не соответствует режиму. Укажите prompt для llm или actions для direct."
    "invalid_actions" -> "Действия задания неверны. Укажите от 1 до 12 объектов actions с tool и args."
    "tool_unavailable", "tool_not_allowed", "interactive_tool", "mandatory_confirmation" -> "Инструмент недоступен для фонового задания. Выберите разрешённый инструмент без обязательного диалога."
    "invalid_number" -> "Числовой параметр задания неверен. Укажите целое число без кавычек."
    "invalid_bounds" -> "Границы расписания неверны. Укажите будущий end_at_unix_ms позже start_at_unix_ms в пределах 10 лет."
    "invalid_time" -> "Время разового запуска неверно. Укажите будущий at_unix_ms в пределах 10 лет и границ расписания."
    "invalid_cron" -> "Cron-расписание неверно. Проверьте cron_expression и уберите at_unix_ms."
    "invalid_schedule" -> "Тип расписания неверен. Укажите schedule_type: once или cron."
    "invalid_timezone" -> "Часовой пояс неверен. Укажите timezone в формате IANA, например Europe/Moscow."
    "invalid_run_limit" -> "Лимит запусков неверен. Укажите max_runs от 1 до 100000."
    "invalid_catchup" -> "Режим пропущенных запусков неверен. Укажите catchup: skip, fire_once или fire_all."
    "payload_too_large" -> "Задание слишком велико. Сократите prompt или actions и повторите вызов."
    "missing_id" -> "Не указан ID задания. Вызовите list_jobs и укажите id нужного задания."
    "not_found" -> "Задание текущего ассистента не найдено. Вызовите list_jobs и выберите доступный id."
    "invalid_limit" -> "Лимит истории неверен. Укажите limit от 1 до 100."
    "no_future_occurrence" -> "В расписании нет будущего запуска. Проверьте время и границы задания."
    "missing_conversation_snapshot", "payload_unavailable" -> "Настройки задания недоступны. Откройте текущий чат и создайте задание заново."
    "enqueue_failed", "job_unavailable" -> "Не удалось поставить задание в очередь. Проверьте, что оно включено, и повторите запуск."
    "android_permission_required", "root_not_granted", "root_interaction_required", "root_approval_required", "approval_required" -> "Для запуска нужен доступ на устройстве. Откройте приложение и проверьте разрешения задания и его инструментов."
    "feature_disabled", "job_disabled", "run_no_longer_allowed" -> "Задание или его функция выключены. Проверьте настройки и возобновите задание."
    "run_limit", "hard_budget_exhausted", "token_budget_exceeded", "step_limit" -> "Достигнут лимит выполнения. Проверьте лимит запусков, шагов и бюджет токенов задания."
    "stale_revision", "run_already_claimed", "concurrent_run" -> "Запуск устарел или уже выполняется. Дождитесь завершения и проверьте историю задания."
    "model_unavailable", "unknown_or_ambiguous_model", "unknown_owner" -> "Ассистент или модель задания недоступны. Проверьте настройки текущего чата и создайте задание заново."
    "root_command_blocked", "web_content_blocked" -> "Действие задания заблокировано проверкой безопасности. Проверьте команду и источники данных задания."
    "process_interrupted" -> "Прервано: приложение было закрыто. Часть действий могла выполниться. Проверьте результат и запустите задание вручную."
    "deadline_exceeded", "command_timeout", "execution_cancelled", "execution_interrupted", "root_cleanup_unconfirmed", "root_execution_indeterminate", "tool_result_indeterminate" -> "Выполнение прервано; результат действий может быть неполным. Проверьте его на устройстве перед повторным запуском."
    else -> "Не удалось выполнить операцию с заданием. Проверьте историю, параметры и доступность инструментов, затем повторите вызов."
}

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
    fun error(code: String, cause: Throwable? = null) = result(buildJsonObject {
        put("status", "error"); put("code", code); put("message", scheduleJobMessage(code))
        cause?.let { put("detail", deviceToolFailureReason(it)) }
    })
    fun schema(vararg names: String, required: List<String> = emptyList()) = InputSchema.Obj(
        properties = buildJsonObject { names.forEach { name -> put(name, cronParameterSchema(name)) } }, required = required)
    fun summary(job: ScheduledJobEntity) = buildJsonObject {
        put("id", job.id)
        val payload = runCatching { repo.payload(job) }.getOrNull()
        put("name", payload?.name ?: "Задание недоступно после восстановления")
        if (payload == null) put("message", "Содержимое задания недоступно. Откройте текущий чат и создайте задание заново.")
        put("mode", job.mode); put("schedule_type", job.scheduleType); put("enabled", job.enabled)
        job.cronExpression?.let { put("cron_expression", it) }; job.atUnixMs?.let { put("at_unix_ms", it) }
        put("timezone", job.timezone); job.nextRunAtMs?.let { put("next_run_at_ms", it) }
        job.maxRuns?.let { put("max_runs", it) }; put("total_runs", job.totalRuns); put("total_succeeded", job.totalSucceeded)
        put("total_failed", job.totalFailed); put("total_blocked", job.totalBlocked); put("total_cancelled", job.totalCancelled); put("total_indeterminate", job.totalIndeterminate)
    }
    val schedule = Tool(
        name = "schedule_job", needsApproval = { true },
        description = "Создать задание текущего помощника: mode llm с prompt или direct с actions [{tool,args}]. schedule_type once с at_unix_ms либо cron с cron_expression (пять полей, @daily, @every 30m). timezone — IANA. catchup skip/fire_once/fire_all (не более 20). max_runs ограничивает число попыток независимо от истории. Время запуска задаёт точный будильник Android с пробуждением в Doze; WorkManager выполняет задание и служит запасным путём, если точные будильники недоступны. Система может задержать исполнение при исчерпании квоты фоновой работы. Фоновые действия требуют действующих разрешений и могут быть заблокированы. Не передавайте пароли или ключи: используйте ссылки на AndroidKeyStore. Размер JSON задания — не более 60000 байт UTF-8.",
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
            for (action in actions) {
                HeadlessToolPolicy.blockReason(action.tool, action.args)?.let { return@Tool error(it) }
            }
            if (callerConversation.config == null) return@Tool error("missing_conversation_snapshot")
            val payload = ScheduledJobPayload(text("name")!!, text("prompt"), actions, callerConversation.config, callerConversation.workspaceCwd, allowed)
            try {
                val created = repo.create(job.copy(nextRunAtMs = next), payload)
                val enqueued = scheduler.schedule(created)
                result(buildJsonObject { put("status", if (enqueued) "scheduled" else "blocked"); if (!enqueued) { put("code", "enqueue_failed"); put("message", scheduleJobMessage("enqueue_failed")) }; put("job", summary(repo.getById(created.id) ?: created)) })
            } catch (cancel: CancellationException) { throw cancel }
            catch (cause: Exception) { error("schedule_failed", cause) }
        },
    )
    val list = Tool(name = "list_jobs", needsApproval = { true }, description = "Список до 100 неудалённых заданий текущего ассистента, от новых к старым, без промптов и аргументов. count — выданные строки, total — число заданий, truncated — есть ещё. Продолжение списка через этот инструмент недоступно; активные задания доступны в настройках, повтор вызова возвращает первую часть.", parameters = { schema() }, execute = {
        val jobs = repo.listOwned(owner)
        val total = repo.countOfAssistant(owner)
        result(buildJsonObject {
            put("jobs", buildJsonArray { jobs.forEach { add(summary(it)) } })
            put("count", jobs.size); put("total", if (total >= jobs.size) JsonPrimitive(total) else JsonNull)
            put("truncated", total > jobs.size)
            if (total > jobs.size) put("message", "Показаны первые 100 заданий; продолжение списка через list_jobs недоступно. Активные задания проверьте в настройках расписаний. Для известного id используйте get_job_history; повторный list_jobs возвращает ту же первую часть.")
        })
    })
    val history = Tool(name = "get_job_history", needsApproval = { true }, description = "История задания текущего помощника: queued/running/succeeded/failed/blocked/cancelled/indeterminate, время и безопасные коды. Не содержит ответов или секретов. count — число выданных записей, truncated — есть более старые, total — точное число сохранённых записей либо null. При truncated можно увеличить limit до 100; продолжение за пределами 100 недоступно.", parameters = { schema("id", "limit", required = listOf("id")) }, execute = { input ->
        val obj = input as? JsonObject ?: return@Tool error("invalid_arguments")
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@Tool error("missing_id")
        repo.getOwned(id, owner) ?: return@Tool error("not_found")
        val limit = if ("limit" !in obj) 20 else (obj["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return@Tool error("invalid_limit")
        if (limit !in 1..100) return@Tool error("invalid_limit")
        val fetchedRuns = runRepo.recentWithLookahead(id, limit)
        val runs = fetchedRuns.take(limit)
        val truncated = fetchedRuns.size > limit
        result(buildJsonObject {
            put("runs", buildJsonArray { runs.forEach { run -> add(buildJsonObject {
                put("occurrence_id", run.occurrenceId); put("status", run.status); put("scheduled_at_ms", run.scheduledAtMs)
                run.startedAtMs?.let { put("started_at_ms", it) }; run.finishedAtMs?.let { put("finished_at_ms", it) }; run.outcomeCode?.let { put("code", it); put("message", scheduleJobMessage(it)) }
            }) } })
            put("count", runs.size); put("truncated", truncated)
            put("total", if (truncated) JsonNull else JsonPrimitive(runs.size))
            if (truncated) put("message", if (limit < 100)
                "Есть более старые записи. Повторите get_job_history с тем же id и увеличьте limit до 100; инструмент не поддерживает продолжение за пределами 100 записей."
            else "Показаны 100 последних записей. Достигнут максимальный limit; продолжение более старой истории недоступно, повтор возвращает ту же первую часть.")
        })
    })
    fun mutation(name: String, description: String, action: suspend (String) -> List<UIMessagePart>): Tool = Tool(
        name = name, needsApproval = { true }, description = description, parameters = { schema("id", required = listOf("id")) }, execute = { input ->
            val id = ((input as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@Tool error("missing_id")
            repo.getOwned(id, owner) ?: return@Tool error("not_found")
            try { action(id) } catch (cancel: CancellationException) { throw cancel } catch (cause: Exception) { error("mutation_failed", cause) }
        })
    val delete = mutation("delete_job", "Удалить задание текущего помощника и его историю; отменить обычные, ручные и пропущенные запуски.") { id ->
        if (!repo.deleteOwned(id, owner)) error("not_found") else { scheduler.cancel(id); runRepo.deleteForJob(id); result(buildJsonObject { put("status", "deleted"); put("id", id) }) }
    }
    val pause = mutation("pause_job", "Приостановить задание текущего помощника и отменить все его запуски.") { id ->
        if (repo.setEnabled(id, owner, false) == null) error("not_found") else { scheduler.cancel(id); result(buildJsonObject { put("status", "paused"); put("id", id) }) }
    }
    val resume = mutation("resume_job", "Возобновить задание текущего помощника с новым будущим запуском.") { id ->
        val job = repo.setEnabled(id, owner, true)
        if (job == null) error("not_found") else { scheduler.cancel(id); val enqueued = scheduler.schedule(job); result(buildJsonObject { put("status", if (enqueued) "resumed" else "blocked"); if (!enqueued) { put("code", "job_unavailable"); put("message", scheduleJobMessage("job_unavailable")) }; put("id", id) }) }
    }
    val trigger = mutation("trigger_job_now", "Поставить ручной запуск задания текущего помощника в очередь WorkManager. Возвращает настоящий occurrence_id и статус, без обещания успеха.") { id ->
        val run = scheduler.triggerNow(id, owner)
        if (run == null) error("job_unavailable") else result(buildJsonObject { put("status", run.status); put("occurrence_id", run.occurrenceId); put("scheduled_at_ms", run.scheduledAtMs) })
    }
    return listOf(schedule, list, history, delete, pause, resume, trigger)
}

/** Public argument contract mirrors ScheduleJobValidator; arbitrary action args stay free-form. */
private fun cronParameterSchema(name: String): JsonObject = buildJsonObject {
    val integers = setOf("at_unix_ms", "start_at_unix_ms", "end_at_unix_ms", "max_runs", "limit")
    put("type", when { name in integers -> "integer"; name == "actions" -> "array"; else -> "string" })
    put("description", when (name) {
        "name" -> "Название задания, от 1 до 80 символов."
        "mode" -> "Режим: llm с prompt либо direct с actions."
        "prompt" -> "Непустой текст задачи для llm, до 4000 символов; не передавайте одновременно actions."
        "actions" -> "От 1 до 12 действий для direct; не передавайте одновременно prompt."
        "schedule_type" -> "once — один запуск по at_unix_ms; cron — повторяющееся расписание cron_expression."
        "at_unix_ms" -> "Будущий момент однократного запуска: целое время Unix в миллисекундах, в пределах 10 лет."
        "cron_expression" -> "Повторяющееся расписание из пяти полей либо @daily, @every 30m; только для cron."
        "timezone" -> "Часовой пояс IANA, например Europe/Moscow; по умолчанию часовой пояс устройства."
        "start_at_unix_ms" -> "Начальная граница расписания: целое время Unix в миллисекундах от 0 до текущего времени плюс 10 лет; по умолчанию без отдельной границы."
        "end_at_unix_ms" -> "Конечная граница: будущее целое время Unix в миллисекундах, позже начала и в пределах 10 лет; по умолчанию без границы."
        "max_runs" -> "Максимальное число попыток от 1 до 100000; отсутствие — без ограничения."
        "catchup" -> "Пропущенные запуски: skip — пропустить, fire_once — один, fire_all — до 20; по умолчанию fire_once."
        "id" -> "Строковый id задания текущего ассистента из list_jobs."
        "limit" -> "Число записей истории от 1 до 100, по умолчанию 20; целое число без кавычек."
        else -> error("Unknown cron parameter")
    })
    val choices = when (name) {
        "mode" -> listOf("llm", "direct")
        "schedule_type" -> listOf("once", "cron")
        "catchup" -> listOf("skip", "fire_once", "fire_all")
        else -> emptyList()
    }
    if (choices.isNotEmpty()) put("enum", JsonArray(choices.map(::JsonPrimitive)))
    if (name == "catchup") put("default", "fire_once")
    if (name == "name") { put("minLength", 1); put("maxLength", 80) }
    if (name == "prompt") { put("minLength", 1); put("maxLength", 4000) }
    if (name == "start_at_unix_ms") put("minimum", 0)
    if (name == "max_runs") { put("minimum", 1); put("maximum", 100000) }
    if (name == "limit") { put("minimum", 1); put("maximum", 100); put("default", 20) }
    if (name == "actions") {
        put("minItems", 1); put("maxItems", 12)
        put("items", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {
                put("tool", buildJsonObject { put("type", "string"); put("description", "Имя доступного фонового инструмента; вложенный cron и dispatch субагентов запрещены.") })
                put("args", buildJsonObject { put("type", "object"); put("additionalProperties", true); put("description", "Аргументы выбранного инструмента по его схеме; секреты передавайте только ссылками на хранилище.") })
            }); put("required", buildJsonArray { add("tool"); add("args") })
        })
    }
}
