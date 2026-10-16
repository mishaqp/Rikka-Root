package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.InteractionsAPI
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyToolParametersRegressionTest {
    private val client = OkHttpClient()
    private val messages = listOf(UIMessage.user("Вызови инструмент"))
    private val expected = json.parseToJsonElement("""{"type":"object","properties":{}}""")

    @Test
    fun `Claude получает пустую object schema для null parameters`() {
        variants().forEach { params ->
            val method = ClaudeProvider::class.java.getDeclaredMethod(
                "buildMessageRequest", ProviderSetting.Claude::class.java, List::class.java,
                TextGenerationParams::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val body = method.invoke(ClaudeProvider(client), ProviderSetting.Claude(), messages, params, false) as JsonObject
            assertEquals(expected, body["tools"]!!.jsonArray.single().jsonObject["input_schema"])
        }
    }

    @Test
    fun `Google получает пустую object schema для null parameters`() {
        variants().forEach { params ->
            val method = GoogleProvider::class.java.getDeclaredMethod(
                "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
            ).apply { isAccessible = true }
            val body = method.invoke(GoogleProvider(client), messages, params) as JsonObject
            assertEquals(expected, body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!
                .jsonArray.single().jsonObject["parameters"])
        }
    }

    @Test
    fun `Interactions получает пустую object schema для null parameters`() {
        variants().forEach { params ->
            val body = InteractionsAPI(client).buildRequestBody(messages, params, false)
            assertEquals(expected, body["tools"]!!.jsonArray.single().jsonObject["parameters"])
        }
    }

    @Test
    fun `Responses получает пустую object schema для null parameters`() {
        variants().forEach { params ->
            val body = ResponseAPI(client).buildRequestBody(ProviderSetting.OpenAI(), messages, params, false)
            assertEquals(expected, body["tools"]!!.jsonArray.single().jsonObject["parameters"])
        }
    }

    @Test
    fun `ChatCompletions получает пустую object schema для null parameters`() {
        variants().forEach { params ->
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
                "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
                ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val body = method.invoke(ChatCompletionsAPI(client, KeyRoulette.default()), messages, params, ProviderSetting.OpenAI(), false) as JsonObject
            assertEquals(expected, body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"])
        }
    }

    private fun variants() = listOf(
        Tool(name = "test", description = "Тест без параметров", execute = { emptyList() }),
        Tool(name = "test", description = "Тест явного null", parameters = { null }, execute = { emptyList() }),
    ).map { tool ->
        TextGenerationParams(model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL)), tools = listOf(tool))
    }
}
