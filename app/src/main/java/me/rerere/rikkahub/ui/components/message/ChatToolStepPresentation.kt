package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * Presentation of one timeline step: a one-line title with the essence of the call and a short
 * preview of its result. Raw arguments and JSON stay in the details sheet; nothing here changes
 * the stored call, its permissions or its execution.
 */
internal sealed interface ToolResultPreview {
    /** Command output; [exitCode] is set only when it is not zero. */
    data class Command(val stdout: String, val stderr: String, val exitCode: Int?) : ToolResultPreview

    /** A human-readable reason in Russian. */
    data class Error(val message: String) : ToolResultPreview

    data class Summary(val text: String) : ToolResultPreview
}

internal val COMMAND_OUTPUT_TOOL_NAMES = setOf(
    "root_exec", "termux_run_command", "ssh_exec", "ssh_exec_saved", "workspace_shell",
    "workspace_background_output", "termux_session_start", "termux_session_send", "termux_session_read",
)

private const val TITLE_MAX_CHARS = 200
private const val SUMMARY_PIECE_MAX_CHARS = 80
private const val ERROR_MAX_CHARS = 200

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
        "mcp_add" -> s("name")?.let { "MCP: добавить $it" }
        "mcp_test" -> s("id")?.let { "MCP: проверка $it" }
        else -> remoteMcpHeadline(toolName)
    }
    return headline?.take(TITLE_MAX_CHARS)
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
    listOf("schedule_job", "list_jobs", "get_job_history", "pause_job", "resume_job", "delete_job",
        "trigger_job_now", "get_time_info").forEach { put(it, HugeIcons.Clock02) }
    listOf("tap", "long_press", "swipe", "scroll", "find_node", "click_node", "set_text", "global_action",
        "read_window_tree", "take_screenshot", "wake_screen").forEach { put(it, HugeIcons.CursorPointer01) }
    listOf("read_file", "write_text_file", "write_binary_file", "delete_file", "copy_file", "move_file",
        "batch_copy", "batch_move", "batch_delete", "file_info", "find_files", "list_files", "create_directory",
        "open_file").forEach { put(it, HugeIcons.Files02) }
    listOf("list_storage_volumes", "grant_directory_access", "list_granted_directories")
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
    val obj = outputJson as? JsonObject
    if (obj != null) {
        toolErrorMessage(toolName, obj)?.let { return ToolResultPreview.Error(it) }
        if (toolName in COMMAND_OUTPUT_TOOL_NAMES) commandPreview(obj)?.let { return it }
        specialSummary(toolName, obj)?.let { return ToolResultPreview.Summary(it) }
        return ToolResultPreview.Summary(genericSummary(obj))
    }
    return when (outputJson) {
        is JsonArray -> ToolResultPreview.Summary(if (outputJson.isEmpty()) "Пусто" else "Элементов: ${outputJson.size}")
        is JsonPrimitive -> ToolResultPreview.Summary(outputJson.contentOrNull?.trim().orEmpty().ifEmpty { "Готово" })
        else -> ToolResultPreview.Summary(outputText.trim())
    }
}

private fun commandPreview(obj: JsonObject): ToolResultPreview? {
    if (obj.string("mode") == "interactive") return ToolResultPreview.Summary(obj.string("note") ?: "Открыта сессия Termux")
    // Termux sessions return the terminal screen; its newest lines are at the bottom.
    obj.string("screen")?.let { screen ->
        val lines = screen.lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        return if (lines.all { it.isBlank() }) ToolResultPreview.Summary(obj.string("note") ?: "Экран пуст")
        else ToolResultPreview.Command(lines.takeLast(4).joinToString("\n"), "", null)
    }
    val stdout = obj.string("stdout")
    val stderr = obj.string("stderr")
    val exit = (obj["exit_code"] ?: obj["exitCode"])?.let { (it as? JsonPrimitive)?.intOrNull }
    if (stdout == null && stderr == null) {
        val pid = (obj["pid"] as? JsonPrimitive)?.longOrNull
        return when {
            pid != null -> ToolResultPreview.Summary("Запущено в фоне · PID $pid")
            exit != null && exit != 0 -> ToolResultPreview.Command("", "", exit)
            else -> null
        }
    }
    val out = stdout.orEmpty().trimEnd()
    val err = stderr.orEmpty().trimEnd()
    val nonZero = exit?.takeIf { it != 0 }
    if (out.isBlank() && err.isBlank() && nonZero == null) return ToolResultPreview.Summary("Без вывода")
    return ToolResultPreview.Command(out.trimStart('\n'), err.trimStart('\n'), nonZero)
}

