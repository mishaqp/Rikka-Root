package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class HttpToolSecretSanitizerTest {
    private val raw = """{"url":"https://example.test","headers":{"authorization":"Bearer private-auth","COOKIE":"a=private-cookie","Proxy-Authorization":"private-proxy","X-Custom-Token":"private-token","X-Service-Key":"private-key","Accept":"application/json"}}"""

    @Test fun `history masks sensitive headers and keeps ordinary arguments`() {
        val safe = protectToolArguments("http_request", raw, null, null)
        assertFalse(safe.contains("private-"))
        val headers = Json.parseToJsonElement(safe).jsonObject.getValue("headers").jsonObject
        assertEquals("application/json", headers.getValue("Accept").jsonPrimitive.content)
        assertEquals(HttpToolSecretSanitizer.MASK, headers.getValue("authorization").jsonPrimitive.content)
        assertEquals(safe, protectToolArguments("http_request", safe, null, null))
    }

    @Test fun `incomplete and malformed HTTP input cannot enter history`() {
        for (input in listOf("""{"headers":{"Authorization":"private-auth""", """{"headers":["private-auth"]}""")) {
            assertFalse(protectToolArguments("http_request", input, null, null).contains("private-auth"))
        }
    }

    @Test fun `only the new tool is masked and presentation preserves execution input`() {
        for (name in listOf("unrelated", "http_request_extra", "http_reques")) {
            assertEquals(raw, HttpToolSecretSanitizer.sanitize(name, raw))
        }
        val tool = UIMessagePart.Tool("id", "http_request", raw)
        val presentation = HttpToolSecretSanitizer.presentation(tool)
        assertFalse(presentation.input.contains("private-"))
        assertEquals(raw, tool.input)
        assertEquals(tool.toolCallId, presentation.toolCallId)
        assertEquals(tool.approvalState, presentation.approvalState)
    }

    @Test fun `nested workflow actions receive HTTP masking through existing pipeline`() {
        val workflow = """{"definition":{"actions":[{"tool":"http_request","args":$raw}]}}"""
        assertFalse(protectToolArguments("workflow_create", workflow, null, null).contains("private-"))
        val tool = UIMessagePart.Tool("id", "workflow_create", workflow)
        assertFalse(HttpToolSecretSanitizer.presentation(tool).input.contains("private-"))
        assertEquals(workflow, tool.input)
        val other = UIMessagePart.Tool("id", "workflow_create", """{"definition":{"actions":[{"tool":"ordinary","args":{"headers":{"Authorization":"ordinary-data"}}}]}}""")
        assertEquals(other, HttpToolSecretSanitizer.presentation(other))
    }

    @Test fun `partial workflow presentation cannot reveal HTTP secrets before the JSON closes`() {
        for (name in listOf("workflow_create", "workflow_update")) {
            for (httpName in listOf("http_request", "http_\\u0072equest")) {
                val input = """{"definition":{"actions":[{"tool":"$httpName","args":{"headers":{"Authorization":"private-streamed-auth"""
                val tool = UIMessagePart.Tool("id", name, input)
                assertFalse(HttpToolSecretSanitizer.presentation(tool).input.contains("private-streamed-auth"))
                assertEquals(input, tool.input)
            }
        }
    }

    @Test fun `query credentials and broader secret header names are masked`() {
        val input = """{"url":"https://example.test/a?q=cats&access_token=private-q&api%5Fkey=private-k#top","headers":{"X-Api-Secret":"private-s","X-Password":"private-p","Accept":"*/*"}}"""
        val safe = HttpToolSecretSanitizer.sanitize("http_request", input)
        assertFalse(safe, safe.contains("private-"))
        val url = Json.parseToJsonElement(safe).jsonObject.getValue("url").jsonPrimitive.content
        assertEquals("https://example.test/a?q=cats&access_token=${HttpToolSecretSanitizer.MASK}&api%5Fkey=${HttpToolSecretSanitizer.MASK}#top", url)
        assertEquals(safe, HttpToolSecretSanitizer.sanitize("http_request", safe))
    }
}
