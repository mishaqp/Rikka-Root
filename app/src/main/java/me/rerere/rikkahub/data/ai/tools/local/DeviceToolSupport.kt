package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.ai.tools.ToolExecutionAbortedException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

internal enum class CommandEnvironment { GENERIC, WORKSPACE, TERMUX, ANDROID_ROOT, SSH }

private val missingCommandDiagnostic = Regex(
    "(?:^|:\\s*)([A-Za-z0-9_./+-]+):\\s*(?:command not found|not found|inaccessible or not found)\\s*$",
)
private val reversedMissingCommandDiagnostic = Regex("(?:^|:\\s*)command not found:\\s*([A-Za-z0-9_./+-]+)\\s*$")
private val installableCommandName = Regex("[A-Za-z0-9_][A-Za-z0-9_.+-]{0,79}")
private val joinedSleepCommand = Regex("sleep([0-9]+)")
private val joinedChmodCommand = Regex("chmod([0-7]{3,4})")
private val permissionDeniedDiagnostic = Regex("\\bPermission denied\\b", RegexOption.IGNORE_CASE)
private val dataAdbPath = Regex("""(?<![A-Za-z0-9_./-])/data/adb(?=/|$|[\s'":;|&()<>])""")
private const val literalDataAdbPath = "/data/adb(?:/[A-Za-z0-9_.+-]+)*/?"
private val singleDataAdbRead = Regex(
    """[ \t]*(?:[A-Za-z0-9_./+-]+/)?(cat|ls|head|tail|stat|wc|du)[ \t]+(?:-[A-Za-z]+[ \t]+)*(?:--[ \t]+)?($literalDataAdbPath|'$literalDataAdbPath'|"$literalDataAdbPath")[ \t]*""",
)

/** A pathless diagnostic needs one literal read target; ambiguous shell input is not evidence. */
private fun singleDataAdbReadCommand(command: String?): String? {
    val read = singleDataAdbRead.matchEntire(command.orEmpty()) ?: return null
    if (read.groupValues[2].trim('\'', '"').split('/').any { it == ".." }) return null
    return read.groupValues[1]
}

private fun missingCommandName(stderr: String): String? =
    stderr.take(8192).lineSequence().take(40).firstNotNullOfOrNull { line ->
        (missingCommandDiagnostic.find(line)?.groupValues?.get(1)
            ?: reversedMissingCommandDiagnostic.find(line)?.groupValues?.get(1))
            ?.substringAfterLast('/')?.takeIf(installableCommandName::matches)
    }

/** Only focused diagnostics may replace a historical generic explanation in the UI. */
internal fun specificCommandFailureHint(
    exitCode: Int?,
    stderr: String = "",
    environment: CommandEnvironment = CommandEnvironment.GENERIC,
    stdout: String = "",
    command: String? = null,
): String? {
    if (exitCode == null || exitCode == 0) return null
    if (exitCode == 127) {
        val name = missingCommandName(stderr)
        val spaced = joinedSleepCommand.matchEntire(name.orEmpty())?.let { "sleep ${it.groupValues[1]}" }
            ?: joinedChmodCommand.matchEntire(name.orEmpty())?.let { "chmod ${it.groupValues[1]}" }
        if (spaced != null) return "Команда $name не найдена (код 127). Возможно, пропущен пробел: $spaced. Проверьте исходную команду и результат перед новым запуском."
        if (name != null) return null
    }
    if (environment != CommandEnvironment.TERMUX) return null
    // stdout may contain an access diagnostic, but never supplies an installable command name.
    val deniedLines = sequenceOf(stderr, stdout)
        .flatMap { it.take(8192).lineSequence().take(40) }
        .filter(permissionDeniedDiagnostic::containsMatchIn).toList()
    val readCommand = singleDataAdbReadCommand(command)
    val deniedDataAdb = deniedLines.any(dataAdbPath::containsMatchIn) ||
        (readCommand != null && deniedLines.any { it.trim().equals("$readCommand: Permission denied", ignoreCase = true) })
    if (!deniedDataAdb) return null
    return "В Termux нет доступа к /data/adb (код $exitCode). Для чтения этого каталога нужны права root; используйте root_exec и проверьте результат."
}

