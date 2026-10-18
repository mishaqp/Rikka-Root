package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val CHART_STYLES = listOf("line", "bar", "scatter")
private val AXIS_SCALES = listOf("linear", "log")
private const val MAX_SERIES = 12
private const val MAX_SERIES_POINTS = 2000
private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

internal fun buildChartDisplayTool(): Tool = Tool(
    name = "chart_display",
    description = """
        Показать линейный, столбчатый или точечный график прямо в чате.
        Для line/bar у каждого ряда нужны values, согласованные по позиции с x_axis.data;
        points запрещены. Для scatter нужны points с числовыми x/y, а values запрещены.
        В bar столбцы начинаются с 0: min/max осей недоступны. Для line/bar ось X содержит
        категории: её min/max и log недоступны; диапазон и log задавайте через y_axis.
        Подписи data допустимы только в x_axis для line/bar. Не более $MAX_SERIES рядов
        и $MAX_SERIES_POINTS значений или точек на ряд. Пользователь сразу увидит график;
        повторять исходные данные в ответе не нужно.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("style", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { CHART_STYLES.forEach { add(it) } })
                    put("description", "Вид графика: line — линейный, bar — столбчатый, scatter — точечный.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Необязательный заголовок графика над изображением.")
                })
                put("x_axis", buildAxisSchema("Настройки оси X: категории для line/bar или числовые координаты для scatter."))
                put("y_axis", buildAxisSchema("Настройки числовой оси Y: заголовок, диапазон и масштаб."))
                put("series", buildJsonObject {
                    put("type", "array")
                    put("description", "Ряды данных: от 1 до $MAX_SERIES; формат values или points зависит от style.")
                    put("minItems", 1); put("maxItems", MAX_SERIES)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Необязательное имя ряда для легенды, когда рядов несколько.")
                            })
                            put("color", buildJsonObject {
                                put("type", "string")
                                put("description", "Необязательный цвет #FF8800 или #F80; некоторые клиенты могут его игнорировать.")
                                put("pattern", HEX_COLOR_REGEX.pattern)
                            })
                            put("values", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "number") }); put("maxItems", MAX_SERIES_POINTS)
                                put(
                                    "description",
                                    "Числовые значения для line/bar, согласованные по позиции с x_axis.data. Не более $MAX_SERIES_POINTS значений; в scatter это поле запрещено."
                                )
                            })
                            put("points", buildJsonObject {
                                put("type", "array")
                                put("description", "Точки с числовыми координатами x/y для scatter: не более $MAX_SERIES_POINTS. Для line/bar используйте values.")
                                put("maxItems", MAX_SERIES_POINTS)
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("x", buildJsonObject { put("type", "number"); put("description", "Конечная числовая координата X; для логарифмической оси должна быть больше 0.") })
                                        put("y", buildJsonObject { put("type", "number"); put("description", "Конечная числовая координата Y; для логарифмической оси должна быть больше 0.") })
                                    })
                                    put("required", buildJsonArray {
                                        add("x")
                                        add("y")
                                    })
                                })
                            })
                        })
                    })
                })
            },
            required = listOf("style", "series")
        )
    },
    execute = { args ->
        val error = validateChartArgs(args.jsonObject)
        val payload = if (error != null) {
            buildJsonObject {
                put("error", "INVALID_CHART")
                put("message", error)
            }
        } else {
            buildJsonObject {
                put("success", true)
                put("message", "График показан пользователю.")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun buildAxisSchema(description: String): JsonObject = buildJsonObject {
    put("type", "object"); put("description", description)
    put("properties", buildJsonObject {
        put("title", buildJsonObject {
            put("type", "string")
            put("description", "Необязательный заголовок оси, обычно с единицей измерения.")
        })
        put("data", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Подписи категорий, например месяцев, по позициям значений рядов. " +
                "Допустимы только в x_axis для line/bar; число подписей должно совпадать с числом values каждого ряда.")
        })
        put("min", buildJsonObject {
            put("type", "number")
            put("description", "Нижняя граница отображаемого диапазона. Допустима в y_axis для line и на обеих осях scatter; должна быть меньше max. В bar недоступна.")
        })
        put("max", buildJsonObject {
            put("type", "number")
            put("description", "Верхняя граница отображаемого диапазона. Допустима в y_axis для line и на обеих осях scatter; должна быть больше min. В bar недоступна.")
        })
        put("scale", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { AXIS_SCALES.forEach { add(it) } })
            put("description", "Масштаб оси: linear — линейный (обычный), log — логарифмический. log допустим в y_axis и в x_axis только для scatter; значения и заданная min должны быть больше 0.")
        })
        put("format", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Формат подписей: f-стиль для чисел (например .1f), strftime для дат (например %Y-%m). " +
                    "Некоторые клиенты могут его игнорировать."
            )
        })
    })
}

// Numeric strings are accepted for compatibility with tool callers; infinities and NaN are never chart data.
private fun JsonPrimitive.finiteNumber(): Double? = doubleOrNull?.takeIf { it.isFinite() }

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.finiteNumber()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun validateChartArgs(params: JsonObject): String? {
    val style = params.string("style")
    if (style !in CHART_STYLES) return "Поле style должно быть одним из: ${CHART_STYLES.joinToString()}"

    val axes = mutableMapOf<String, JsonObject>()
    for (axisName in listOf("x_axis", "y_axis")) {
        val element = params[axisName] ?: continue
        val axis = element as? JsonObject ?: return "$axisName должен быть объектом"
        validateAxis(axisName, axis, style!!)?.let { return it }
        axes[axisName] = axis
    }

    val series = params["series"] as? JsonArray
    if (series.isNullOrEmpty()) return "series должен быть непустым массивом"
    if (series.size > MAX_SERIES) return "В series допускается не более $MAX_SERIES рядов"

    val xLabels = axes["x_axis"]?.get("data") as? JsonArray
    val xLog = axes["x_axis"]?.string("scale") == "log"
    val yLog = axes["y_axis"]?.string("scale") == "log"

    series.forEachIndexed { index, element ->
        val item = element as? JsonObject ?: return "series[$index] должен быть объектом"
        val color = item["color"]
        if (color != null) {
            val colorText = (color as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (colorText == null || !HEX_COLOR_REGEX.matches(colorText)) {
                return "series[$index].color должен быть цветом в формате '#F80' или '#FF8800'"
            }
        }

        if (style == "scatter") {
            if ("values" in item) return "Для точечного графика в series[$index] укажите points вместо values"
            val points = item["points"] as? JsonArray ?: return "Для точечного графика требуется массив series[$index].points"
            if (points.size > MAX_SERIES_POINTS) return "В series[$index].points допускается не более $MAX_SERIES_POINTS точек"
            points.forEachIndexed { pointIndex, point ->
                val obj = point as? JsonObject
                val x = obj?.number("x")
                val y = obj?.number("y")
                if (x == null || y == null) return "В series[$index].points[$pointIndex] координаты x и y должны быть конечными числами"
                if (xLog && x <= 0) return "series[$index].points[$pointIndex].x должен быть больше 0 для логарифмической оси X"
                if (yLog && y <= 0) return "series[$index].points[$pointIndex].y должен быть больше 0 для логарифмической оси Y"
            }
        } else {
            if ("points" in item) return "Для графика $style в series[$index] укажите values вместо points"
            val values = item["values"] as? JsonArray ?: return "Для графика $style требуется массив series[$index].values"
            if (values.size > MAX_SERIES_POINTS) return "В series[$index].values допускается не более $MAX_SERIES_POINTS значений"
            values.forEachIndexed { valueIndex, value ->
                val number = (value as? JsonPrimitive)?.finiteNumber()
                    ?: return "series[$index].values[$valueIndex] должен быть конечным числом"
                if (yLog && number <= 0) return "series[$index].values[$valueIndex] должен быть больше 0 для логарифмической оси Y"
            }
            if (xLabels != null && xLabels.size != values.size) {
                return "Число значений series[$index].values (${values.size}) не совпадает с числом подписей x_axis.data (${xLabels.size})"
            }
        }
    }
    return null
}

private fun validateAxis(axisName: String, axis: JsonObject, style: String): String? {
    val hasMin = "min" in axis
    val hasMax = "max" in axis
    // 折线图/柱状图的 X 轴是类目轴, 没有可映射的数值范围或对数刻度
    if (axisName == "x_axis" && style != "scatter" && (hasMin || hasMax || axis.string("scale") == "log")) {
        return "Для графика $style ось X содержит категории: x_axis.min/max и логарифмическая шкала недоступны. " +
            "Укажите диапазон и логарифмическую шкалу в y_axis"
    }
    if (style == "bar" && (hasMin || hasMax)) {
        return "$axisName.min/max недоступны для столбчатого графика: столбцы всегда начинаются от 0"
    }
    val min = axis.number("min")
    val max = axis.number("max")
    if (hasMin && min == null) return "$axisName.min должен быть конечным числом"
    if (hasMax && max == null) return "$axisName.max должен быть конечным числом"
    if (min != null && max != null && min >= max) return "$axisName.min должен быть меньше $axisName.max"

    val scale = axis["scale"]
    if (scale != null && axis.string("scale") !in AXIS_SCALES) {
        return "$axisName.scale должен быть одним из: ${AXIS_SCALES.joinToString()}"
    }
    if (axis.string("scale") == "log" && min != null && min <= 0) {
        return "$axisName.min должен быть больше 0 для логарифмической шкалы"
    }

    val data = axis["data"]
    // 标签只用于折线图/柱状图的类目 X 轴, 其余情况渲染时会被忽略
    if (data != null && (axisName == "y_axis" || style == "scatter")) {
        return if (axisName == "y_axis") {
            "y_axis.data недоступен: подписи категорий указываются в x_axis.data"
        } else {
            "Для точечного графика x_axis.data недоступен: ось X числовая, используйте points[].x"
        }
    }
    if (data != null) {
        val labels = data as? JsonArray ?: return "$axisName.data должен быть массивом строк"
        if (labels.any { (it as? JsonPrimitive)?.isString != true }) return "$axisName.data должен быть массивом строк"
    }
    return null
}
