package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** A parameterless Gemini call may omit args; the tool loop must see empty input, not "null". */
class GoogleFunctionCallArgsTest {
    private fun parse(part: String): UIMessagePart.Tool {
        val method = GoogleProvider::class.java.getDeclaredMethod("parseMessagePart", JsonObject::class.java, Int::class.java)
        method.isAccessible = true
        return method.invoke(GoogleProvider(OkHttpClient()), Json.parseToJsonElement(part).jsonObject, 0) as UIMessagePart.Tool
    }

    @Test fun `missing or null args become empty input`() {
        assertEquals("", parse("""{"functionCall":{"name":"time_info"}}""").input)
        assertEquals("", parse("""{"functionCall":{"name":"time_info","args":null}}""").input)
    }

    @Test fun `present args are kept`() {
        assertEquals("""{"city":"Москва"}""", parse("""{"functionCall":{"name":"weather","args":{"city":"Москва"}}}""").input)
    }
}
