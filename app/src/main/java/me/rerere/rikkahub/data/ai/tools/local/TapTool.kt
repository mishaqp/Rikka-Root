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

private const val DEFAULT_LONG_PRESS_MS = 600L

private fun coordOrError(jsonObj: kotlinx.serialization.json.JsonElement, key: String): Double? {
    val v = jsonObj.jsonObject[key]?.jsonPrimitive?.doubleOrNull
    return if (v != null && v.isFinite() && v >= 0.0) v else null
}

fun tapTool(
    service: RootScreenService,
): Tool = Tool(
    name = "tap",
    description = "Нажать по абсолютным координатам x/y в пикселях основного экрана через проверенный root. Координаты должны быть конечными, неотрицательными и находиться в пределах текущего дисплея. Предпочитайте click_node с node_id из read_window_tree, если элемент присутствует в дереве. Возвращает success и after с текущим приложением, клавиатурой и screen_changed; проверяйте after перед следующим действием, вместо лишнего снимка экрана.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number"); put("description", "Абсолютная горизонтальная координата в пикселях: конечная, неотрицательная, внутри текущего экрана."); put("minimum", 0) })
                put("y", buildJsonObject {
                    put("type", "number"); put("description", "Абсолютная вертикальная координата в пикселях: конечная, неотрицательная, внутри текущего экрана."); put("minimum", 0) })
            },
            required = listOf("x", "y")
        )
    },
    execute = { input ->
        screenArgumentError(input, "x" to "number", "y" to "number")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val x = coordOrError(input, "x")
        val y = coordOrError(input, "y")
        if (x == null || y == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите x и y — неотрицательные координаты на экране.")
                    }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true,
            validateBeforeWake = { screenBoundsError(it, x to y) }) { svc ->
            withActionEnvelope(svc) { _ ->
                val dm = svc.displayMetrics
                if (coordsOutOfBounds(x, y, dm.widthPixels, dm.heightPixels)) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "out_of_bounds")
                        put("display", buildJsonObject {
                            put("w", dm.widthPixels)
                            put("h", dm.heightPixels)
                        })
                    }
                }
                val ok = svc.tap(x, y, 50L)
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "gesture_cancelled_or_timeout")
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun longPressTool(
    service: RootScreenService,
): Tool = Tool(
    name = "long_press",
    description = "Удерживать нажатие по абсолютным координатам x/y в пикселях через root. duration_ms: 100–5000, по умолчанию 600; координаты — конечные неотрицательные в пределах дисплея. Для короткого нажатия используйте click_node или tap. Результат содержит success и after; проверьте after.screen_changed, чтобы оценить результат.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("x", buildJsonObject {
                    put("type", "number"); put("description", "Абсолютная горизонтальная координата в пикселях: конечная, неотрицательная, внутри текущего экрана."); put("minimum", 0) })
                put("y", buildJsonObject {
                    put("type", "number"); put("description", "Абсолютная вертикальная координата в пикселях: конечная, неотрицательная, внутри текущего экрана."); put("minimum", 0) })
                put("duration_ms", buildJsonObject {
                    put("type", "integer"); put("description", "Время удержания в миллисекундах: 100–5000, по умолчанию 600."); put("minimum", 100); put("maximum", 5000); put("default", 600) })
            },
            required = listOf("x", "y")
        )
    },
    execute = { input ->
        screenArgumentError(input, "x" to "number", "y" to "number", "duration_ms" to "integer")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val x = coordOrError(input, "x")
        val y = coordOrError(input, "y")
        if (x == null || y == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите x и y — неотрицательные координаты на экране.")
                    }.toString()
                )
            )
        }
        val durationRaw = input.jsonObject["duration_ms"]?.jsonPrimitive?.longOrNull
            ?: DEFAULT_LONG_PRESS_MS
        if (durationRaw < 100L || durationRaw > 5000L) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "Укажите duration_ms от 100 до 5000 миллисекунд.")
                    }.toString()
                )
            )
        }
        val payload = service.withService(wakeBeforeSnapshot = true,
            validateBeforeWake = { screenBoundsError(it, x to y) }) { svc ->
            withActionEnvelope(svc) { _ ->
                val dm = svc.displayMetrics
                if (coordsOutOfBounds(x, y, dm.widthPixels, dm.heightPixels)) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "out_of_bounds")
                        put("display", buildJsonObject {
                            put("w", dm.widthPixels)
                            put("h", dm.heightPixels)
                        })
                    }
                }
                val ok = svc.tap(x, y, durationRaw)
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "gesture_cancelled_or_timeout")
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
