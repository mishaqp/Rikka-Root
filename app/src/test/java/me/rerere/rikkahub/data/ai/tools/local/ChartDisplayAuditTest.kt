package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ChartDisplayAuditTest {
    private fun execute(args: String): JsonObject = runBlocking {
        val text = buildChartDisplayTool().execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        Json.parseToJsonElement(text.text).jsonObject
    }

    @Test fun `invalid chart values explain errors in Russian`() {
        val result = execute("""{"style":"line","series":[{"values":["not-a-number"]}]}""")
        assertEquals("INVALID_CHART", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["message"]!!.jsonPrimitive.content.any { it in 'А'..'я' })
    }

    @Test fun `scatter coordinates and axis ranges reject invalid numeric values`() {
        for (args in listOf(
            """{"style":"scatter","series":[{"points":[{"x":"NaN","y":2}]}]}""",
            """{"style":"scatter","series":[{"points":[{"x":1,"y":"Infinity"}]}]}""",
            """{"style":"line","y_axis":{"min":"invalid"},"series":[{"values":[1]}]}""",
            """{"style":"line","y_axis":{"max":"NaN"},"series":[{"values":[1]}]}"""
        )) assertEquals(args, "INVALID_CHART", execute(args)["error"]?.jsonPrimitive?.content)
    }

    @Test fun `nonfinite numbers cannot reach chart renderer`() {
        for (args in listOf(
            """{"style":"line","series":[{"values":[1e999]}]}""",
            """{"style":"line","series":[{"values":["NaN"]}]}""",
            """{"style":"line","series":[{"values":["1e999"]}]}""",
            """{"style":"scatter","series":[{"points":[{"x":1e999,"y":2}]}]}""",
            """{"style":"line","y_axis":{"max":1e999},"series":[{"values":[1]}]}"""
        )) assertEquals(args, "INVALID_CHART", execute(args)["error"]?.jsonPrimitive?.content)
    }

    @Test fun `valid numbers display a chart with Russian success message`() {
        val result = execute("""{"style":"line","series":[{"values":[1,2.5]}]}""")
        assertEquals(JsonPrimitive(true), result["success"])
        assertTrue(result["message"]!!.jsonPrimitive.content.any { it in 'А'..'я' })
    }

    @Test fun `unambiguous numeric strings remain accepted for values points and ranges`() {
        for (args in listOf(
            """{"style":"line","series":[{"values":["1","2.5"]}]}""",
            """{"style":"scatter","series":[{"points":[{"x":"1","y":"2.5"}]}]}""",
            """{"style":"line","y_axis":{"min":"0","max":"3"},"series":[{"values":[1,2]}]}"""
        )) assertEquals(args, JsonPrimitive(true), execute(args)["success"])
    }
}
