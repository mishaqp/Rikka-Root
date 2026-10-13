package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

internal fun deviceToolError(message: String, permission: String? = null): JsonObject = buildJsonObject {
    put("error", message)
    permission?.let { put("required_permission", it) }
}

/** Revoked permissions and absent hardware must not crash an ongoing generation. */
internal suspend fun deviceToolResult(read: suspend () -> JsonObject): List<UIMessagePart> {
    return deviceToolParts { listOf(UIMessagePart.Text(read().toString())) }
}

internal suspend fun deviceToolParts(read: suspend () -> List<UIMessagePart>): List<UIMessagePart> {
    val error = try {
        return read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        deviceToolError("Android запретил доступ. Проверьте разрешения этой функции в настройках ассистента.")
    } catch (error: Exception) {
        deviceToolError(deviceToolFailureReason(error))
    }
    return listOf(UIMessagePart.Text(error.toString()))
}

private val authoredReason = Regex("[А-Яа-яЁё]")

/**
 * Reasons written in Russian by this app's own checks (for example "Нет записи в выбранной
 * папке. Сначала вызовите grant_directory_access.") are shown as is. Android and Java exception
 * texts are never exposed, since they may carry private input or device details; only their
 * kind is reported.
 */
internal fun deviceToolFailureReason(error: Throwable): String {
    val message = error.message?.trim()
    if (message != null && message.length <= 300 && authoredReason.containsMatchIn(message) &&
        message.none(Char::isISOControl)) return message
    return when (error) {
        is java.nio.file.AccessDeniedException -> "Нет доступа к файлу или папке."
        is java.io.FileNotFoundException ->
            if (message?.contains("EACCES") == true || message?.contains("Permission denied", ignoreCase = true) == true)
                "Нет доступа к файлу или папке." else "Файл или папка не найдены."
        is java.nio.file.NoSuchFileException -> "Файл или папка не найдены."
        is java.nio.file.FileAlreadyExistsException -> "Файл уже существует."
        is UnsupportedOperationException -> "Функция не поддерживается на этом устройстве."
        is java.io.IOException -> "Ошибка чтения или записи."
        else -> "Функция недоступна на устройстве или Android отклонил запрос."
    }
}

internal fun JsonObject.textArgument(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
