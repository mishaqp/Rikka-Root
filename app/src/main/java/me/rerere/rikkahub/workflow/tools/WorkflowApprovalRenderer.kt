package me.rerere.rikkahub.workflow.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.workflow.model.ConditionSpec
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson

/**
 * Phase 12 — human-readable approval prompt renderer for `workflow_create` /
 * `workflow_update` (in-app dialog + Telegram approval keyboard message).
 *
 * Output template (matches spec §"Approval prompt rendering"):
 * ```
 * Create workflow "<name>"
 *
 * Когда: <trigger summary>
 * Условия: <comma-separated condition summaries, or "всегда">
 * Действия:
 *   1. <action 1 summary>
 *   2. <action 2 summary>
 *   ...
 *
 * Пауза: <X seconds, or "нет">
 * Дневной лимит: <N запусков, or "без ограничений">
 * ```
 *
 * Telegram variant uses HTML — `<b>` for the title, `<code>` for tool names. Same content,
 * just lightly marked up.
 */
object WorkflowApprovalRenderer {

    /** True if the tool is one of ours and should use this renderer (not the JSON dump). */
    fun isWorkflowTool(toolName: String): Boolean = toolName in WORKFLOW_TOOL_NAMES

    /** Plain-text rendering for the in-app approval card. */
    fun renderPlain(toolName: String, argsJson: String): String {
        val verb = when (toolName) {
            "workflow_create" -> "Создать"
            "workflow_update" -> "Обновить"
            "workflow_delete" -> return renderDelete(argsJson, html = false)
            "workflow_set_enabled" -> return renderSetEnabled(argsJson, html = false)
            "workflow_run" -> return renderRunNow(argsJson, html = false)
            else -> return argsJson  // unknown — fall through to default
        }
        val def = parseDefinition(argsJson) ?: return argsJson
        val sb = StringBuilder()
        sb.appendLine("$verb сценарий \"${def.name}\"")
        sb.appendLine()
        sb.appendLine("Когда: ${triggerSummary(def.trigger)}")
        sb.appendLine("Условия: ${
            if (def.conditions.isEmpty()) "всегда"
            else def.conditions.joinToString(", ") { conditionSummary(it) }
        }")
        sb.appendLine("Действия:")
        for ((idx, action) in def.actions.withIndex()) {
            sb.appendLine("  ${idx + 1}. ${actionSummary(action)}")
        }
        sb.appendLine()
        sb.appendLine("Пауза: ${
            if (def.cooldownSeconds == 0) "нет"
            else "${def.cooldownSeconds} с"
        }")
        sb.appendLine("Дневной лимит: ${def.maxRunsPerDay?.let { "$it запусков" } ?: "без ограничений"}")
        return sb.toString().trimEnd()
    }

    /** Telegram HTML rendering — same content, with `<b>` and `<code>` markup. */
    fun renderTelegramHtml(toolName: String, argsJson: String): String {
        val verb = when (toolName) {
            "workflow_create" -> "Создать"
            "workflow_update" -> "Обновить"
            "workflow_delete" -> return renderDelete(argsJson, html = true)
            "workflow_set_enabled" -> return renderSetEnabled(argsJson, html = true)
            "workflow_run" -> return renderRunNow(argsJson, html = true)
            else -> return escapeHtml(argsJson)
        }
        val def = parseDefinition(argsJson) ?: return escapeHtml(argsJson)
        val sb = StringBuilder()
        sb.appendLine("<b>$verb сценарий \"${escapeHtml(def.name)}\"</b>")
        sb.appendLine()
        sb.appendLine("<b>Когда:</b> ${escapeHtml(triggerSummary(def.trigger))}")
        sb.appendLine("<b>Условия:</b> ${
            if (def.conditions.isEmpty()) "всегда"
            else def.conditions.joinToString(", ") { escapeHtml(conditionSummary(it)) }
        }")
        sb.appendLine("<b>Действия:</b>")
        for ((idx, action) in def.actions.withIndex()) {
            sb.appendLine("  ${idx + 1}. <code>${escapeHtml(action.tool)}</code>(${escapeHtml(actionArgsHint(action))})")
        }
        sb.appendLine()
        sb.appendLine("<b>Пауза:</b> ${
            if (def.cooldownSeconds == 0) "нет"
            else "${def.cooldownSeconds} с"
        }")
        sb.appendLine("<b>Дневной лимит:</b> ${def.maxRunsPerDay?.let { "$it запусков" } ?: "без ограничений"}")
        return sb.toString().trimEnd()
    }