/** Only a known shell diagnostic supplies a name; a compound input command is never guessed. */
internal fun commandExitFailureReason(
    exitCode: Int?,
    stderr: String = "",
    environment: CommandEnvironment = CommandEnvironment.GENERIC,
    stdout: String = "",
    command: String? = null,
): String? {
    if (exitCode == null || exitCode == 0) return null
    specificCommandFailureHint(exitCode, stderr, environment, stdout, command)?.let { return it }
    if (exitCode != 127) return "Команда завершилась с ошибкой (код $exitCode). Проверьте вывод и результат перед новым запуском."
    val name = missingCommandName(stderr)
    val reason = if (name != null) "Программа $name не установлена или недоступна в PATH (код 127)."
        else "Программа не найдена (код 127)."
    val installation = when (environment) {
        CommandEnvironment.WORKSPACE -> "Установите её в рабочем окружении${name?.let { ", например: apt install $it" } ?: " с помощью apt"}, или проверьте PATH."
        CommandEnvironment.TERMUX -> "Установите её в Termux${name?.let { ", например: pkg install $it" } ?: " с помощью pkg"}, или проверьте PATH."
        CommandEnvironment.ANDROID_ROOT -> "Установите версию для Android из доверенного источника или проверьте PATH root-оболочки."
        CommandEnvironment.SSH -> "Установите её на SSH-сервере через его пакетный менеджер или проверьте PATH на сервере."
        CommandEnvironment.GENERIC -> "Установите нужную программу в окружении команды или проверьте PATH."
    }
    return "$reason $installation"
}

/** Keep authored warnings while replacing only known app-generated historical hints. */
internal fun commandFailureSupplement(text: String, exitCode: Int, stderr: String, focusedHint: String): String? {
    var supplement = text.replace(focusedHint, "")
    if (exitCode == 127) {
        missingCommandName(stderr)?.let { name ->
            val legacyMissingReason = Regex(
                Regex.escape("Программа $name не установлена или недоступна в PATH (код 127).") +
                    "(?:\\s+Установите (?:её|версию|нужную программу)[^\\n]*?\\.)?",
            )
            supplement = supplement.replace(legacyMissingReason, "")
        }
    } else {
        commandExitFailureReason(exitCode)?.let { supplement = supplement.replace(it, "") }
    }
    return supplement.trim().takeIf { it.isNotEmpty() }
}

internal fun deviceToolError(message: String, permission: String? = null): JsonObject = buildJsonObject {
    put("error", message)
    permission?.let { put("required_permission", it) }
}

/** Revoked permissions and absent hardware must not crash an ongoing generation. */
internal suspend fun deviceToolResult(
    fileOperation: FileToolOperation? = null,
    read: suspend () -> JsonObject,
): List<UIMessagePart> {
    return deviceToolParts(fileOperation) { listOf(UIMessagePart.Text(read().toString())) }
}

