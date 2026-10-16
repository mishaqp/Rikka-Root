package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.ai.tools.local.previewAppSettingsChange
import me.rerere.rikkahub.data.ai.tools.local.manageAssistantsApprovalDescription
import me.rerere.rikkahub.data.ai.tools.local.AppThemePreferences
import me.rerere.rikkahub.data.datastore.Settings

internal fun isManagementTool(name: String): Boolean =
    name == "manage_assistants" || name == "manage_app_settings"

/** Unknown or malformed actions cannot accidentally become automatic mutations. */
internal fun managementToolNeedsApproval(name: String, input: JsonElement): Boolean {
    val action = ((input as? JsonObject)?.get("action") as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull
    return when (name) {
        "manage_assistants" -> action !in setOf("list", "get", "list_options")
        "manage_app_settings" -> action !in setOf("list_sections", "get")
        else -> false
    }
}

/** Freeze the values the user approves; never accept a preview supplied by the model. */
internal fun prepareManagementToolApproval(
    name: String, input: JsonElement, settings: Settings, themePreferences: AppThemePreferences? = null,
): JsonElement {
    if (name != "manage_app_settings") return input
    val args = input as? JsonObject ?: return input
    val clean = args - setOf("_expected_before", "_approval_preview")
    if ((clean["action"] as? JsonPrimitive)?.contentOrNull != "set") return JsonObject(clean)
    val preview = runCatching {
        val key = (clean["key"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("Укажите ключ настройки.")
        val value = clean["value"] ?: error("Укажите новое значение.")
        previewAppSettingsChange(settings, key, value, themePreferences)
    }.getOrNull() ?: return JsonObject(clean)
    return JsonObject(clean + mapOf(
        "_expected_before" to preview.before,
        "_approval_preview" to preview.toJson(),
    ))
}

/** Management cards never display raw arguments that could contain rejected credentials. */
internal fun managementToolApprovalDescription(name: String, input: JsonElement): String? = when (name) {
    "manage_assistants" -> manageAssistantsApprovalDescription(input)
    "manage_app_settings" -> {
        val args = input as? JsonObject
        val preview = args?.get("_approval_preview") as? JsonObject
        if (preview != null && preview["before"] == args["_expected_before"] &&
            preview["after"] == args["value"] && preview["key"] == args["key"]
        ) (preview["description"] as? JsonPrimitive)?.contentOrNull
        else "Изменение не подготовлено. Проверьте ключ и значение; запрещённые настройки можно изменить только вручную в настройках."
    }
    else -> null
}