    private fun parseDefinition(argsJson: String): WorkflowDefinition? {
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return null
        val defStr = (obj["definition"] as? JsonObject)?.toString() ?: argsJson
        return WorkflowJson.parseStored(defStr)
    }

    private fun triggerSummary(t: TriggerSpec): String = when (t) {
        is TriggerSpec.TimeCron -> when {
            !t.timeOfDay.isNullOrBlank() -> {
                val days = if (t.daysOfWeek.isEmpty()) "каждый день" else "в дни ${t.daysOfWeek.joinToString(",")}"
                "в ${t.timeOfDay} $days"
            }
            !t.cron.isNullOrBlank() -> "расписание (${t.cron})"
            else -> "расписание"
        }
        is TriggerSpec.WifiConnected -> "WiFi подключён" + (t.ssid?.let { " к $it" }.orEmpty())
        is TriggerSpec.WifiDisconnected -> "WiFi отключён" + (t.ssid?.let { " от $it" }.orEmpty())
        is TriggerSpec.BluetoothDeviceConnected -> "Bluetooth подключён" + (t.deviceAddress?.let { " ($it)" }.orEmpty())
        is TriggerSpec.BluetoothDeviceDisconnected -> "Bluetooth отключён" + (t.deviceAddress?.let { " ($it)" }.orEmpty())
        is TriggerSpec.HeadphonesPlugged -> "наушники подключены"
        is TriggerSpec.HeadphonesUnplugged -> "наушники отключены"
        is TriggerSpec.PowerConnected -> "зарядное устройство подключено"
        is TriggerSpec.PowerDisconnected -> "зарядное устройство отключено"
        is TriggerSpec.BatteryBelow -> "заряд батареи ниже ${t.thresholdPercent}%"
        is TriggerSpec.BatteryAbove -> "заряд батареи выше ${t.thresholdPercent}%"
        is TriggerSpec.GeofenceEnter -> "вход в геозону ${t.label ?: "место (${t.lat},${t.lng}, ${t.radiusM}m)"}"
        is TriggerSpec.GeofenceExit -> "выход из геозоны ${t.label ?: "место (${t.lat},${t.lng}, ${t.radiusM}m)"}"
        is TriggerSpec.AppLaunched -> "${t.packageName} запущено"
        is TriggerSpec.AppClosed -> "${t.packageName} закрыто"
        is TriggerSpec.NotificationReceived -> {
            val parts = mutableListOf<String>()
            t.packageName?.let { parts += "от $it" }
            t.titleContains?.let { parts += "заголовок содержит '$it'" }
            t.textContains?.let { parts += "текст содержит '$it'" }
            t.titleMatches?.let { parts += "заголовок соответствует /$it/" }
            t.textMatches?.let { parts += "текст соответствует /$it/" }
            "пришло уведомление" + if (parts.isEmpty()) "" else " (${parts.joinToString("; ")})"
        }
        is TriggerSpec.BootCompleted -> "загрузка устройства"
        is TriggerSpec.ScreenOn -> "экран включён"
        is TriggerSpec.ScreenOff -> "экран выключен"
        is TriggerSpec.Manual -> "ручной запуск"
    }

