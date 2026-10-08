package me.rerere.rikkahub.automation

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Phase 13 — External Automation config tools.
 *
 * Why these are LLM-callable tools instead of a Settings page in v1:
 *  * The project's existing pattern for security-critical config (Telegram bot setup,
 *    SSH host management, MCP control) is chat-driven via approval-gated tools — no new
 *    Settings page per phase.
 *  * Building a polished Compose Settings screen for the External Automation toggle adds
 *    significant scope without functional payoff: the data model is already done in
 *    [ExternalAutomationConfig], a chat tool surface is enough to flip the bits.
 *  * A future phase can add the Settings UI as polish; the data model, exported activity,
 *    receiver, and trust gate are all designed to outlive that addition.
 *
 * Mutations require approval because they change who's allowed to fire the assistant.
 * Status also reads personal invocation history and is covered by Rikka-Root's approval
 * registry. Headless invocations cannot mutate their own caller trust configuration.
 */

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

private fun okEnv(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> {
    return listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))
}

fun externalAutomationStatusTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_status",
    description = """
        Прочитать состояние внешней автоматизации: включён ли общий переключатель,
        какие пакеты вызывающих приложений доверенные и последние 20 вызовов.
        Используйте перед изменением настроек, чтобы сообщить пользователю текущее состояние.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    needsApproval = { true },
    execute = {
        val enabled = config.enabledFlow.first()
        val trusted = config.trustedPackagesFlow.first().toList().sorted()
        val recent = config.recentInvocationsFlow.first().takeLast(20)
        okEnv {
            put("enabled", enabled)
            putJsonArray("trusted_packages") { trusted.forEach { add(it) } }
            putJsonArray("recent_invocations") {
                recent.forEach { entry ->
                    addJsonObject {
                        put("timestamp_ms", entry.timestampMs)
                        put("caller_package", entry.callerPackage)
                        put("action", entry.action)
                        put("status", entry.status)
                        if (!entry.requestId.isNullOrBlank()) put("request_id", entry.requestId)
                    }
                }
            }
        }
    },
)

fun externalAutomationSetEnabledTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_set_enabled",
    description = """
        Включить или выключить Intent API внешней автоматизации. По умолчанию выключено:
        экспортированные Activity и Receiver отклоняют все запросы независимо от приложения.
        Для запуска также нужен доверенный пакет вызывающего приложения — добавьте его
        через external_automation_add_trusted_package. Пакет проверяется средствами Android.

        Требует одобрения: настройка меняет доступ к ассистенту из других приложений.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("enabled", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("enabled"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val enabled = args.jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return@Tool errEnv("invalid_enabled", "Укажите enabled: true или false")
        config.setEnabled(enabled)
        okEnv {
            put("enabled", enabled)
        }
    },
)

fun externalAutomationAddTrustedPackageTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_add_trusted_package",
    description = """
        Добавить пакет вызывающего приложения в доверенный список, например
        "net.dinglisch.android.taskerm" для Tasker или "com.arlosoft.macrodroid" для MacroDroid.
        Доверенные приложения могут отправлять RUN_TASK без отдельного диалога при включённой
        функции. Недоверенные или не подтверждённые Android приложения отклоняются;
        диалог разового одобрения ещё не реализован в Agent. Требует одобрения.
        Activity-вызов должен использовать startActivityForResult либо передавать
        ActivityOptions.setShareIdentityEnabled(true) на Android 14 и выше; обычный
        Send Intent без подтверждённого пакета может быть отклонён.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject { put("type", "string") })
            },
            required = listOf("package_name"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val pkg = args.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return@Tool errEnv("invalid_package_name", "Укажите package_name")
        if (pkg.isEmpty()) {
            return@Tool errEnv("invalid_package_name", "package_name не может быть пустым")
        }
        // Cheap sanity check on caller package format. Caller package is a Java identifier
        // path: alphanumeric + dots + underscores only. Reject anything else as malformed.
        if (!pkg.matches(Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)*$"""))) {
            return@Tool errEnv(
                "invalid_package_name",
                "package_name '$pkg' не является допустимым именем пакета Android"
            )
        }
        config.addTrustedPackage(pkg)
        val current = config.trustedPackagesFlow.first()
        okEnv {
            put("added", pkg)
            putJsonArray("trusted_packages") { current.sorted().forEach { add(it) } }
        }
    },
)

fun externalAutomationRemoveTrustedPackageTool(config: ExternalAutomationConfig): Tool = Tool(
    name = "external_automation_remove_trusted_package",
    description = """
        Удалить пакет вызывающего приложения из доверенного списка. После удаления
        следующие Intent-запросы этого приложения отклоняются. Требует одобрения.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject { put("type", "string") })
            },
            required = listOf("package_name"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val pkg = args.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return@Tool errEnv("invalid_package_name", "Укажите package_name")
        if (pkg.isEmpty()) {
            return@Tool errEnv("invalid_package_name", "package_name не может быть пустым")
        }
        config.removeTrustedPackage(pkg)
        val current = config.trustedPackagesFlow.first()
        okEnv {
            put("removed", pkg)
            putJsonArray("trusted_packages") { current.sorted().forEach { add(it) } }
        }
    },
)
