package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.Icon1stBracket
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.ShieldKey
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.hugeicons.stroke.Voice
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.ai.tools.local.commandExitFailureReason
import me.rerere.rikkahub.data.ai.tools.local.CommandEnvironment

/**
 * Presentation of one timeline step: a one-line title with the essence of the call and a short
 * preview of its result. Details render readable fields, not protocol envelopes. Nothing here
 * changes the stored call, its permissions, model-facing output or execution.
 */
internal sealed interface ToolResultPreview {
    /** Command output; [exitCode] is set only when it is not zero. */
    data class Command(val stdout: String, val stderr: String, val exitCode: Int?, val reason: String? = null) : ToolResultPreview

    /** A human-readable reason in Russian. */
    data class Error(val message: String) : ToolResultPreview

    /** Ordinary previews are compact; warnings and partial-effect notes retain their full text. */
    data class Summary(val text: String, val preserveFullText: Boolean = false) : ToolResultPreview
}

internal val COMMAND_OUTPUT_TOOL_NAMES = setOf(
    "root_exec", "termux_run_command", "ssh_exec", "ssh_exec_saved", "workspace_shell",
    "workspace_background_output", "termux_session_start", "termux_session_send", "termux_session_read",
)

private val foregroundExitReasonTools = setOf("root_exec", "termux_run_command", "ssh_exec", "ssh_exec_saved", "workspace_shell")

private const val TITLE_MAX_CHARS = 200
private const val SUMMARY_PIECE_MAX_CHARS = 80

// --- Title ---------------------------------------------------------------------------------

/** "Root: ls /sdcard", "Файл: записан test.txt"; null when the call has nothing to show. */
internal fun presentToolHeadline(toolName: String, arguments: JsonElement?): String? {
    val args = arguments as? JsonObject ?: JsonObject(emptyMap())
    fun s(key: String) = args.string(key)?.oneLine()?.takeIf { it.isNotEmpty() }
    fun file(verb: String, key: String = "path") = s(key)?.let { "Файл: $verb ${it.baseName()}" }
    val headline = when (toolName) {
        "root_exec" -> s("command")?.let { "Root: $it" }
        "termux_run_command" -> (s("command") ?: s("executable")?.let { exe ->
            (listOf(exe.baseName()) + args.strings("arguments")).joinToString(" ").oneLine()
        })?.let { "Termux: $it" }
        "termux_session_start" -> (s("command") ?: s("name"))?.let { "Termux: $it" }
        "termux_session_send" -> s("input")?.let { "Termux: $it" }
        "ssh_exec" -> s("command")?.let { command -> s("host")?.let { "SSH $it: $command" } ?: "SSH: $command" }
        "ssh_exec_saved" -> s("command")?.let { command -> s("name")?.let { "SSH $it: $command" } ?: "SSH: $command" }
        "ssh_upload" -> s("remote_path")?.let { "SSH: отправка ${it.baseName()}" }
        "ssh_download" -> s("remote_path")?.let { "SSH: загрузка ${it.baseName()}" }
        "workspace_shell", "workspace_background_start" -> s("command")?.let { "Терминал: $it" }
        "workspace_write_file", "write_text_file", "write_binary_file" -> file("записан")
        "workspace_edit_file" -> file("изменён")
        "workspace_read_file", "read_file" -> file("прочитан")
        "delete_file" -> file("удалён")
        "file_info" -> file("сведения о")
        "open_file" -> file("открыт")
        "show_image" -> s("path")?.let { "Изображение: ${it.baseName()}" }
        "copy_file" -> file("скопирован", "src")
        "move_file" -> file("перемещён", "src")
        "create_directory" -> (s("name") ?: s("path")?.baseName())?.let { "Папка: создана $it" }
        "list_files" -> s("path")?.let { "Папка: ${it.baseName()}" }
        "find_files" -> s("query")?.let { "Поиск файлов: $it" }
        "zip_files" -> s("destination")?.let { "Архив: создан ${it.baseName()}" }
        "unzip_file", "list_zip_contents" -> s("source")?.let { "Архив: ${it.baseName()}" }
        "browser_open" -> s("url")?.let { "Браузер: открыта ${it.shortUrl()}" }
        "browser_click", "browser_click_and_read" -> s("selector")?.let { "Браузер: нажатие $it" }
        "browser_type" -> s("selector")?.let { "Браузер: ввод в $it" }
        "browser_select" -> s("selector")?.let { "Браузер: выбор в $it" }
        "browser_submit" -> s("selector")?.let { "Браузер: отправка $it" }
        "browser_wait_for" -> s("selector")?.let { "Браузер: ожидание $it" }
        "browser_press_key" -> s("key")?.let { "Браузер: клавиша $it" }
        "open_url" -> s("url")?.let { "Ссылка: ${it.shortUrl()}" }
        "download_file" -> (s("filename") ?: s("url")?.shortUrl())?.let { "Загрузка: $it" }
        "scrape_web" -> s("url")?.let { "Страница: ${it.shortUrl()}" }
        "search_web", "web_search" -> s("query")?.let { "Поиск: $it" }
        "conversation_search" -> s("query")?.let { "Поиск в чатах: $it" }
        "use_skill", "skill_get_content", "skill_install_from_url", "skill_install_from_text" ->
            s("name")?.let { "Навык: $it" }
        "launch_app", "list_app_activities" -> s("package_name")?.let { "Приложение: $it" }
        "post_notification" -> s("title")?.let { "Уведомление: $it" }
        "show_toast" -> s("text")?.let { "Сообщение: $it" }
        "text_to_speech" -> s("text")?.let { "Озвучка: $it" }
        "calendar_create", "create_calendar_event" -> s("title")?.let { "Календарь: $it" }
        "workflow_run", "workflow_get", "workflow_delete" -> s("id")?.let { "Сценарий: $it" }
        "manage_assistants" -> assistantManagementHeadline(s("action"), s("name")
            ?: (args["settings"] as? JsonObject)?.string("name")?.oneLine()?.takeIf { it.isNotEmpty() }
            ?: s("assistant_id") ?: s("id"))
        "manage_app_settings" -> appSettingsHeadline(s("action"), s("key") ?: s("section"))
        "mcp_add" -> s("name")?.let { "MCP: добавить $it" }
        "mcp_test" -> s("id")?.let { "MCP: проверка $it" }
        else -> remoteMcpHeadline(toolName)
    }
    return headline?.take(TITLE_MAX_CHARS)
}