    private fun conditionSummary(c: ConditionSpec): String {
        val base = when (c) {
            is ConditionSpec.TimeBetween -> "между ${c.start} и ${c.end}"
            is ConditionSpec.TimeAfterSunset -> "после заката" + if (c.offsetMinutes != 0) " (${c.offsetMinutes} мин сдвиг)" else ""
            is ConditionSpec.TimeBeforeSunrise -> "до рассвета" + if (c.offsetMinutes != 0) " (${c.offsetMinutes} мин сдвиг)" else ""
            is ConditionSpec.DayOfWeekIn -> "в дни ${c.days.joinToString(",")}"
            is ConditionSpec.WifiSsidIs -> "WiFi = ${c.ssid}"
            is ConditionSpec.WifiSsidIn -> "WiFi в ${c.ssids.joinToString(",")}"
            is ConditionSpec.BatteryAbove -> "батарея > ${c.percent}%"
            is ConditionSpec.BatteryBelow -> "батарея < ${c.percent}%"
            is ConditionSpec.IsCharging -> "заряжается"
            is ConditionSpec.IsNotCharging -> "не заряжается"
            is ConditionSpec.ForegroundAppIs -> "${c.packageName} на экране"
            is ConditionSpec.ForegroundAppIn -> "${c.packageNames.size} приложений на экране"
            is ConditionSpec.ScreenIsOn -> "экран включён"
            is ConditionSpec.ScreenIsOff -> "экран выключен"
        }
        return if (c.invert) "НЕ ($base)" else base
    }

    private fun actionSummary(action: WorkflowAction): String {
        val hint = actionArgsHint(action)
        return "${action.tool}($hint)"
    }

    /**
     * Truncate args at ~80 chars per spec. Best-effort `key=value` rendering. Values whose
     * key matches the secret-redaction list (token, password, private_key, etc.) are masked
     * with `***` regardless of length — otherwise an LLM-authored workflow whose action is
     * `telegram_set_token({token: "..."})` or `save_ssh_host({password: "..."})` would echo
     * the secret into the user's chat history (in-app card AND Telegram approval message).
     */
    private fun actionArgsHint(action: WorkflowAction): String {
        if (action.args.isEmpty()) return ""
        val pairs = action.args.entries.joinToString(", ") { (k, v) ->
            val str = if (isSensitiveKey(k)) {
                "***"
            } else when (v) {
                is JsonPrimitive -> v.contentOrNull?.take(40).orEmpty()
                else -> v.toString().take(40)
            }
            "$k=\"$str\""
        }
        return pairs.take(80) + if (pairs.length > 80) "…" else ""
    }

    /**
     * Match a key name against the redaction list (case-insensitive, snake_case AND
     * camelCase aware). Mirrors the redaction model already used by McpApprovalRenderer
     * for header values.
     */
    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase().replace("-", "_").replace(" ", "_")
        return SENSITIVE_KEY_PARTS.any { part -> lower == part || lower.contains(part) }
    }

    private val SENSITIVE_KEY_PARTS = setOf(
        "token", "password", "passphrase", "private_key", "privatekey",
        "secret", "api_key", "apikey", "authorization", "auth_token", "access_token",
        "client_secret", "credential", "credentials",
    )

    private fun renderDelete(argsJson: String, html: Boolean): String {
        val id = runCatching {
            (Json.parseToJsonElement(argsJson) as? JsonObject)
                ?.get("id")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: "?"
        val msg = "Удалить сценарий id=$id"
        return if (html) "<b>${escapeHtml(msg)}</b>" else msg
    }

    private fun renderSetEnabled(argsJson: String, html: Boolean): String {
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
        val id = obj?.get("id")?.jsonPrimitive?.contentOrNull ?: "?"
        val enabled = obj?.get("enabled")?.jsonPrimitive?.contentOrNull
        val verb = if (enabled == "true") "Включить" else "Выключить"
        val msg = "$verb сценарий id=$id"
        return if (html) "<b>${escapeHtml(msg)}</b>" else msg
    }

    private fun renderRunNow(argsJson: String, html: Boolean): String {
        val id = runCatching {
            (Json.parseToJsonElement(argsJson) as? JsonObject)
                ?.get("id")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: "?"
        val msg = "Запустить сценарий сейчас id=$id"
        return if (html) "<b>${escapeHtml(msg)}</b>" else msg
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    val WORKFLOW_TOOL_NAMES = setOf(
        "workflow_create",
        "workflow_list",
        "workflow_get",
        "workflow_update",
        "workflow_delete",
        "workflow_set_enabled",
        "workflow_run",
    )
}
