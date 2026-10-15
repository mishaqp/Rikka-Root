package me.rerere.rikkahub.data.ai.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class McpRuntimeMessagesTest {
    @Before fun prepareMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test fun `calling unavailable server gives Russian explanation and recovery`() = runBlocking {
        val scope = AppScope().apply { cancel() }
        val manager = McpManager(mockk(), scope, mockk(), mockk())
        try {
            val serverId = Uuid.random()
            val message = (manager.callTool(serverId, "lookup", JsonObject(emptyMap())).single() as UIMessagePart.Text).text
            assertTrue(message, message.contains("MCP"))
            assertTrue(message, message.contains("Проверьте"))
            assertTrue(message, message.contains(serverId.toString()))
            assertFalse(message, message.contains("Failed to execute"))
            assertFalse(message, message.contains("No MCP session"))
        } finally {
            scope.cancel()
        }
    }

    @Test fun `SDK failure gives Russian recovery and preserves raw diagnostic and code`() = runBlocking {
        val scope = AppScope().apply { cancel() }
        val manager = McpManager(mockk(), scope, mockk(), mockk())
        val registry = mockk<McpSessionRegistry>()
        replaceRegistry(manager, registry)
        coEvery { registry.callTool(any(), any(), any()) } throws SocketTimeoutException("Read timed out")
        try {
            val message = (manager.callTool(Uuid.random(), "lookup", JsonObject(emptyMap())).single() as UIMessagePart.Text).text
            val result = Json.parseToJsonElement(message).jsonObject
            assertEquals("request_timeout", result.getValue("error").jsonPrimitive.content)
            assertEquals("Read timed out", result.getValue("raw_error").jsonPrimitive.content)
            val explanation = result.getValue("detail").jsonPrimitive.content
            assertTrue(explanation, explanation.contains("Проверьте") || explanation.contains("проверьте"))
            assertFalse(explanation, explanation.contains("Read timed out"))
        } finally {
            scope.cancel()
        }
    }

    @Test fun `SDK raw diagnostic still redacts credentials`() = runBlocking {
        val scope = AppScope().apply { cancel() }
        val manager = McpManager(mockk(), scope, mockk(), mockk())
        val registry = mockk<McpSessionRegistry>()
        replaceRegistry(manager, registry)
        coEvery { registry.callTool(any(), any(), any()) } throws
            SocketTimeoutException("Read timed out; Authorization: Bearer unique-mcp-secret")
        try {
            val message = (manager.callTool(Uuid.random(), "lookup", JsonObject(emptyMap())).single() as UIMessagePart.Text).text
            val result = Json.parseToJsonElement(message).jsonObject
            assertEquals("request_timeout", result.getValue("error").jsonPrimitive.content)
            assertTrue(result.getValue("raw_error").jsonPrimitive.content.contains("Read timed out"))
            assertFalse(message.contains("unique-mcp-secret"))
        } finally {
            scope.cancel()
        }
    }

    @Test fun `SDK cancellation keeps original cancellation instead of producing an error message`() = runBlocking {
        val scope = AppScope().apply { cancel() }
        val manager = McpManager(mockk(), scope, mockk(), mockk())
        val registry = mockk<McpSessionRegistry>()
        replaceRegistry(manager, registry)
        val cancellation = CancellationException("cancelled")
        coEvery { registry.callTool(any(), any(), any()) } throws cancellation
        try {
            val failure = runCatching { manager.callTool(Uuid.random(), "lookup", JsonObject(emptyMap())) }.exceptionOrNull()
            assertSame(cancellation, failure)
        } finally {
            scope.cancel()
        }
    }

    @Test fun `remote error result adds Russian explanation and keeps all partial text parts`() = runBlocking {
        val scope = AppScope().apply { cancel() }
        val manager = McpManager(mockk(), scope, mockk(), mockk())
        val registry = mockk<McpSessionRegistry>()
        replaceRegistry(manager, registry)
        val rawError = "Remote request failed: E_REMOTE"
        val partialResult = "Partial rows: 3"
        val result = mockk<CallToolResult>()
        every { result.isError } returns true
        every { result.content } returns listOf(TextContent(rawError), TextContent(partialResult))
        coEvery { registry.callTool(any(), any(), any()) } returns result
        try {
            val parts = manager.callTool(Uuid.random(), "lookup", JsonObject(emptyMap()))
            val envelope = Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject
            assertEquals("mcp_tool_error", envelope.getValue("error").jsonPrimitive.content)
            val explanation = envelope.getValue("detail").jsonPrimitive.content
            assertTrue(explanation, explanation.contains("Проверьте"))
            assertFalse(explanation, explanation.contains("Remote request failed"))
            assertTrue(envelope.getValue("raw_error").jsonPrimitive.content.contains("E_REMOTE"))
            assertEquals(listOf(rawError, partialResult), parts.drop(1).map { (it as UIMessagePart.Text).text })
        } finally {
            scope.cancel()
        }
    }

    @Test fun `successful MCP result keeps original external text without an error envelope`() = runBlocking {
        val scope = AppScope().apply { cancel() }
        val manager = McpManager(mockk(), scope, mockk(), mockk())
        val registry = mockk<McpSessionRegistry>()
        replaceRegistry(manager, registry)
        val rawText = "External data: processing completed"
        val result = mockk<CallToolResult>()
        every { result.isError } returns false
        every { result.content } returns listOf(TextContent(rawText))
        coEvery { registry.callTool(any(), any(), any()) } returns result
        try {
            val parts = manager.callTool(Uuid.random(), "lookup", JsonObject(emptyMap()))
            assertEquals(listOf(rawText), parts.map { (it as UIMessagePart.Text).text })
        } finally {
            scope.cancel()
        }
    }

    private fun replaceRegistry(manager: McpManager, registry: McpSessionRegistry) {
        McpManager::class.java.getDeclaredField("sessionRegistry").apply {
            isAccessible = true
            set(manager, registry)
        }
    }
}