private fun assistantManagementHeadline(action: String?, target: String?): String? {
    val title = when (action) {
        "list" -> return "Ассистенты: список"
        "list_options" -> return "Ассистенты: доступные параметры"
        "get" -> "Ассистент: просмотр"
        "create" -> "Ассистент: создать"
        "update" -> "Ассистент: изменить"
        "duplicate" -> "Ассистент: скопировать"
        "switch" -> "Ассистент: переключить на"
        "delete" -> "Ассистент: удалить"
        else -> return null
    }
    return listOfNotNull(title, target).joinToString(" ")
}

private fun appSettingsHeadline(action: String?, target: String?): String? {
    val title = when (action) {
        "list_sections" -> return "Настройки: доступные разделы"
        "get" -> "Настройки: просмотр"
        "set" -> "Настройки: изменить"
        else -> return null
    }
    return listOfNotNull(title, target).joinToString(" ")
}

private val remoteMcpName = Regex("mcp__[0-9a-fA-F]{8}_(.+)__(.+)", RegexOption.DOT_MATCHES_ALL)

private fun remoteMcpHeadline(toolName: String): String? =
    remoteMcpName.matchEntire(toolName)?.let { "MCP ${it.groupValues[1]}: ${it.groupValues[2]}" }

// --- Icon ------------------------------------------------------------------------------------

/**
 * The icon of a tool's category, as in the assistant's tool catalog. Null means "no own icon":
 * the registered renderer's icon (or the generic wrench for unknown tools) is used instead.
 */
internal fun timelineToolIcon(toolName: String): ImageVector? = when {
    toolName == "root_exec" -> HugeIcons.Bash
    toolName.startsWith("termux_") -> HugeIcons.ComputerTerminal01
    toolName.startsWith("workspace_background_") || toolName == "workspace_shell" -> HugeIcons.ComputerTerminal01
    toolName.startsWith("workspace_") -> HugeIcons.Files02
    toolName.startsWith("ssh_") || toolName.endsWith("_ssh_host") || toolName == "list_ssh_hosts" -> HugeIcons.ServerStack01
    toolName.startsWith("browser_") -> HugeIcons.Earth
    toolName.startsWith("mcp_") || remoteMcpName.matches(toolName) -> HugeIcons.McpServer
    toolName.startsWith("external_automation_") -> HugeIcons.Connect
    toolName.startsWith("subagent_") -> HugeIcons.GitFork
    toolName.startsWith("workflow_") -> HugeIcons.Zap
    toolName.startsWith("skill_install_") -> HugeIcons.FileImport
    toolName.startsWith("keystore_") -> HugeIcons.ShieldKey
    toolName.startsWith("nfc_") -> HugeIcons.Connect
    else -> exactToolIcons[toolName]
}

private val exactToolIcons: Map<String, ImageVector> = buildMap {
    put("http_request", HugeIcons.Internet)
    listOf("set_alarm", "set_timer", "show_alarms").forEach { put(it, HugeIcons.Clock02) }
    listOf("qr_generate", "qr_scan").forEach { put(it, HugeIcons.Image02) }
    put("read_document", HugeIcons.Files02)
    listOf("schedule_job", "list_jobs", "get_job_history", "pause_job", "resume_job", "delete_job",
        "trigger_job_now", "get_time_info").forEach { put(it, HugeIcons.Clock02) }
    listOf("tap", "long_press", "swipe", "scroll", "find_node", "click_node", "set_text", "global_action",
        "read_window_tree", "take_screenshot", "wake_screen").forEach { put(it, HugeIcons.CursorPointer01) }
    listOf("read_file", "write_text_file", "write_binary_file", "delete_file", "copy_file", "move_file",
        "batch_copy", "batch_move", "batch_delete", "file_info", "find_files", "list_files", "create_directory",
        "open_file").forEach { put(it, HugeIcons.Files02) }
    listOf("list_storage_volumes", "grant_directory_access", "list_granted_directories", "revoke_directory_access")
        .forEach { put(it, HugeIcons.Folder01) }
    listOf("zip_files", "unzip_file", "list_zip_contents").forEach { put(it, HugeIcons.Package01) }
    listOf("play_media", "pause_media", "resume_media", "stop_media", "seek_media", "get_media_status")
        .forEach { put(it, HugeIcons.MusicNote03) }
    listOf("scan_media", "show_image", "set_wallpaper").forEach { put(it, HugeIcons.Image02) }
    listOf("get_audio_info", "get_volume", "set_volume", "text_to_speech").forEach { put(it, HugeIcons.VolumeHigh) }
    listOf("get_brightness", "set_brightness").forEach { put(it, HugeIcons.Sun01) }
    listOf("list_contacts", "search_contacts", "create_contact").forEach { put(it, HugeIcons.Book01) }
    listOf("list_sms_inbox", "search_sms", "send_sms_intent", "send_email_intent", "show_toast")
        .forEach { put(it, HugeIcons.Message01) }
    listOf("launch_app", "launch_activity", "list_installed_apps", "list_app_activities")
        .forEach { put(it, HugeIcons.Rocket01) }
    listOf("list_sensors", "read_sensor").forEach { put(it, HugeIcons.Cpu) }
    listOf("web_search", "file_search").forEach { put(it, HugeIcons.Search01) }
    put("code_interpreter", HugeIcons.Code)
    put("run_js", HugeIcons.Code)
    put("eval_javascript", HugeIcons.Icon1stBracket)
    put("ask_user", HugeIcons.BubbleChatQuestion)
    put("check_token_usage", HugeIcons.MoneyBag02)
    put("generate_bug_report", HugeIcons.Bug01)
    put("manage_assistants", HugeIcons.AiBrain01)
    put("manage_app_settings", HugeIcons.Settings03)
    put("download_file", HugeIcons.Download01)
    put("open_url", HugeIcons.Link01)
    put("get_battery_status", HugeIcons.Zap)
    put("set_torch", HugeIcons.Zap)
    put("get_telephony_info", HugeIcons.SmartPhone01)
    put("vibrate", HugeIcons.SmartPhone01)
    put("get_wifi_info", HugeIcons.Internet)
    put("open_wifi_settings", HugeIcons.Settings03)
    put("get_storage_info", HugeIcons.Database02)
    put("get_location", HugeIcons.Pin)
    put("show_location_on_map", HugeIcons.Pin)
    put("list_call_log", HugeIcons.TransactionHistory)
    put("send_sms", HugeIcons.MessageAdd01)
    put("create_calendar_event", HugeIcons.Calendar03)
    put("verify_fingerprint", HugeIcons.CheckmarkCircle01)
    put("take_photo", HugeIcons.Camera01)
    put("record_audio", HugeIcons.Mic01)
    put("speech_to_text", HugeIcons.Voice)
    put("post_notification", HugeIcons.Notification01)
    put("share", HugeIcons.Share01)
    put("memory_tool", HugeIcons.AiBrain01)
    put("skill_get_content", HugeIcons.MagicWand01)
    put("use_skill", HugeIcons.MagicWand01)
}

