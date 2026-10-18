package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ExecutionRuntimeMessagesPart2Test {
    @Test fun browserMissingArgumentDetailsAreAuthoredInRussianWithExistingCodes() = runBlocking {
        val context = mockk<Context>()
        val tools = listOf(browserOpenTool(context), browserClickTool(), browserTypeTool(), browserScrollTool(),
            browserSubmitTool(), browserSelectTool(), browserPressKeyTool(), browserEvalJsTool(),
            browserWaitForTool(), browserClickAndReadTool(), browserDoneTool())
        for (tool in tools) {
            val result = envelope(tool.execute(JsonObject(emptyMap())))
            assertTrue(tool.name, result["error"]!!.jsonPrimitive.content.startsWith("missing_"))
            assertTrue("${tool.name}: ${result["detail"]}", Regex("[А-Яа-яЁё]").containsMatchIn(result["detail"]!!.jsonPrimitive.content))
            assertTrue(tool.name, result["message"]!!.jsonPrimitive.content.contains("Укажите"))
        }
    }

    @Test fun browserUnsupportedSchemeExplainsTheSameRejectionInRussian() = runBlocking {
        val result = envelope(browserOpenTool(mockk()).execute(buildJsonObject { put("url", "file:///private/example") }))
        assertEquals("scheme_not_allowed", result["error"]!!.jsonPrimitive.content)
        assertTrue(result.toString(), Regex("[А-Яа-яЁё]").containsMatchIn(result["detail"]!!.jsonPrimitive.content))
        assertTrue(result["detail"]!!.jsonPrimitive.content.contains("file"))
        assertTrue(result["message"]!!.jsonPrimitive.content.contains("http"))
    }

    @Test fun sshTruncationMarkerIsRussianAndRetainsTheRealUtf8PrefixAndOmittedByteCount() {
        val stream = BoundedOutputStream(5)
        stream.write("Ж🙂X".toByteArray())
        val result = stream.snapshot()
        assertTrue(result, result.startsWith("Ж\n"))
        assertTrue(result, result.contains("ещё 5 байт"))
        assertFalse(result, result.contains("truncated"))
        assertFalse(result, result.contains('\uFFFD'))
    }

    @Test fun sshResultReportsActualTruncationAndDoesNotSuggestBlindCommandReplay() {
        val output = SshCommandOutput()
        output.stdout.write("Ж".repeat(5000).toByteArray())
        output.stderr.write("unaltered remote diagnostic".toByteArray())
        val result = finishSsh(output)
        assertEquals(true, result["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, result["stdout_truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, result["stderr_truncated"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(result["stdout"]!!.jsonPrimitive.content.startsWith("Ж".repeat(4000)))
        assertEquals("unaltered remote diagnostic", result["stderr"]!!.jsonPrimitive.content)
        val recovery = result["recovery"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(recovery, Regex("[А-Яа-яЁё]").containsMatchIn(recovery))
        assertTrue(recovery, recovery.contains("не повтор", ignoreCase = true))
    }

    @Test fun sshFailurePartialOutputCarriesRealTruncationMetadata() {
        val output = SshCommandOutput()
        output.stderr.write("X".repeat(2500).toByteArray())
        val partial = output.partial()
        assertEquals(true, partial["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, partial["stderr_truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, partial["stdout_truncated"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(partial["partial_stderr"]!!.jsonPrimitive.content.startsWith("X".repeat(2000)))
    }

    @Test fun remotePrintedTruncationWordsDoNotBecomeAppMetadataOrChangeExternalBytes() {
        val raw = "remote: [truncated; 300 bytes more] вывод сокращён"
        val output = SshCommandOutput()
        output.stdout.write(raw.toByteArray())
        val result = finishSsh(output)
        assertEquals(raw, result["stdout"]!!.jsonPrimitive.content)
        assertFalse(result["truncated"]?.jsonPrimitive?.booleanOrNull == true)
        assertFalse(result.containsKey("recovery"))
    }

    private fun finishSsh(output: SshCommandOutput): JsonObject {
        val session = mockk<Session>()
        val channel = mockk<ChannelExec>(relaxed = true)
        every { session.openChannel("exec") } returns channel
        every { channel.isClosed } returns true
        every { channel.exitStatus } returns 0
        return runOnSession(session, "printf example", 1000, output = output)
    }
    private fun envelope(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
}
