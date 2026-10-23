package me.rerere.rikkahub.miniapps

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MiniAppFetchTest {
    private val app = MiniApp("test", "Test", File("."), network = listOf("api.example.com"))
    @Test fun redirectsCannotLeaveManifestAndClientDoesNotAutoFollow() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen.add(chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://evil.example/").body("".toResponseBody()).build()
        }.build()
        try { MiniAppFetch(client).fetch(app, buildJsonObject { put("url", "https://api.example.com/") }); fail("redirect allowed") }
        catch (e: MiniAppException) { assertEquals("host_not_allowed", e.code) }
        assertEquals(listOf("api.example.com"), seen)
    }
    @Test fun convertsPostRedirectAndReturnsJsonEnvelope() = runBlocking {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            methods.add(chain.request().method)
            val redirect = methods.size == 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (redirect) 303 else 201).message("OK")
                .apply { if (redirect) header("Location", "/done") }
                .body((if (redirect) "" else "hello").toResponseBody()).build()
        }.build()
        val result = MiniAppFetch(client).fetch(app, Json.parseToJsonElement("""{"url":"https://api.example.com/start","method":"POST","body":"x"}""").jsonObject)
        assertEquals(listOf("POST", "GET"), methods)
        assertEquals(201, result["status"]!!.jsonPrimitive.int)
        assertEquals("hello", result["body"]!!.jsonPrimitive.content)
    }
    @Test fun boundsResponseEvenWithoutContentLength() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ByteArray(MiniAppFetch.MAX_RESPONSE_BYTES + 1).toResponseBody()).build() }.build()
        try { MiniAppFetch(client).fetch(app, buildJsonObject { put("url", "https://api.example.com/") }); fail("unbounded body") }
        catch (e: MiniAppException) { assertEquals("response_too_large", e.code) }
    }
}