// --- Result preview -------------------------------------------------------------------------

/**
 * [outputText] is the joined text output, [outputJson] its JSON when it parses. Returns null when
 * there is no textual output to summarize.
 */
internal fun presentToolResult(toolName: String, outputText: String?, outputJson: JsonElement?): ToolResultPreview? {
    if (outputText.isNullOrBlank()) return null
    val result = outputJson ?: parseToolOutputJson(toolName, listOf(outputText))
    val obj = result as? JsonObject
    if (obj != null) {
        toolErrorMessage(toolName, obj)?.let { return ToolResultPreview.Error(it) }
        val warning = toolWarningMessage(toolName, obj)
        val preview = if (toolName in COMMAND_OUTPUT_TOOL_NAMES) commandPreview(toolName, obj) else null
        if (warning != null) {
            val text = preview?.readableText() ?: specialSummary(toolName, obj) ?: genericSummary(obj)
            return ToolResultPreview.Summary("$text\nПредупреждение: $warning", preserveFullText = true)
        }
        return preview ?: ToolResultPreview.Summary(specialSummary(toolName, obj) ?: genericSummary(obj))
    }
    return when (result) {
        is JsonArray -> {
            val errors = result.mapNotNull { (it as? JsonObject)?.let { item ->
                toolErrorMessage(toolName, item)?.let { reason -> item.withResultIdentity(reason) }
            } }
            if (errors.isNotEmpty()) ToolResultPreview.Error(errors.joinToString("\n"))
            else ToolResultPreview.Summary(if (result.isEmpty()) "Пусто" else "Элементов: ${result.size}")
        }
        is JsonPrimitive -> ToolResultPreview.Summary(result.contentOrNull?.trim().orEmpty().ifEmpty { "Готово" })
        else -> when {
            incompleteJson.containsMatchIn(outputText) -> ToolResultPreview.Summary("Результат получен не полностью. Проверьте результат перед повторным вызовом.", preserveFullText = true)
            else -> ToolResultPreview.Summary(outputText.trim())
        }
    }
}

/** MCP can return an error envelope followed by its original text/image parts. */
internal fun parseToolOutputJson(toolName: String, textParts: List<String>): JsonElement? {
    fun parse(text: String) = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    parse(textParts.joinToString("\n"))?.let { return it }
    // Only promote an error envelope: unrelated successful JSON fragments are separate content.
    return (textParts.asSequence() + textParts.asSequence().flatMap { it.lineSequence() })
        .mapNotNull { parse(it) as? JsonObject }
        .firstOrNull { ownFailure(toolName, it) }
}

private fun commandPreview(toolName: String, obj: JsonObject, fullScreen: Boolean = false): ToolResultPreview? {
    if (obj.string("mode") == "interactive") return ToolResultPreview.Summary(obj.string("note") ?: "Открыта сессия Termux",
        preserveFullText = obj.string("note") != null)
    obj.string("screen")?.let { screen ->
        val lines = screen.lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        return if (lines.all { it.isBlank() }) {
            val partial = ToolResultPreview.Command(
                obj.string("partial_stdout").orEmpty().trimEnd(),
                obj.string("partial_stderr").orEmpty().trimEnd(),
                null,
            ).readableText().takeIf { it.isNotBlank() }
            ToolResultPreview.Summary(listOfNotNull(obj.string("note") ?: "Экран пуст", partial).joinToString("\n"),
                preserveFullText = obj.string("note") != null || partial != null)
        }
        else ToolResultPreview.Command((if (fullScreen) lines else lines.takeLast(4)).joinToString("\n"), "", null)
    }
    val stdout = obj.string("stdout") ?: obj.string("partial_stdout")
    val stderr = obj.string("stderr") ?: obj.string("partial_stderr")
    val exit = (obj["exit_code"] ?: obj["exitCode"])?.let { (it as? JsonPrimitive)?.intOrNull }
    val nonZero = exit?.takeIf { it != 0 }
    val reason = nonZero?.let { code ->
        listOf("message", "reason", "note", "recovery").mapNotNull { obj.string(it)?.readableRussian() }
            .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            ?: if (toolName in foregroundExitReasonTools) commandExitFailureReason(code, stderr.orEmpty(), when (toolName) {
                "workspace_shell" -> CommandEnvironment.WORKSPACE
                "termux_run_command" -> CommandEnvironment.TERMUX
                "root_exec" -> CommandEnvironment.ANDROID_ROOT
                else -> CommandEnvironment.SSH
            }) else null
    }
    if (stdout == null && stderr == null) {
        val pid = (obj["pid"] as? JsonPrimitive)?.longOrNull
        return when {
            pid != null -> ToolResultPreview.Summary("Запущено в фоне · PID $pid")
            nonZero != null -> ToolResultPreview.Command("", "", nonZero, reason)
            else -> null
        }
    }
    val out = stdout.orEmpty().trimEnd()
    val err = stderr.orEmpty().trimEnd()
    if (out.isBlank() && err.isBlank() && nonZero == null) return ToolResultPreview.Summary("Без вывода")
    return ToolResultPreview.Command(out.trimStart('\n'), err.trimStart('\n'), nonZero, reason)
}

private val failureStatuses = setOf("error", "failed", "failure", "blocked", "denied", "cancelled", "timeout",
    "timed_out", "partial", "partial_failure", "native_error", "android_error")
private val currentResultKeys = setOf("failed", "errors", "failures", "results", "items", "files", "operations", "result")
private val warningKeys = setOf("warning", "warn", "warnings", "workspace_copy_error")
private val reasonKeys = listOf("message", "reason", "detail", "recovery", "hint")

