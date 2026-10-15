package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

internal fun commandExitFailureReason(exitCode: Int?): String? =
    exitCode?.takeIf { it != 0 }?.let {
        "Команда завершилась с ошибкой (код $it). Проверьте вывод и результат перед новым запуском."
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
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        (safFileCause(error) as? CancellationException)?.let { throw it }
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
