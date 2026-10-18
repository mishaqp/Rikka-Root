package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JavascriptRuntimeMessagesPart2Test {
    private suspend fun evaluate(code: String, timeoutMillis: Long = 2_000): JsonObject {
        val raw = evaluateJavascript(code, timeoutMillis)
        assertFalse(raw, raw.contains('\n'))
        return Json.parseToJsonElement(raw).jsonObject
    }
    private fun russianFailure(result: JsonObject) {
        assertTrue(result.toString(), result.containsKey("error"))
        val reason = listOf("message", "error").mapNotNull { result[it]?.jsonPrimitive?.content }.joinToString(" ")
        assertTrue(result.toString(), reason.contains(Regex("[А-Яа-яЁё]")))
    }

    @Test(timeout = 20_000) fun appOwnedNativeTimeoutHasRussianExplanationAndRuntimeStillWorks() = runBlocking {
        val result = evaluate("while(true) {}", 100)
        russianFailure(result)
        assertFalse(result.toString(), result.toString().contains("execution timed out"))
        assertEquals("2", evaluate("1+1")["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 20_000) fun appOwnedOutputLimitHasRussianExplanationWithoutReturningPartialValue() = runBlocking {
        val result = evaluate("'x'.repeat(2*1024*1024)")
        russianFailure(result)
        assertFalse(result.toString(), result.toString().contains("result exceeds output limit"))
        assertNull(result["result"])
    }

    @Test(timeout = 20_000) fun truncatedNativeLogsExposeBooleanAndRussianMarker() = runBlocking {
        val result = evaluate("for(let i=0;i<10000;i++) console.log('USER LOG'); 1")
        val logs = result.getValue("logs").jsonPrimitive.content
        assertEquals(JsonPrimitive(true), result["logs_truncated"])
        assertTrue(logs.length < 66_000)
        assertTrue(logs, logs.contains("USER LOG"))
        assertTrue(logs, logs.contains(Regex("[А-Яа-яЁё]")))
        assertFalse(logs, logs.contains("[Logs truncated]"))
        assertEquals(JsonPrimitive("1"), result["result"])
    }

    @Test(timeout = 20_000) fun externalScriptValuesLogsAndThrownTextStayExact() = runBlocking {
        val short = evaluate("console.log('USER English stdout'); 'USER English value'")
        assertEquals("[LOG] USER English stdout", short["logs"]?.jsonPrimitive?.content)
        assertEquals("USER English value", short["result"]?.jsonPrimitive?.content)
        assertNotEquals(JsonPrimitive(true), short["logs_truncated"])
        assertTrue(evaluate("throw new Error('USER English failure')").toString().contains("USER English failure"))
    }
}