private fun meaningfulError(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonPrimitive -> value.contentOrNull?.trim()?.let { it.isNotEmpty() && it !in setOf("false", "0", "null") } == true
    is JsonObject -> value.isNotEmpty()
    is JsonArray -> value.isNotEmpty()
}

internal fun toolErrorCode(obj: JsonObject): String? = when (val error = obj["error"]) {
    is JsonObject -> error.string("code") ?: error.string("message")
    is JsonPrimitive -> error.contentOrNull?.takeIf { meaningfulError(error) }
    else -> null
} ?: obj.string("code") ?: obj.string("error_kind")

private fun ownFailure(toolName: String, obj: JsonObject): Boolean {
    if (meaningfulError(obj["error"]) || obj.boolean("isError") == true || obj.boolean("is_error") == true) return true
    if (obj.string("status")?.lowercase() in failureStatuses || obj.boolean("timedOut") == true || obj.boolean("timed_out") == true) return true
    if (obj.boolean("ok") == false) return true
    if (obj.boolean("success") == false) {
        // A command's process exit belongs next to its useful stdout/stderr, not a generic error.
        return toolName !in COMMAND_OUTPUT_TOOL_NAMES || listOf("stdout", "stderr", "partial_stdout", "partial_stderr", "screen")
            .none { it in obj }
    }
    return false
}

private fun readableReasons(obj: JsonObject): List<String> = buildList {
    reasonKeys.forEach { key -> obj.string(key)?.readableRussian()?.let(::add) }
    when (val error = obj["error"]) {
        is JsonPrimitive -> error.contentOrNull?.readableRussian()?.let(::add)
        is JsonObject -> {
            addAll(readableReasons(error))
            error.string("text")?.readableRussian()?.let(::add)
        }
        is JsonArray -> error.forEach { (it as? JsonPrimitive)?.contentOrNull?.readableRussian()?.let(::add) }
        else -> Unit
    }
    if (obj.boolean("isError") == true || obj.boolean("is_error") == true) {
        (obj["content"] as? JsonArray)?.forEach { item ->
            (item as? JsonObject)?.string("text")?.readableRussian()?.let(::add)
        }
    }
}.distinct().let { reasons -> reasons.filter { reason -> reasons.none { it != reason && reason in it } } }

