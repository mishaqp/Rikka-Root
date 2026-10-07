package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootApprovalPolicy

/** Registry of side-effect capabilities actually implemented by this app. */
object ToolPermissionPolicy {
    fun canResumeAutomatic(tool: UIMessagePart.Tool): Boolean =
        !tool.isExecuted && tool.approvalState == ToolApprovalState.Auto &&
            (tool.toolName in registry || tool.toolName.startsWith("mcp__"))

    val registry: Map<String, String> = linkedMapOf(
        "root_exec" to "root-команды",
        "workspace_shell" to "команды workspace",
        "workspace_write_file" to "запись файлов workspace",
        "workspace_edit_file" to "редактирование файлов workspace",
        "clipboard_tool" to "запись в буфер обмена",
        "calendar_create" to "создание событий календаря",
        "memory_tool" to "создание, изменение и удаление памяти",
        "text_to_speech" to "озвучивание текста",
    )

    fun mandatoryConfirmation(name: String, input: JsonElement): Boolean {
        if (name != "root_exec") return false
        val command = ((input as? JsonObject)?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return command.isNullOrBlank() || RootApprovalPolicy.reason(command) != null
    }

    fun sideEffect(name: String, input: JsonElement): Boolean = when (name) {
        "clipboard_tool" -> ((input as? JsonObject)?.get("action") as? JsonPrimitive)?.contentOrNull != "read"
        else -> name in registry
    }

    fun canGrantAlways(name: String, input: JsonElement): Boolean =
        name != "ask_user" && !mandatoryConfirmation(name, input)

    fun apply(tool: Tool, store: RootAccessStore): Tool = tool.copy(needsApproval = { input ->
        when {
            tool.name == "ask_user" -> true // Interactive answers must never be auto-filled.
            mandatoryConfirmation(tool.name, input) -> true
            sideEffect(tool.name, input) || tool.needsApproval(input) -> !store.isAllowed(tool.name)
            else -> false
        }
    })

    fun warningText(): String =
        "Без запроса будут выполняться доступные ассистенту инструменты: ${registry.values.joinToString(", ")}. " +
            "Также будут автоодобряться подключённые MCP-инструменты, для которых настроено подтверждение. Вопросы пользователю остаются интерактивными.\n\n" +
            "Автоодобрение и «Всегда разрешать» не обходят подтверждение массового удаления защищённых каталогов, форматирования, записи в разделы, " +
            "изменения загрузчика, сброса устройства, отключения SELinux/проверки загрузки и remount рабочих разделов. " +
            "После чтения веб-страницы или результата поиска подтверждения временно возвращаются до конца разговора. " +
            "Запретный список не разбирает shell-обёртки (sh -c, eval) и содержимое скриптов.\n\n" +
            "Это опасная функция. Включайте, только если доверяете модели, конфигурации ассистента и своим запросам."
}
