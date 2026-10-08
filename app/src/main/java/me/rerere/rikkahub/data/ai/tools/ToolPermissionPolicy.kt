package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.data.preferences.isWorkspaceToolName

/** Registry of side-effect capabilities actually implemented by this app. */
object ToolPermissionPolicy {
    fun canResumeAutomatic(tool: UIMessagePart.Tool): Boolean =
        !tool.isExecuted && tool.approvalState == ToolApprovalState.Auto &&
            tool.toolName != "ask_user"

    val registry: Map<String, String> = linkedMapOf(
        "root_exec" to "root-команды",
        "workspace_shell" to "команды workspace",
        "workspace_background_start" to "запуск фоновых процессов workspace",
        "workspace_background_stop" to "остановка фоновых процессов workspace",
        "workspace_write_file" to "запись файлов workspace",
        "workspace_edit_file" to "редактирование файлов workspace",
        "clipboard_tool" to "запись в буфер обмена",
        "calendar_create" to "создание событий календаря",
        "memory_tool" to "создание, изменение и удаление памяти",
        "text_to_speech" to "озвучивание текста",
        "show_toast" to "всплывающие сообщения",
        "post_notification" to "публикация уведомлений",
        "share" to "открытие системного меню отправки",
        "set_torch" to "управление фонариком",
        "vibrate" to "вибрация устройства",
        "get_brightness" to "чтение яркости экрана",
        "set_brightness" to "изменение яркости экрана",
        "get_volume" to "чтение громкости",
        "set_volume" to "изменение громкости",
        "set_wallpaper" to "смена обоев",
        "nfc_status" to "состояние NFC",
        "nfc_read_tag" to "чтение NFC-меток",
        "nfc_write_tag" to "запись NFC-меток",
    )

    fun mandatoryConfirmation(name: String, input: JsonElement): Boolean {
        if (name != "root_exec") return false
        val command = ((input as? JsonObject)?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return command.isNullOrBlank() || RootApprovalPolicy.reason(command) != null
    }

    fun sideEffect(name: String, input: JsonElement): Boolean = when (name) {
        "clipboard_tool" -> ((input as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull != "read"
        else -> ToolApprovalDefaults.requiresApproval(name)
    }

    fun canGrantAlways(name: String, input: JsonElement): Boolean =
        name != "ask_user" && !mandatoryConfirmation(name, input)

    fun apply(tool: Tool): Tool = tool.copy(needsApproval = { input ->
        when {
            tool.name == "ask_user" -> true // Interactive answers must never be auto-filled.
            mandatoryConfirmation(tool.name, input) -> true
            // Resolve workspace's live overrides through isToolAutoApproved, not a frozen tool definition.
            isWorkspaceToolName(tool.name) -> true
            else -> sideEffect(tool.name, input) || tool.needsApproval(input)
        }
    })

    fun warningText(): String =
        "Без запроса будут выполняться доступные ассистенту инструменты: ${registry.values.joinToString(", ")}. " +
            "Также будут автоодобряться подключённые MCP-инструменты, для которых настроено подтверждение. Вопросы пользователю остаются интерактивными.\n\n" +
            "Автоодобрение и «Всегда разрешать» не обходят подтверждение массового удаления защищённых каталогов, форматирования, записи в разделы, " +
            "изменения загрузчика, сброса устройства, отключения SELinux/проверки загрузки и remount рабочих разделов. " +
            "Подтверждения после веб-поиска возвращаются только при включённом переключателе «Спрашивать после веб-контента». " +
            "Запретный список не разбирает shell-обёртки (sh -c, eval) и содержимое скриптов.\n\n" +
            "Это опасная функция. Включайте, только если доверяете модели, конфигурации ассистента и своим запросам."
}