/** Russian reasons are complete: warnings about partial effects must survive every display path. */
internal fun toolErrorMessage(toolName: String, obj: JsonObject): String? {
    val errors = mutableListOf<String>()
    if (ownFailure(toolName, obj)) {
        val reasons = readableReasons(obj)
        val rawReasons = reasonKeys.mapNotNull { obj.string(it) } + listOfNotNull(toolErrorCode(obj))
        val reason = when {
            reasons.isNotEmpty() -> reasons.joinToString("\n")
            rawReasons.any { backgroundStartRefused.containsMatchIn(it) } -> if (toolName.startsWith("termux_"))
                "Termux не разрешает запуск из фона — дайте Termux разрешение «Поверх других приложений»"
                else "Android не разрешает запуск из фона"
            else -> localizedErrorCode(toolName, toolErrorCode(obj)) ?: if (obj.boolean("timedOut") == true || obj.boolean("timed_out") == true)
                "Команда не завершилась вовремя" else "Не удалось выполнить инструмент"
        }
        errors += reason
    }
    currentResultKeys.forEach { key ->
        when (val value = obj[key]) {
            is JsonArray -> value.forEach { item ->
                when (item) {
                    is JsonObject -> toolErrorMessage(toolName, item)?.let { errors += item.withResultIdentity(it) }
                    is JsonPrimitive -> if (key in setOf("failed", "errors", "failures") && meaningfulError(item))
                        errors += item.content.readableRussian() ?: localizedErrorCode(toolName, item.content) ?: "Не удалось выполнить операцию"
                    else -> Unit
                }
            }
            is JsonObject -> toolErrorMessage(toolName, value)?.let { errors += value.withResultIdentity(it) }
            is JsonPrimitive -> if (key in setOf("failed", "errors", "failures") && (value.longOrNull ?: 0) > 0)
                errors += "Не выполнено операций: ${value.content}"
            else -> Unit
        }
    }
    if (errors.isEmpty()) return null
    val successCount = (obj["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    val pieces = buildList {
        successCount?.let { add("Успешно: $it") }
        addAll(errors.distinct())
        toolWarningMessage(toolName, obj)?.let { add("Предупреждение: $it") }
        listOf("backup_path" to "Резервная копия", "created_path" to "Созданный файл").forEach { (key, label) ->
            obj.string(key)?.let { add("$label: $it") }
        }
        if (toolName in COMMAND_OUTPUT_TOOL_NAMES) (commandPreview(toolName, obj, fullScreen = true) as? ToolResultPreview.Command)
            ?.copy(reason = null)
            ?.readableText()?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return pieces.joinToString("\n")
}

private fun JsonObject.withResultIdentity(reason: String): String =
    listOf("path", "file_path", "name", "filename").firstNotNullOfOrNull { string(it) }
        ?.let { "${it.baseName()}: $reason" } ?: reason

internal fun toolWarningMessage(toolName: String, obj: JsonObject): String? {
    fun warning(value: JsonElement?): List<String> = when (value) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.flatMap(::warning)
        is JsonObject -> readableReasons(value).ifEmpty { listOf("Инструмент сообщил о предупреждении") }
        is JsonPrimitive -> if (!meaningfulError(value)) emptyList() else listOf(
            value.content.readableRussian() ?: localizedErrorCode(toolName, value.content) ?: "Инструмент сообщил о предупреждении")
    }
    val messages = warningKeys.flatMap { warning(obj[it]) } + currentResultKeys.flatMap { key ->
        val children = when (val value = obj[key]) {
            is JsonArray -> value.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(value)
            else -> emptyList()
        }
        children.mapNotNull { child -> toolWarningMessage(toolName, child)?.let { child.withResultIdentity(it) } }
    }
    return messages.distinct().joinToString("\n").takeIf { it.isNotEmpty() }
}

private val cyrillic = Regex("[А-Яа-яЁё]")
private val authoredRussianStart = Regex("^(?:[A-Za-z_][A-Za-z0-9_-]*\\s+)?[А-Яа-яЁё]+(?:\\s|[.!?:]|$)")
private val nativeExceptionClass = "(?:[a-zA-Z_$][\\w$]*\\.)*(?:[A-Z][\\w$]*(?:Exception|Error)|Exception|Error)\\b"
private val nativeException = Regex("$nativeExceptionClass|\\n\\s*at [\\w.$/]+\\(")
private val nativeExceptionPrefix = Regex(
    "^\\s*(?:\\[?(?:(?:Caused by|Suppressed):\\s*|Exception in thread\\s+\"[^\"]*\"\\s*)?" +
        "$nativeExceptionClass|at [\\w.$/]+\\([^)]*\\)(?:\\s|$))"
)
private val incompleteJson = Regex("^\\s*(?:\\{\\s*\"|\\[\\s*[\\{\\[\"])")

private fun String.readableRussian(): String? = trim().takeIf {
    cyrillic.containsMatchIn(it) && authoredRussianStart.containsMatchIn(it) && !nativeException.containsMatchIn(it)
}

private fun localizedErrorCode(toolName: String, code: String?): String? {
    if (code == null) return null
    if (toolName.startsWith("ssh_") || toolName in setOf("save_ssh_host", "delete_ssh_host", "list_ssh_hosts")) {
        sshErrors[code]?.let { return it }
    }
    if (toolName.startsWith("mcp_")) {
        when (code) {
            "auth_failed", "auth_required", "auth_forbidden" -> return "MCP: не удалось пройти авторизацию — проверьте учётные данные сервера"
            "connect_failed", "tcp_unreachable" -> return "MCP: не удалось подключиться к серверу"
        }
    }
    if (code == "not_found") return when {
        toolName.startsWith("workflow_") -> "Сценарий не найден"
        toolName in setOf("schedule_job", "list_jobs", "get_job_history", "delete_job", "pause_job", "resume_job", "trigger_job_now") -> "Задание не найдено"
        toolName.startsWith("mcp__") -> "Запрошенный объект MCP не найден"
        toolName.startsWith("mcp_") -> "Сервер MCP не найден"
        else -> "Объект не найден"
    }
    knownErrors[code]?.let { return it }
    return when {
        code.startsWith("missing_") -> "Не указаны обязательные параметры вызова"
        code.startsWith("invalid_") || code.startsWith("bad_") || code == "not_an_object" -> "Неверные параметры вызова — проверьте названия, типы и значения полей"
        code.startsWith("unknown_") -> "Запрошенный объект не найден — проверьте параметры вызова"
        code.startsWith("unsupported_") -> "Эта операция не поддерживается"
        else -> null
    }
}

private val backgroundStartRefused = Regex(
    "start (?:a )?(?:service|activit\\w*) from (?:the )?background|app is in background|" +
        "not allowed to start (?:service|activity)|over other apps|draw over|SYSTEM_ALERT_WINDOW|" +
        "background activity start",
    RegexOption.IGNORE_CASE,
)
private val knownErrors = mapOf(
    "termux_not_installed" to "Termux не установлен",
    "termux_permission_not_granted" to "Нет разрешения на запуск команд Termux — включите его в настройках ассистента",
    "termux_permission_denied" to "Termux не разрешает внешние команды — включите allow-external-apps в Termux",
    "timeout" to "Команда не завершилась вовремя",
    "command_timeout" to "Команда не завершилась вовремя",
    "root_not_granted" to "Нет root-доступа — разрешите его в менеджере root",
    "root_command_blocked" to "Команда заблокирована защитой",
    "blocked_by_safety_floor" to "Команда заблокирована защитой",
    "blocked_command" to "Команда заблокирована защитой",
    "root_interaction_required" to "Root-менеджер ждёт подтверждения на телефоне",
    "root_approval_required" to "Нужно подтверждение команды",
    "root_execution_indeterminate" to "Исход неизвестен: выполнение было прервано",
    "tool_execution_indeterminate" to "Исход неизвестен: выполнение было прервано",
    "process_interrupted" to "Прервано: приложение было закрыто. Часть действий могла выполниться. Проверьте результат и запустите действие вручную.",
    "root_cleanup_unconfirmed" to "Не удалось подтвердить завершение команды",
    "tool_not_found" to "Инструмент недоступен в этом ответе",
    "tool_cancelled_wall_clock" to "Истёк общий лимит времени на инструменты",
    "invalid_arguments" to "Неверные параметры вызова",
    "dispatch_failed" to "Не удалось запустить команду",
    "session_not_found" to "Сессия Termux завершена или не найдена — создайте новую сессию",
    "missing_session_id" to "Укажите идентификатор сессии Termux",
    "session_start_failed" to "Не удалось создать сессию Termux — проверьте Termux и tmux",
    "too_many_sessions" to "Достигнут предел сессий Termux — завершите одну из них",
    "tmux_installing" to "tmux ещё устанавливается — подождите и повторите вызов",
    "browser_not_open" to "Сначала откройте страницу в браузере",
    "browser_busy" to "Браузер занят другим действием — дождитесь его завершения",
    "browser_session_lost" to "Сессия браузера потеряна — откройте страницу заново",
    "browser_task_timeout" to "Истёк срок работы с браузером — откройте страницу заново",
    "browser_launch_failed" to "Не удалось открыть браузер — проверьте доступность приложения",
    "selector_not_found" to "Элемент страницы не найден — проверьте страницу и селектор",
    "not_a_select" to "Этот элемент страницы не является списком выбора",
    "no_enclosing_form" to "Для выбранного элемента не найдена форма отправки",
    "js_failed" to "Не удалось выполнить действие на странице — проверьте страницу и параметры",
    "js_error" to "Ошибка JavaScript в навыке — проверьте скрипт и параметры",
    "eval_dispatch_failed" to "Не удалось запустить JavaScript — откройте страницу заново",
    "readability_failed" to "Не удалось извлечь текст страницы — попробуйте другой способ чтения",
    "js_no_result" to "Страница не вернула результат JavaScript",
    "js_parse_failed" to "Не удалось разобрать ответ страницы",
    "nav_failed" to "Не удалось перейти на страницу — проверьте адрес и соединение",
    "tool_timeout" to "Инструмент не завершился вовремя — проверьте результат перед новым вызовом",
    "skill_not_found" to "Навык не найден — проверьте его название и установку",
    "skill_not_enabled" to "Навык отключён — включите его для текущего чата",
    "skill_file_not_found" to "Файл навыка не найден — проверьте путь внутри навыка",
    "skill_body_not_found" to "Содержимое навыка не найдено — установите навык заново",
    "skill_file_too_large" to "Файл навыка слишком велик — прочитайте нужную часть",
    "path_outside_skill" to "Путь выходит за пределы папки навыка",
    "script_not_found" to "Скрипт навыка не найден — проверьте имя файла",
    "script_failed" to "Не удалось выполнить скрипт навыка — проверьте скрипт и параметры",
    "script_timeout" to "Скрипт навыка не завершился вовремя",
    "secret_unavailable" to "Секрет навыка недоступен — проверьте его в настройках навыка",
    "fetch_failed" to "Не удалось загрузить навык — проверьте адрес и интернет",
    "html_response" to "По адресу открылась веб-страница — укажите адрес исходного файла SKILL.md",
    "empty_body" to "Файл навыка пуст — проверьте исходный файл",
    "body_too_large" to "Файл навыка превышает допустимый размер",
    "data_too_large" to "Входные данные превышают допустимый размер",
    "transcode_failed" to "Не удалось разобрать формат навыка — проверьте исходный файл",
    "save_failed" to "Не удалось сохранить навык — проверьте доступ к файлам",
    "unknown_run" to "Субагент с таким идентификатором не найден",
    "nested_dispatch_disabled" to "Субагент не может запускать других субагентов",
    "unknown_or_ambiguous_model" to "Модель не найдена или название неоднозначно — укажите точный идентификатор",
    "concurrency_limit" to "Достигнут предел одновременных субагентов — дождитесь завершения одного из них",
    "run_failed" to "Субагент завершился с ошибкой — проверьте задачу и настройки модели",
    "cancelled" to "Выполнение остановлено — уже выполненные действия не отменены",
    "feature_disabled" to "Функция отключена — включите её в настройках ассистента",
    "tool_not_allowed" to "Инструмент недоступен для этого запуска — проверьте разрешения",
    "conversation_not_allowed" to "Нет доступа к этому диалогу — используйте текущий диалог",
    "no_conversation" to "Текущий диалог недоступен — откройте его заново",
    "NO_PERMISSION" to "Нет разрешения — включите доступ к функции в настройках ассистента и Android",
    "INVALID_TIME" to "Неверное время — проверьте формат даты и часовой пояс",
    "INVALID_RANGE" to "Неверный диапазон — конец должен быть позже начала",
    "MISSING_REQUIRED" to "Не заполнены обязательные поля",
    "NO_CALENDAR" to "Нет доступного календаря для записи — добавьте календарь на устройстве",
    "INSERT_FAILED" to "Не удалось сохранить событие в календарь",
    "creation_cleanup_failed" to "Не удалось убрать созданный файл — проверьте выбранную папку",
    "source_delete_failed" to "Копия сохранена, но источник не удалось полностью удалить. Проверьте оба файла перед новым действием.",
    "restore_failed" to "Не удалось восстановить предыдущий файл — проверьте сохранённую резервную копию",
    "screenshot_unavailable" to "Снимок экрана недоступен — проверьте Root и состояние экрана",
    "write_failed" to "Не удалось записать файл — проверьте доступ и свободное место",
    "screen_permission_revoked" to "Автоматизация экрана отключена или её разрешение отозвано",
    "out_of_bounds" to "Координаты выходят за границы экрана — проверьте размеры экрана",
    "wrong_foreground_app" to "На экране другое приложение — откройте нужное приложение и повторите чтение экрана",
    "no_active_window" to "Нет доступного окна — разблокируйте экран и откройте нужное приложение",
    "no_match" to "Элемент экрана не найден — прочитайте экран заново",
    "nth_out_of_range" to "Элемента с таким номером нет — проверьте результаты поиска",
    "no_clickable_ancestor" to "Для выбранного элемента не найден доступный элемент для нажатия",
    "node_not_editable" to "В выбранный элемент нельзя вводить текст",
    "host_not_found" to "Сервер не найден — проверьте адрес и интернет",
    "request_timeout" to "Сервер не ответил вовремя — проверьте соединение и настройки таймаута",
    "auth_failed" to "Не удалось пройти авторизацию — проверьте учётные данные",
    "connect_failed" to "Не удалось подключиться к серверу — проверьте адрес и соединение",
    "schedule_failed" to "Не удалось создать задание — проверьте расписание и повторите попытку",
    "job_unavailable" to "Задание недоступно — проверьте, включено ли оно и доступен ли ассистент",
    "no_future_occurrence" to "У расписания нет будущих запусков — измените время или границы расписания",
    "mutation_failed" to "Не удалось изменить задание — проверьте его состояние",
    "persist_failed" to "Не удалось сохранить сценарий — повторите попытку",
    "trigger_unavailable" to "Условие запуска недоступно на этом устройстве",
    "workflow_chaining_disabled" to "Запуск сценария из другого сценария отключён",
)

private val sshErrors = mapOf(
    "auth_failed" to "SSH: неверный логин, пароль или ключ",
    "connect_failed" to "SSH: не удалось подключиться к серверу",
    "tcp_unreachable" to "SSH: сервер недоступен",
    "host_key_changed" to "SSH: ключ сервера изменился — проверьте сервер перед подтверждением нового ключа",
)

private fun specialSummary(toolName: String, obj: JsonObject): String? = when (toolName) {
    "check_token_usage" -> (obj["total_tokens"] as? JsonPrimitive)?.longOrNull?.let { total ->
        val cap = (obj["hard_cap"] as? JsonPrimitive)?.longOrNull ?: (obj["soft_cap"] as? JsonPrimitive)?.longOrNull
        val status = when (obj.string("status")) {
            "UNDER_SOFT" -> "в пределах лимита"
            "WARN" -> "близко к лимиту"
            "OVER_HARD" -> "лимит превышен"
            "NO_BUDGET" -> "лимит не задан"
            else -> null
        }
        listOfNotNull("Использовано ${compactTokenCount(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())} токенов",
            cap?.let { "лимит ${compactTokenCount(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())}" }, status)
            .joinToString(" · ")
    }
    "termux_session_kill" -> "Сессия закрыта"
    "send_sms" -> if (obj.boolean("success") == true) {
        val sent = (obj["parts_sent"] as? JsonPrimitive)?.longOrNull
        val delivery = if (obj.boolean("recipient_delivery_confirmed") == true) "Доставка получателю подтверждена"
            else "Доставка получателю не подтверждена"
        "SMS передано в сеть${sent?.let { " · частей: $it" }.orEmpty()}. $delivery."
    } else null
    else -> null
}

private fun genericSummary(obj: JsonObject): String {
    val pieces = mutableListOf<String>()
    fun add(text: String?) {
        val value = text?.oneLine()?.takeIf { it.isNotEmpty() } ?: return
        if (pieces.size < 3 && value !in pieces) pieces += value.take(SUMMARY_PIECE_MAX_CHARS)
    }
    listOf("path", "file_path", "destination", "dst", "file", "filename", "local_path", "remote_path")
        .firstNotNullOfOrNull { obj.string(it) }?.let { add(it.baseName()) }
    obj.string("url")?.let { add(it.shortUrl()) }
    add(obj.string("title") ?: obj.string("name"))
    obj.entries.firstOrNull { it.value is JsonArray && it.key !in warningKeys && it.key !in setOf("error", "failed", "errors", "failures") }?.let { (_, value) ->
        val size = (value as JsonArray).size
        add(if (size == 0) "ничего не найдено" else "найдено: $size")
    }
    listOf("count", "total", "deleted_count", "bytes_written")
        .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.longOrNull?.let { key to it } }
        ?.let { (key, value) -> add(if (key == "bytes_written") "${value} байт" else "всего: $value") }
    add(listOf("message", "note", "result", "status", "text", "content").firstNotNullOfOrNull { key -> obj.string(key)?.let { fieldValue(key, it) } }
        ?.firstLine())
    if (pieces.isEmpty()) {
        if ((obj["success"] as? JsonPrimitive)?.booleanOrNull == true) return "Готово"
        obj.entries.filter { it.value is JsonPrimitive && it.value != JsonNull }.take(2).forEach { (key, value) ->
            if (key !in hiddenResultFields) add("${fieldLabel(key)}: ${primitiveText(key, value as JsonPrimitive)}")
        }
    }
    return pieces.joinToString(" · ").ifEmpty { "Готово" }
}

