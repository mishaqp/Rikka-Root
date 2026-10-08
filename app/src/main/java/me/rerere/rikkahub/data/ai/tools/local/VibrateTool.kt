// Adapted from ExTV/rikkahub-agent, local/VibrateTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun vibrateTool(context: Context): Tool = Tool(
    name = "vibrate",
    description = "Vibrate once, for at most 5000 ms total. Provide duration_ms (default 500) or an alternating off/on pattern of 2-20 intervals, never both.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("duration_ms", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 5000) })
        put("pattern", buildJsonObject {
            put("type", "array"); put("minItems", 2); put("maxItems", 20)
            put("items", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 5000) })
        })
    }) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры вибрации должны быть объектом.")
        val timings = try { validateVibrationArguments(obj) } catch (_: IllegalArgumentException) {
            return@deviceToolResult deviceToolError("Укажите duration_ms от 1 до 5000 либо pattern из 2–20 целых неотрицательных интервалов. Суммарная длительность — до 5000 мс; нужен хотя бы один интервал вибрации.")
        }
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator == null || !vibrator.hasVibrator()) return@deviceToolResult deviceToolError("Вибрация на устройстве недоступна.")
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        buildJsonObject { put("success", true); put("total_duration_ms", timings.sum()) }
    } },
)
