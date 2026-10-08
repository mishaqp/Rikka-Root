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
    val payload = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: SecurityException) {
        deviceToolError("Android запретил доступ. Проверьте разрешения этой функции в настройках ассистента.")
    } catch (_: Exception) {
        // Do not expose exception text, which may contain private input or device details.
        deviceToolError("Функция недоступна на устройстве или Android отклонил запрос.")
    }
    return listOf(UIMessagePart.Text(payload.toString()))
}

internal fun JsonObject.textArgument(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