internal fun ToolResultPreview.readableText(): String = when (this) {
    is ToolResultPreview.Error -> message
    is ToolResultPreview.Summary -> text
    is ToolResultPreview.Command -> listOfNotNull(
        stdout.takeIf { it.isNotBlank() }?.let { "Вывод команды:\n$it" },
        stderr.takeIf { it.isNotBlank() }?.let { "Сообщения команды:\n$it" },
        exitCode?.let { "Код завершения: $it" },
        reason,
    ).joinToString("\n")
}

/** Selectable full details: source text, files and terminal output retain their real line breaks. */
internal fun presentToolDetails(toolName: String, value: JsonElement, isResult: Boolean = true, failureContext: Boolean = false): String {
    fun primitive(key: String, item: JsonPrimitive, failed: Boolean): String =
        if (isResult && failed && nativeExceptionPrefix.containsMatchIn(item.content))
            "Не удалось получить результат части ответа сервера"
        else primitiveText(key, item)
    fun render(element: JsonElement, indent: String = "", failed: Boolean = failureContext): String = when (element) {
        JsonNull -> "не указано"
        is JsonPrimitive -> primitive("", element, failed)
        is JsonArray -> if (element.isEmpty()) "Пусто" else element.mapIndexed { index, item ->
            "$indent${index + 1}. ${render(item, "$indent  ", failed)}"
        }.joinToString("\n")
        is JsonObject -> {
            // Nested history/server results keep their identity and useful receipt fields.
            val ownError = isResult && ownFailure(toolName, element)
            val error = if (ownError) toolErrorMessage(toolName,
                JsonObject(element.filterKeys { it !in currentResultKeys })) else null
            val fields = element.entries.filter { (_, item) -> item != JsonNull }
                .filter { (key, _) -> !isResult || key !in hiddenResultFields }
                .filter { (key, _) -> error == null || key !in reasonKeys && key !in warningKeys }
                .joinToString("\n") { (key, item) ->
                    val label = fieldLabel(key)
                    when (item) {
                        is JsonPrimitive -> "$indent$label: ${primitive(key, item, failed || ownError)}"
                        else -> "$indent$label:\n${render(item, "$indent  ", failed || ownError)}"
                    }
                }.ifBlank { if (isResult) "Готово" else "Без параметров" }
            listOfNotNull(error, fields.takeUnless { error != null && it == "Готово" }).joinToString("\n")
        }
    }
    if (isResult && value is JsonObject) {
        if (toolName in COMMAND_OUTPUT_TOOL_NAMES) commandPreview(toolName, value, fullScreen = true)?.let { command ->
            return toolErrorMessage(toolName, value) ?: listOfNotNull(command.readableText(),
                toolWarningMessage(toolName, value)?.let { "Предупреждение: $it" }).joinToString("\n")
        }
    }
    return render(value)
}