/** Russian reason for a failed result, or null when the result is not an error. */
internal fun toolErrorMessage(toolName: String, obj: JsonObject): String? {
    val errorValue = obj["error"]
    val hasError = errorValue != null && errorValue != JsonNull && (errorValue as? JsonPrimitive)?.booleanOrNull != false
    val failedWithoutError = !hasError && (obj["success"] as? JsonPrimitive)?.booleanOrNull == false &&
        toolName !in COMMAND_OUTPUT_TOOL_NAMES
    if (!hasError && !failedWithoutError) return null
    val code = when (errorValue) {
        is JsonPrimitive -> errorValue.contentOrNull
        is JsonObject -> errorValue.string("message") ?: errorValue.string("code")
        else -> null
    }?.trim()
    val details = listOfNotNull(obj.string("recovery"), obj.string("message"), obj.string("detail"),
        obj.string("reason"), code)
    if (details.any { backgroundStartRefused.containsMatchIn(it) }) {
        return if (toolName.startsWith("termux_"))
            "Termux не разрешает запуск из фона — дайте Termux разрешение «Поверх других приложений»"
        else "Android не разрешает запуск из фона"
    }
    knownErrors[code]?.let { return it }
    val russian = details.firstOrNull { cyrillic.containsMatchIn(it) }
    val message = russian?.firstSentence() ?: code?.let { "Ошибка: $it" } ?: "Не выполнено"
    return message.oneLine().take(ERROR_MAX_CHARS)
}

private val backgroundStartRefused = Regex(
    "start (?:a )?(?:service|activit\\w*) from (?:the )?background|app is in background|" +
        "not allowed to start (?:service|activity)|over other apps|draw over|SYSTEM_ALERT_WINDOW|" +
        "background activity start",
    RegexOption.IGNORE_CASE,
)
private val cyrillic = Regex("[А-Яа-яЁё]")

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
    "root_cleanup_unconfirmed" to "Не удалось подтвердить завершение команды",
    "tool_not_found" to "Инструмент недоступен в этом ответе",
    "tool_cancelled_wall_clock" to "Истёк общий лимит времени на инструменты",
    "invalid_arguments" to "Неверные параметры вызова",
    "auth_failed" to "SSH: неверный логин, пароль или ключ",
    "connect_failed" to "SSH: не удалось подключиться к серверу",
    "tcp_unreachable" to "SSH: сервер недоступен",
    "host_key_changed" to "SSH: ключ сервера изменился",
    "dispatch_failed" to "Не удалось запустить команду",
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
    obj.entries.firstOrNull { it.value is JsonArray }?.let { (_, value) ->
        val size = (value as JsonArray).size
        add(if (size == 0) "ничего не найдено" else "найдено: $size")
    }
    listOf("count", "total", "deleted_count", "bytes_written")
        .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.longOrNull?.let { key to it } }
        ?.let { (key, value) -> add(if (key == "bytes_written") "${value} байт" else "всего: $value") }
    add(listOf("message", "note", "result", "status", "text", "content").firstNotNullOfOrNull { obj.string(it) }
        ?.firstLine())
    if (pieces.isEmpty()) {
        if ((obj["success"] as? JsonPrimitive)?.booleanOrNull == true) return "Готово"
        obj.entries.filter { it.value is JsonPrimitive && it.value != JsonNull }.take(2).forEach { (key, value) ->
            add("$key: ${(value as JsonPrimitive).content}")
        }
    }
    return pieces.joinToString(" · ").ifEmpty { "Готово" }
}

// --- helpers ---------------------------------------------------------------------------------

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private val whitespace = Regex("\\s+")

private fun String.oneLine(): String = replace(whitespace, " ").trim()

private fun String.firstLine(): String = trim().lineSequence().first()

private fun String.firstSentence(): String {
    val text = trim()
    val end = Regex("[.!?](\\s|$)").find(text)?.range?.first ?: return text
    return text.substring(0, end + 1).removeSuffix(".")
}

internal fun String.baseName(): String {
    val trimmed = trim().trimEnd('/')
    val decoded = trimmed.substringAfterLast("%2F").substringAfterLast("%3A")
    return decoded.substringAfterLast('/').ifEmpty { trimmed }
}

internal fun String.shortUrl(): String {
    val withoutScheme = trim().substringAfter("://")
    return withoutScheme.removePrefix("www.").trimEnd('/')
}
