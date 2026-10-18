// Adapted from ExTV/rikkahub-agent, local/VolumeTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    put("description", "Аудиопоток: media, ring, notification, alarm, voice_call или system.")
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
    description = "Прочитать фактическую громкость stream: media по умолчанию, ring, notification, alarm, voice_call или system. Возвращает volume, min, max и percent; диапазон уровней зависит от устройства. Для изменения используйте set_volume.",
    parameters = { InputSchema.Obj(buildJsonObject { put("stream", JsonObject(streamSchema() + ("default" to JsonPrimitive("media")))) }) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val name = if (obj.containsKey("stream")) obj.textArgument("stream") else "media"
        val stream = audioStreams[name] ?: return@deviceToolResult deviceToolError("Укажите stream: media, ring, notification, alarm, voice_call или system.")
        val manager = context.getSystemService(AudioManager::class.java) ?: return@deviceToolResult deviceToolError("Служба звука недоступна. Проверьте настройки звука Android и повторите запрос.")
        volumePayload(manager, name!!, stream)
    } },
)

fun setVolumeTool(context: Context): Tool = Tool(
    name = "set_volume",
    description = "Установить громкость stream по целому percent от 0 до 100. Для ring, notification и system нужен доступ «Не беспокоить»; media, alarm и voice_call его не требуют. Для чтения используйте get_volume. Возвращает запрошенный requested_percent и фактические volume/min/max/percent; success=false и note объясняют ограничение Android, а не подтверждают применение желаемого уровня.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("stream", streamSchema())
        put("percent", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 100); put("description", "Запрошенная громкость от 0 до 100 процентов; результат сообщает реально применённый уровень Android.") })
    }, required = listOf("stream", "percent")) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val name = obj.textArgument("stream")
        val stream = audioStreams[name] ?: return@deviceToolResult deviceToolError("Укажите stream: media, ring, notification, alarm, voice_call или system.")
        val percent = obj.integerArgument("percent")?.takeIf { it in 0..100 }
            ?: return@deviceToolResult deviceToolError("Укажите целое число percent от 0 до 100.")
        if (volumeNeedsDndAccess(name) && context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted != true) {
            return@deviceToolResult deviceToolError("Для громкости звонка, уведомлений и системного звука разрешите доступ к «Не беспокоить» в настройках функции «Громкость». На телефонах системный звук связан со звонком; медиагромкость доступна без этого доступа.", "android.permission.ACCESS_NOTIFICATION_POLICY")
        }
        val manager = context.getSystemService(AudioManager::class.java) ?: return@deviceToolResult deviceToolError("Служба звука недоступна. Проверьте настройки звука Android и повторите запрос.")
        if (manager.isVolumeFixed) return@deviceToolResult deviceToolError("Android использует фиксированную громкость. Измените уровень на подключённом аудиоустройстве.")
        val min = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(stream) else 0
        val target = volumeStep(percent, min, manager.getStreamMaxVolume(stream))
        manager.setStreamVolume(stream, target, 0)
        val actual = volumePayload(manager, name!!, stream)
        buildJsonObject {
            actual.forEach { (key, value) -> put(key, value) }
            put("requested_percent", percent)
            put("success", manager.getStreamVolume(stream) == target)
            if (manager.getStreamVolume(stream) != target) put("note", "Android применил другой уровень громкости из-за системных ограничений или безопасного уровня звука. Проверьте фактический уровень в результате и настройки звука Android.")
        }
    } },
)
