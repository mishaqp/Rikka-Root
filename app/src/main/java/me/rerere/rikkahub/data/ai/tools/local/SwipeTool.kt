// Adapted from RikkaHub Agent (AGPL-3.0): root replaces the Accessibility backend.
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val DEFAULT_SWIPE_MS = 300L

private fun numOrNull(input: kotlinx.serialization.json.JsonElement, key: String): Double? =
    input.jsonObject[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }

fun swipeTool(
    service: RootScreenService,
): Tool = Tool(
    name = "swipe",
    description = "Провести жест между абсолютными координатами start_x/start_y и end_x/end_y в пикселях. Все координаты — конечные неотрицательные в пределах дисплея; duration_ms: 50–5000, по умолчанию 300. Для прокрутки конкретного контейнера предпочтительнее scroll. Нужен root; возвращает success, причину отказа при наличии и after. Проверяйте after.screen_changed перед следующим действием.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("start_x", buildJsonObject { put("type", "number"); put("description", "Горизонтальная координата начала жеста в пикселях, внутри экрана."); put("minimum", 0) })
                put("start_y", buildJsonObject { put("type", "number"); put("description", "Вертикальная координата начала жеста в пикселях, внутри экрана."); put("minimum", 0) })
                put("end_x", buildJsonObject { put("type", "number"); put("description", "Горизонтальная координата конца жеста в пикселях, внутри экрана."); put("minimum", 0) })
                put("end_y", buildJsonObject { put("type", "number"); put("description", "Вертикальная координата конца жеста в пикселях, внутри экрана."); put("minimum", 0) })
                put("duration_ms", buildJsonObject {
                    put("type", "integer"); put("description", "Продолжительность жеста в миллисекундах: 50–5000, по умолчанию 300."); put("minimum", 50); put("maximum", 5000); put("default", 300) })
            },
            required = listOf("start_x", "start_y", "end_x", "end_y")
        )
    },
    execute = { input ->
        screenArgumentError(input, "start_x" to "number", "start_y" to "number",
            "end_x" to "number", "end_y" to "number", "duration_ms" to "integer")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val sx = numOrNull(input, "start_x")
        val sy = numOrNull(input, "start_y")
        val ex = numOrNull(input, "end_x")
        val ey = numOrNull(input, "end_y")
        if (sx == null || sy == null || ex == null || ey == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите start_x, start_y, end_x и end_y — неотрицательные координаты свайпа.")
                    }.toString()
                )
            )
        }
        val duration = input.jsonObject["duration_ms"]?.jsonPrimitive?.longOrNull ?: DEFAULT_SWIPE_MS
        if (duration < 50L || duration > 5000L) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите duration_ms от 50 до 5000 миллисекунд.")
                    }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true,
            validateBeforeWake = { screenBoundsError(it, sx to sy, ex to ey) }) { svc ->
            withActionEnvelope(svc) { _ ->
                val dm = svc.displayMetrics
                if (coordsOutOfBounds(sx, sy, dm.widthPixels, dm.heightPixels) ||
                    coordsOutOfBounds(ex, ey, dm.widthPixels, dm.heightPixels)
                ) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "out_of_bounds")
                        put("display", buildJsonObject {
                            put("w", dm.widthPixels)
                            put("h", dm.heightPixels)
                        })
                    }
                }
                val ok = svc.swipe(sx, sy, ex, ey, duration)
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "gesture_cancelled_or_timeout")
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