/** An MCP failure envelope explains the error; its other content parts may describe partial effects. */
internal fun presentToolOutputTexts(toolName: String, textParts: List<String>): List<String> {
    val outputJson = parseToolOutputJson(toolName, textParts)
    val preview = presentToolResult(toolName, textParts.joinToString("\n"), outputJson)
    return buildList {
        if (preview is ToolResultPreview.Error) add(outputJson?.let { presentToolDetails(toolName, it) } ?: preview.message)
        textParts.forEach { text ->
            val partJson = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            if (preview is ToolResultPreview.Error && partJson == outputJson && partJson != null) return@forEach
            val readable = if (preview is ToolResultPreview.Error && nativeExceptionPrefix.containsMatchIn(text))
                "Не удалось получить результат части ответа сервера"
            else partJson?.let { presentToolDetails(toolName, it, failureContext = preview is ToolResultPreview.Error) }
                ?: presentToolResult(toolName, text, null)?.readableText()
            readable?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private val hiddenResultFields = setOf("error", "raw_error", "error_detail", "raw", "native_error_type", "stage", "backend",
    "android_result_code", "modem_error_code", "tools_available_this_turn", "error_kind", "code")

private val fieldLabels = mapOf(
    "success" to "Выполнено", "ok" to "Выполнено", "status" to "Состояние", "name" to "Название",
    "title" to "Заголовок", "description" to "Описание", "message" to "Сообщение", "reason" to "Причина",
    "recovery" to "Что сделать", "hint" to "Подсказка", "detail" to "Пояснение", "note" to "Примечание",
    "warning" to "Предупреждение", "warn" to "Предупреждение", "warnings" to "Предупреждения",
    "workspace_copy_error" to "Предупреждение о копии отчёта", "path" to "Путь", "file_path" to "Путь к файлу",
    "filename" to "Имя файла", "file" to "Файл", "files" to "Файлы", "entries" to "Элементы",
    "src" to "Источник", "from" to "Источник", "dst" to "Назначение", "to" to "Назначение", "destination" to "Назначение",
    "dst_dir" to "Папка назначения", "local_path" to "Локальный путь", "remote_path" to "Путь на сервере",
    "backup_path" to "Резервная копия", "created_path" to "Созданный файл", "url" to "Адрес", "uri" to "Адрес",
    "current_url" to "Текущая страница", "text" to "Текст", "content" to "Содержимое", "result" to "Результат",
    "results" to "Результаты", "failed" to "Не выполнено", "failures" to "Ошибки операций", "errors" to "Ошибки",
    "items" to "Элементы", "count" to "Количество", "total" to "Всего", "size_bytes" to "Размер в байтах",
    "size" to "Размер", "bytes_read" to "Прочитано байт", "bytes_written" to "Записано байт", "bytes_copied" to "Скопировано байт",
    "deleted_count" to "Удалено", "truncated" to "Показана часть результата", "exists" to "Существует",
    "is_directory" to "Папка", "is_content_uri" to "Файл из выбранной папки Android", "created" to "Создано",
    "encoding" to "Кодировка", "binary" to "Двоичный файл", "content_base64" to "Двоичные данные Base64",
    "command" to "Команда", "arguments" to "Аргументы", "executable" to "Программа", "cwd" to "Рабочая папка",
    "stdout" to "Вывод команды", "stderr" to "Сообщения команды", "partial_stdout" to "Частичный вывод команды",
    "partial_stderr" to "Частичные сообщения команды", "exit_code" to "Код завершения", "exitCode" to "Код завершения",
    "screen" to "Экран терминала", "input" to "Ввод", "session_id" to "Сессия", "sessions" to "Сессии",
    "session_created" to "Сессия создана", "send_text_result" to "Результат ввода команды", "send_enter_result" to "Результат нажатия Enter",
    "pid" to "Номер процесса", "process_id" to "Процесс", "host" to "Сервер", "port" to "Порт",
    "username" to "Пользователь", "id" to "Идентификатор", "server_id" to "Сервер", "servers" to "Серверы",
    "tools" to "Инструменты", "tool_name" to "Инструмент", "enabled" to "Включено", "enabled_for_assistant" to "Включено для чата",
    "needs_approval" to "Требует подтверждения", "required_permission" to "Нужное разрешение", "type" to "Тип",
    "runs" to "Запуски", "history" to "История", "run_history" to "История запусков", "jobs" to "Задания", "job" to "Задание",
    "last_run_error" to "Ошибка последнего запуска", "last_run_message" to "Последний запуск", "last_run_recovery" to "Что сделать после последнего запуска",
    "last_run_status" to "Состояние последнего запуска", "last_run_at_ms" to "Время последнего запуска, мс",
    "parts_total" to "Всего частей SMS", "parts_sent" to "Передано частей SMS", "parts_unknown" to "Частей SMS без подтверждения",
    "recipient_delivery_confirmed" to "Доставка получателю подтверждена", "delivery_status" to "Состояние доставки",
    "automatic_retry_allowed" to "Автоматический повтор разрешён", "overwrite" to "Заменить существующий файл",
    "recursive" to "Включая вложенные папки", "limit" to "Лимит", "query" to "Запрос", "pattern" to "Шаблон",
    "total_tokens" to "Использовано токенов", "input_tokens" to "Входные токены", "output_tokens" to "Выходные токены",
    "level" to "Уровень", "charging" to "Идёт зарядка",
)

private fun fieldLabel(key: String): String = fieldLabels[key] ?: "Поле «$key»"

private fun fieldValue(key: String, value: String): String = if (key in setOf("status", "delivery_status", "last_run_status")) when (value.lowercase()) {
    "ok", "success", "succeeded", "completed", "done" -> "Готово"
    "sent" -> "Передано в сеть"
    "delivered" -> "Доставлено"
    "unconfirmed" -> "Не подтверждено"
    "pending", "queued" -> "Ожидает"
    "running" -> "Выполняется"
    "scheduled" -> "Запланировано"
    "paused" -> "Приостановлено"
    "resumed" -> "Возобновлено"
    "deleted" -> "Удалено"
    "disabled" -> "Отключено"
    "connected" -> "Подключено"
    "connecting" -> "Подключается"
    "disconnected" -> "Отключено"
    "error", "failed", "failure", "delivery_error" -> "Ошибка"
    "blocked", "denied" -> "Заблокировано"
    "timeout", "timed_out" -> "Время ожидания истекло"
    "cancelled" -> "Остановлено"
    "partial", "partial_failure" -> "Выполнено частично"
    "native_error", "android_error" -> "Ошибка Android при отправке"
    "indeterminate" -> "Исход неизвестен"
    "under_soft" -> "В пределах лимита"
    "warn" -> "Близко к лимиту"
    "over_hard" -> "Лимит превышен"
    "no_budget" -> "Лимит не задан"
    else -> value
} else if (key == "last_run_error") value.readableRussian() ?: "Ошибка последнего запуска — проверьте историю и настройки"
else value

private fun primitiveText(key: String, value: JsonPrimitive): String = when {
    value == JsonNull -> "не указано"
    !value.isString && value.booleanOrNull != null -> if (value.booleanOrNull == true) "да" else "нет"
    else -> fieldValue(key, value.content)
}

// --- helpers ---------------------------------------------------------------------------------

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private val whitespace = Regex("\\s+")

private fun String.oneLine(): String = replace(whitespace, " ").trim()

private fun String.firstLine(): String = trim().lineSequence().first()

internal fun String.baseName(): String {
    val trimmed = trim().trimEnd('/')
    val decoded = trimmed.substringAfterLast("%2F").substringAfterLast("%3A")
    return decoded.substringAfterLast('/').ifEmpty { trimmed }
}

internal fun String.shortUrl(): String {
    val withoutScheme = trim().substringAfter("://")
    return withoutScheme.removePrefix("www.").trimEnd('/')
}
