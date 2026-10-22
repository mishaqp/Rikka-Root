package me.rerere.rikkahub.data.ai.tools.local

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.root.RootShellManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Only process launch is replaced: timeout selection and tool result handling stay real. */
class RootToolTimeoutTest {
    private val savedTimeout = TermuxRuntime.commandTimeoutMs
    @After fun restoreTimeout() { TermuxRuntime.commandTimeoutMs = savedTimeout }

    @Test fun `omitted root timeout uses the user command timeout at invocation`() = runBlocking {
        val timeouts = mutableListOf<Int>()
        val manager = mockk<RootShellManager>()
        coEvery { manager.exec(any(), capture(timeouts), any(), any()) } returns RootProcessResult(exitCode = 0)
        val tool = buildRootTool(manager)
        TermuxRuntime.commandTimeoutMs = 120_000
        tool.execute(buildJsonObject { put("command", "printf ok") })
        TermuxRuntime.commandTimeoutMs = 180_000
        tool.execute(buildJsonObject { put("command", "printf ok") })
        assertEquals(listOf(120_000, 180_000), timeouts)
    }

    @Test fun `explicit root timeout takes priority and root command ceiling is preserved`() = runBlocking {
        val timeouts = mutableListOf<Int>()
        val manager = mockk<RootShellManager>()
        coEvery { manager.exec(any(), capture(timeouts), any(), any()) } returns RootProcessResult(exitCode = 0)
        TermuxRuntime.commandTimeoutMs = 600_000
        val tool = buildRootTool(manager)
        tool.execute(buildJsonObject { put("command", "printf ok"); put("timeout_ms", 2_000) })
        tool.execute(buildJsonObject { put("command", "printf ok") })
        tool.execute(buildJsonObject { put("command", "printf ok"); put("timeout_ms", 600_000) })
        tool.execute(buildJsonObject { put("command", "printf ok"); put("timeout_ms", 1) })
        assertEquals(listOf(2_000, 300_000, 300_000, 1_000), timeouts)
    }

    @Test fun `timeout response retains partial streams and remains a failure`() = runBlocking {
        val manager = mockk<RootShellManager>()
        coEvery { manager.exec(any(), any(), any(), any()) } returns RootProcessResult(
            stdout = "started\n", stderr = "partial error\n", error = "command_timeout", reason = "Истекло время команды",
        )
        val output = buildRootTool(manager).execute(buildJsonObject { put("command", "sleep 40") })
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(JsonPrimitive("command_timeout"), result["error"])
        assertEquals(JsonPrimitive("started\n"), result["stdout"])
        assertEquals(JsonPrimitive("partial error\n"), result["stderr"])
        assertFalse(result.containsKey("exit_code"))
    }
}
