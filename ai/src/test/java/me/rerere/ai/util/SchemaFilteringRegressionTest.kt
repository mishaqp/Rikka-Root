package me.rerere.ai.util

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SchemaFilteringRegressionTest {
    @Test
    fun `фильтрация ключевых слов сохраняет одноименные пользовательские свойства`() {
        val schema = json.parseToJsonElement("""{
            "type":"object",
            "properties":{
                "format":{"type":"string","format":"date"},
                "enum":{"type":"string","enum":["a","b"]},
                "const":{"type":"object","properties":{"format":{"type":"string","const":"a"}},"required":["format"]}
            },
            "required":["format","enum","const"]
        }""")
        val filtered = schema.removeElements(listOf("format", "enum", "const")).jsonObject
        val properties = filtered["properties"]!!.jsonObject
        assertEquals(setOf("format", "enum", "const"), properties.keys)
        assertFalse(properties.getValue("format").jsonObject.containsKey("format"))
        assertFalse(properties.getValue("enum").jsonObject.containsKey("enum"))
        val nested = properties.getValue("const").jsonObject["properties"]!!.jsonObject
        assertEquals(setOf("format"), nested.keys)
        assertFalse(nested.getValue("format").jsonObject.containsKey("const"))
        assertEquals(schema.jsonObject["required"], filtered["required"])
    }

    @Test
    fun `required очищается от отсутствующих свойств на каждом уровне`() {
        val schema = json.parseToJsonElement("""{
            "type":"object","properties":{"nested":{"type":"object","properties":{"value":{"type":"string"}},"required":["value","missing"]}},
            "required":["nested","missing"]
        }""")
        val filtered = schema.removeElements(listOf("format")).jsonObject
        assertEquals(json.parseToJsonElement("""["nested"]"""), filtered["required"]!!.jsonArray)
        assertEquals(json.parseToJsonElement("""["value"]"""), filtered["properties"]!!.jsonObject["nested"]!!.jsonObject["required"])
    }
}
