package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxResultIntegrityTest {
    @Test fun `failed verification cannot turn green merely by printing the expected marker`() = runTest {
        val context = TermuxCommandTestContext { _, _ -> TermuxTestReply(exitCode = 9, stdout = "RIKKAHUB_OK\n") }
        val result = TermuxIntegration.verify(context, timeoutMs = 1_000)

        assertTrue(result.toString(), result is TermuxIntegration.VerifyResult.OtherError)
        assertTrue((result as TermuxIntegration.VerifyResult.OtherError).message.contains("9"))
    }

    @Test fun `verification requires the expected marker line instead of a substring`() = runTest {
        val context = TermuxCommandTestContext { _, _ -> TermuxTestReply(stdout = "NOT_RIKKAHUB_OK\n") }
        val result = TermuxIntegration.verify(context, timeoutMs = 1_000)

        assertEquals(TermuxIntegration.VerifyResult.UnexpectedOutput("NOT_RIKKAHUB_OK\n"), result)
    }

    @Test fun `verification timeout does not invent an allow external apps diagnosis`() = runTest {
        val context = TermuxCommandTestContext { _, _ -> null }
        val result = TermuxIntegration.verify(context, timeoutMs = 1_000)

        assertTrue(result.toString(), result is TermuxIntegration.VerifyResult.OtherError)
        assertFalse((result as TermuxIntegration.VerifyResult.OtherError).message.contains("allow-external-apps"))
    }

    @Test fun `whitespace only stderr survives the complete command tool response`() = runTest {
        val stderr = " \t\n\n"
        val context = TermuxCommandTestContext { _, _ -> TermuxTestReply(stdout = "ok\n", stderr = stderr) }
        val parts = termuxRunCommandTool(context).execute(buildJsonObject { put("command", "printf 'ok\\n'; printf ' \\t\\n\\n' >&2") })
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

        assertEquals("0", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("ok\n", result["stdout"]?.jsonPrimitive?.content)
        assertEquals(stderr, result["stderr"]?.jsonPrimitive?.content)
    }
}
