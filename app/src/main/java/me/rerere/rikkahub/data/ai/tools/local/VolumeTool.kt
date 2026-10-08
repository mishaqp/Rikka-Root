// Adapted from ExTV/rikkahub-agent, local/VolumeTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private val audioStreams = mapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "notification" to AudioManager.STREAM_NOTIFICATION,
    "alarm" to AudioManager.STREAM_ALARM,
    "voice_call" to AudioManager.STREAM_VOICE_CALL,
    "system" to AudioManager.STREAM_SYSTEM,
)

private fun streamSchema() = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { audioStreams.keys.forEach { add(it) } })
}

private fun volumePayload(manager: AudioManager, name: String, stream: Int) = buildJsonObject {
    val current = manager.getStreamVolume(stream)
    val max = manager.getStreamMaxVolume(stream)
    val min = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(stream) else 0
    put("stream", name); put("volume", current); put("min", min); put("max", max)
    put("percent", if (max > min) ((current - min).toLong() * 100 + (max - min) / 2) / (max - min) else 0)
}

fun getVolumeTool(context: Context): Tool = Tool(
    name = "get_volume",
    description = "Read an audio stream's volume, minimum, maximum and percentage (default stream: media).",
    parameters = { InputSchema.Obj(buildJsonObject { put("stream", streamSchema()) }) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val name = if (obj.containsKey("stream")) obj.textArgument("stream") else "media"
        val stream = audioStreams[name] ?: return@deviceToolResult deviceToolError("Неизвестный поток. Доступны media, ring, notification, alarm, voice_call, system.")
        val manager = context.getSystemService(AudioManager::class.java) ?: return@deviceToolResult deviceToolError("Служба звука недоступна.")
        volumePayload(manager, name!!, stream)
    } },
)

fun setVolumeTool(context: Context): Tool = Tool(
    name = "set_volume",
    description = "Set an audio stream's volume, 0-100 percent. ring/notification/system require Do Not Disturb access because system can be aliased to ring. media/alarm/voice_call do not. Reports the actual volume applied by Android.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("stream", streamSchema())
        put("percent", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 100) })
    }, required = listOf("stream", "percent")) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val name = obj.textArgument("stream")
        val stream = audioStreams[name] ?: return@deviceToolResult deviceToolError("Неизвестный поток. Доступны media, ring, notification, alarm, voice_call, system.")
        val percent = obj.integerArgument("percent")?.takeIf { it in 0..100 }
            ?: return@deviceToolResult deviceToolError("Укажите целое число percent от 0 до 100.")
        if (volumeNeedsDndAccess(name) && context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted != true) {
            return@deviceToolResult deviceToolError("Для громкости звонка, уведомлений и системного звука разрешите доступ к «Не беспокоить» в настройках функции «Громкость». На телефонах системный звук связан со звонком; медиагромкость доступна без этого доступа.", "android.permission.ACCESS_NOTIFICATION_POLICY")
        }
        val manager = context.getSystemService(AudioManager::class.java) ?: return@deviceToolResult deviceToolError("Служба звука недоступна.")
        if (manager.isVolumeFixed) return@deviceToolResult deviceToolError("Android использует фиксированную громкость на этом устройстве.")
        val min = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(stream) else 0
        val target = volumeStep(percent, min, manager.getStreamMaxVolume(stream))
        manager.setStreamVolume(stream, target, 0)
        val actual = volumePayload(manager, name!!, stream)
        buildJsonObject {
            actual.forEach { (key, value) -> put(key, value) }
            put("requested_percent", percent)
            put("success", manager.getStreamVolume(stream) == target)
            if (manager.getStreamVolume(stream) != target) put("note", "Android применил другой уровень громкости из-за системных ограничений или безопасного уровня звука.")
        }
    } },
)
