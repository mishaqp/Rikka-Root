package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GoogleSchemaFilteringRegressionTest {
    @Test
    fun `Google сохраняет параметры с именами format enum const и удаляет неизвестные required`() {
        val properties = json.parseToJsonElement("""{
            "format":{"type":"string","format":"date"},
            "enum":{"type":"string","enum":["a","b"]},
            "const":{"type":"string","const":"a"}
        }""").jsonObject
        val tool = Tool(
            name = "test", description = "Пользовательские имена",
            parameters = { InputSchema.Obj(properties, listOf("format", "enum", "const", "missing")) },
            execute = { emptyList() },
        )
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        val body = method.invoke(GoogleProvider(OkHttpClient()), listOf(UIMessage.user("Тест")),
            TextGenerationParams(model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL)), tools = listOf(tool)))
            as kotlinx.serialization.json.JsonObject
        val parameters = body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!
            .jsonArray.single().jsonObject["parameters"]!!.jsonObject
        assertEquals(setOf("format", "enum", "const"), parameters["properties"]!!.jsonObject.keys)
        parameters["properties"]!!.jsonObject.values.forEach { property ->
            assertFalse(property.jsonObject.keys.any { it in setOf("format", "enum", "const") })
        }
        assertEquals(json.parseToJsonElement("""["format","enum","const"]"""), parameters["required"])
    }
}