internal suspend fun deviceToolParts(
    fileOperation: FileToolOperation? = null,
    read: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> {
    val error = try {
        return read()
    } catch (error: TimeoutCancellationException) {
        // A nested timeout may expire while the tool's caller is still active.
        currentCoroutineContext().ensureActive()
        fileOperation?.let { FileOperationDiagnostics.record(it, error) }
        deviceToolError(deviceToolFailureReason(error, fileOperation))
    } catch (error: CancellationException) {
        currentCoroutineContext().ensureActive()
        fileOperation?.let { FileOperationDiagnostics.record(it, error) }
        deviceToolError(deviceToolFailureReason(error, fileOperation))
    } catch (error: Exception) {
        if (error is ToolExecutionAbortedException) throw error
        (safFileCause(error) as? ToolExecutionAbortedException)?.let { throw it }
        if (safFileCause(error) is CancellationException) currentCoroutineContext().ensureActive()
        fileOperation?.let { FileOperationDiagnostics.record(it, error) }
        deviceToolError(if (error is SecurityException)
            "Android запретил доступ. Проверьте разрешения этой функции в настройках ассистента."
        else deviceToolFailureReason(error, fileOperation))
    }
    return listOf(UIMessagePart.Text(error.toString()))
}

private val authoredReason = Regex("^(?:[A-Za-z_][A-Za-z0-9_]*\\s+)?[А-Яа-яЁё]+(?:\\s|[.!?]$)")

/**
 * Reasons written in Russian by this app's own checks (for example "Нет записи в выбранной
 * папке. Сначала вызовите grant_directory_access.") are shown as is. Android and Java exception
 * texts may carry private input or device details. Known platform failures are classified
 * before considering an app-authored reason; Cyrillic inside a pathname is not a reason.
 */
internal fun deviceToolFailureReason(error: Throwable, fileOperation: FileToolOperation? = null): String {
    val cancelled = error as? CancellationException ?: safFileCause(error) as? CancellationException
    if (cancelled is TimeoutCancellationException) return "Операция не завершилась вовремя. Повторите попытку."
    if (cancelled != null) return "Выполнение инструмента отменено. Проверьте результат перед повторным вызовом."
    safFileCause(error)?.let { return safFileFailureReason(it, fileOperation) }
    val message = error.message?.trim()
    val platformReason = when (error) {
        is SecurityException -> "Android запретил доступ. Проверьте разрешения этой функции в настройках ассистента."
        is java.nio.file.AccessDeniedException -> "Нет доступа к файлу или папке. Проверьте разрешения и выбранную папку."
        is java.io.FileNotFoundException ->
            if (message?.contains("EACCES") == true || message?.contains("Permission denied", ignoreCase = true) == true)
                "Нет доступа к файлу или папке. Проверьте разрешения и выбранную папку."
            else "Файл или папка не найдены. Проверьте путь."
        is java.nio.file.NoSuchFileException -> "Файл или папка не найдены. Проверьте путь."
        is java.nio.file.FileAlreadyExistsException -> "Файл уже существует. Укажите другое имя или разрешите перезапись."
        is java.net.UnknownHostException -> "Сервер не найден. Проверьте адрес и подключение к интернету."
        is java.net.ConnectException -> "Не удалось подключиться к серверу. Проверьте адрес и подключение к интернету."
        is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Повторите попытку."
        is javax.net.ssl.SSLException -> "Не удалось установить защищённое соединение. Проверьте дату и время устройства и сертификат сервера."
        is kotlinx.serialization.SerializationException -> "Некорректный JSON. Проверьте формат и типы значений."
        is java.net.URISyntaxException -> "Некорректный адрес. Проверьте формат URI."
        is javax.crypto.BadPaddingException -> "Не удалось расшифровать данные. Проверьте ключ, входные данные и параметры шифрования."
        is java.security.InvalidKeyException -> "Ключ не подходит для этой операции. Проверьте ключ и его разрешённые назначения."
        is UnsupportedOperationException -> "Функция не поддерживается на этом устройстве. Выберите другой способ."
        else -> null
    }
    if (platformReason != null) return platformReason
    if (message != null && message.length <= 300 && authoredReason.containsMatchIn(message) &&
        message.none(Char::isISOControl)) return message
    return when (error) {
        is IllegalArgumentException -> "Некорректные параметры. Проверьте названия, типы и значения параметров."
        is java.io.IOException -> "Ошибка чтения или записи. Проверьте доступ и свободное место."
        else -> "Функция недоступна на устройстве или Android отклонил запрос. Проверьте настройки функции."
    }
}

/** Provider text, including a Russian filename, must never be mistaken for an app-authored reason. */
private fun safFileFailureReason(error: Throwable, operation: FileToolOperation?): String {
    val action = operation?.action ?: "Операция с файлом"
    val accessDenied = error is SecurityException || error is java.nio.file.AccessDeniedException ||
        error is java.io.FileNotFoundException &&
        (error.message?.contains("EACCES") == true || error.message?.contains("Permission denied", ignoreCase = true) == true)
    if (accessDenied) return "Android не разрешил ${operation?.permissionAction ?: "операцию с файлом"} в выбранной папке."
    val reason = when (error) {
        is java.io.FileNotFoundException, is java.nio.file.NoSuchFileException -> "файл или папка не найдены"
        is java.nio.file.FileAlreadyExistsException -> "файл уже существует; для замены укажите overwrite=true"
        is UnsupportedOperationException -> "Android не поддерживает эту операцию"
        is java.io.IOException -> "ошибка чтения или записи"
        else -> "Android отклонил запрос; тип ошибки указан в отчёте"
    }
    return "$action в выбранной папке: $reason."
}

internal fun JsonObject.textArgument(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
