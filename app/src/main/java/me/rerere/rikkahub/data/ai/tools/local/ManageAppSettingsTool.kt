package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.managementToolNeedsApproval
import me.rerere.rikkahub.data.datastore.SettingsStore

internal fun buildManageAppSettingsTool(settingsStore: SettingsStore, context: Context? = null): Tool = manageAppSettingsTool(
    AppSettingsEngine({ settingsStore.settingsFlow.value }, settingsStore::update, context?.let(::appThemePreferences))
)

/** The approval runtime replaces _expected_before before showing a pending set request. */
internal fun manageAppSettingsTool(engine: AppSettingsEngine): Tool = Tool(
    name = "manage_app_settings",
    description = "Управление обычными настройками Rikka. Сначала list_sections, затем get с section или key, затем set с key и простым value. Названия, типы, диапазоны и реальные ID доступны в get. Каждый set требует одобрения с текущими значениями «было → стало» и сразу сохраняет настройку. Модель чата по умолчанию не меняет модель уже сохранённого разговора. API-ключи, пароли, токены, заголовки и тела запросов, MCP/SSH/WebDAV/S3/proxy, безопасность, разрешения, HARDLINE, root, доступ ко всей памяти и удаление данных недоступны: это меняется только вручную. Не передавайте секреты.",
    needsApproval = { input -> managementToolNeedsApproval("manage_app_settings", input) },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("list_sections"); add("get"); add("set") }) })
                put("section", buildJsonObject { put("type", "string"); put("description", "ID раздела из list_sections; только для get, вместо key") })
                put("key", buildJsonObject { put("type", "string"); put("description", "Точный ключ из get или list_sections; обязателен для set") })
                put("value", buildJsonObject {
                    put("description", "Новое простое значение правильного типа: строка, число, boolean или null для наследуемой настройки. Объекты и массивы запрещены.")
                    put("anyOf", buildJsonArray {
                        for (type in listOf("string", "number", "boolean", "null")) add(buildJsonObject { put("type", type) })
                    })
                })
            },
            required = listOf("action"),
        )
    },
    execute = { input ->
        fun error(message: String, code: String = "invalid_arguments") = buildJsonObject { put("status", "error"); put("code", code); put("message", message) }
        val args = input as? JsonObject
        val action = (args?.get("action") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val key = (args?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val section = (args?.get("section") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val result = when {
            args == null -> error("Укажите action: list_sections, get или set.")
            args.keys.any { it !in setOf("action", "section", "key", "value", "_expected_before", "_approval_preview") } -> error(APP_SETTINGS_MANUAL_ONLY, "manual_only")
            action == "list_sections" && args.keys == setOf("action") -> engine.listSections()
            action == "get" && args.keys.all { it in setOf("action", "section", "key") } -> engine.get(section, key)
            action == "set" && key != null && "value" in args && args.keys.all { it in setOf("action", "key", "value", "_expected_before", "_approval_preview") } -> engine.set(key, args.getValue("value"), args["_expected_before"])
            else -> error("Укажите list_sections без дополнительных полей; get с section или key; set с key и value.")
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
