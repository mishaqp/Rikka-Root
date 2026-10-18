// Adapted from ExTV/rikkahub-agent, local/BrightnessTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun getBrightnessTool(context: Context): Tool = Tool(
    name = "get_brightness",
    description = "Прочитать системную яркость экрана value (0–255) и автоматический режим is_auto_mode. Для изменения используйте set_brightness; инструмент только читает настройки.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    needsApproval = { true },
    execute = { deviceToolResult {
        val resolver = context.contentResolver
        buildJsonObject {
            put("value", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            put("is_auto_mode", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        }
    } },
)

fun setBrightnessTool(context: Context): Tool = Tool(
    name = "set_brightness",
    description = "Изменить системную яркость и отключить автоматический режим. Целое value ограничивается диапазоном 1–255, а не отклоняется за его границами. Нужен доступ Android «Изменение системных настроек». Для проверки используйте get_brightness. Результат содержит success, фактическое value и is_auto_mode=false.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("value", buildJsonObject { put("type", "integer"); put("description", "Целая яркость: будет ограничена диапазоном 1–255. Отключает автоматическую яркость; нужен доступ к системным настройкам.") })
    }, required = listOf("value")) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val value = (input as? JsonObject)?.integerArgument("value")
            ?: return@deviceToolResult deviceToolError("Укажите целое число value; оно будет ограничено диапазоном 1–255.")
        if (!Settings.System.canWrite(context)) return@deviceToolResult deviceToolError("Разрешите изменение системных настроек при включении «Яркость» в настройках ассистента.", "android.permission.WRITE_SETTINGS")
        val clamped = brightnessValue(value)
        val resolver = context.contentResolver
        val valueWritten = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
        val modeWritten = valueWritten && Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (!modeWritten) return@deviceToolResult deviceToolError("Android не применил яркость. Проверьте доступ к изменению системных настроек.")
        buildJsonObject { put("success", true); put("value", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)); put("is_auto_mode", false) }
    } },
)
