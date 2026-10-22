package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxSessionStartupIntegrityTest {
    @Test fun `denied tmux probe stops before package installation`() = runTest {
        val context = TermuxCommandTestContext { _, _ -> TermuxTestReply(err = 1, raw = "PermissionDenied") }
        val result = start(context)

        assertEquals("termux_permission_denied", result["error"]?.jsonPrimitive?.content)
        assertEquals("PermissionDenied", result["raw"]?.jsonPrimitive?.content)
        assertEquals(1, context.requests.size)
    }

    @Test fun `failed tmux transport probe retains its diagnostics and never starts installation`() = runTest {
        val context = TermuxCommandTestContext { _, _ ->
            TermuxTestReply(err = 7, raw = "Service failed", exitCode = 23, stdout = "partial probe", stderr = "probe diagnostic")
        }
        val result = start(context)

        assertEquals("termux_run_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("Service failed", result["raw"]?.jsonPrimitive?.content)
        assertEquals("23", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("partial probe", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals("probe diagnostic", result["partial_stderr"]?.jsonPrimitive?.content)
        assertEquals(1, context.requests.size)
    }

    @Test fun `tmux probe timeout stays a probe timeout and never becomes an installing status`() = runTest {
        val context = TermuxCommandTestContext { _, _ -> null }
        val result = start(context)

        assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
        assertEquals("partial before timeout\n", result["partial_stdout"]?.jsonPrimitive?.content)
        assertNull(result["exit_code"])
        assertEquals(1, context.requests.size)
    }

    @Test fun `unreliable shell probes cannot authorize a session or trigger package changes`() = runTest {
        val failures = listOf(
            TermuxTestReply(exitCode = 137),
            TermuxTestReply(exitCode = 1, stdout = "/bin/tmux\n"),
            TermuxTestReply(exitCode = 127, stderr = "sh: unavailable"),
            TermuxTestReply(exitCode = 0),
        )
        for (failure in failures) {
            val context = TermuxCommandTestContext { _, _ -> failure }
            val result = start(context)

            assertEquals(failure.toString(), "termux_run_failed", result["error"]?.jsonPrimitive?.content)
            assertEquals(failure.exitCode.toString(), result["exit_code"]?.jsonPrimitive?.content)
            assertEquals(1, context.requests.size)
            assertNull(result["success"])
        }
    }

    @Test fun `session listing errors do not become an empty successful inventory`() = runTest {
        val failures = listOf(
            TermuxTestReply(err = 1, raw = "PermissionDenied"),
            TermuxTestReply(err = 7, raw = "List service failed", stderr = "listing diagnostic"),
            TermuxTestReply(exitCode = 2, stderr = "cannot access tmux socket"),
        )
        for (failure in failures) {
            val context = TermuxCommandTestContext { argv, _ ->
                when {
                    argv[0].endsWith("/sh") -> TermuxTestReply(stdout = "/bin/tmux\n")
                    argv.getOrNull(1) == "list-sessions" -> failure
                    else -> TermuxTestReply()
                }
            }
            val result = start(context)

            assertEquals(failure.toString(), "session_list_failed", result["error"]?.jsonPrimitive?.content)
            assertEquals(2, context.requests.size)
            assertFalse(context.requests.any { it.getOrNull(1) == "new-session" })
            assertNull(result["success"])
        }
    }

    @Test fun `listing timeout retains partial output without starting another session`() = runTest {
        val context = TermuxCommandTestContext { argv, _ ->
            if (argv[0].endsWith("/sh")) TermuxTestReply(stdout = "/bin/tmux\n") else null
        }
        val result = start(context)

        assertEquals("session_list_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("partial before timeout\n", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals(2, context.requests.size)
        assertFalse(context.requests.any { it.getOrNull(1) == "new-session" })
    }

    @Test fun `confirmed missing tmux still installs once and checks the installed program`() = runTest {
        for (notFoundExit in listOf(1, 127)) {
            var probes = 0
            val context = TermuxCommandTestContext { argv, _ ->
                when {
                    argv[0].endsWith("/sh") -> if (probes++ == 0) TermuxTestReply(exitCode = notFoundExit)
                        else TermuxTestReply(stdout = "/bin/tmux\n")
                    argv.getOrNull(1) == "capture-pane" -> TermuxTestReply(stdout = "shell-ready\n")
                    else -> TermuxTestReply()
                }
            }
            val result = start(context)

            assertEquals("true", result["success"]?.jsonPrimitive?.content)
            assertEquals(2, probes)
            assertEquals(1, context.requests.count { it.lastOrNull() == "pkg install -y tmux" })
            assertEquals(1, context.requests.count { it.getOrNull(1) == "new-session" })
        }
    }

    @Test fun `a genuinely absent tmux server still permits creating the first session`() = runTest {
        val context = TermuxCommandTestContext { argv, _ ->
            when {
                argv[0].endsWith("/sh") -> TermuxTestReply(stdout = "/bin/tmux\n")
                argv.getOrNull(1) == "list-sessions" -> TermuxTestReply(exitCode = 1, stderr = "no server running on /tmp/tmux-test/default")
                else -> TermuxTestReply()
            }
        }
        val result = start(context)

        assertEquals("true", result["success"]?.jsonPrimitive?.content)
        assertEquals(1, context.requests.count { it.getOrNull(1) == "new-session" })
    }

    @Test fun `failed recheck cannot claim tmux ready merely because it printed a path`() = runTest {
        var probes = 0
        val context = TermuxCommandTestContext { argv, _ ->
            when {
                argv[0].endsWith("/sh") -> if (probes++ == 0) TermuxTestReply(exitCode = 1)
                    else TermuxTestReply(exitCode = 7, stdout = "/bin/tmux\n")
                argv.lastOrNull() == "pkg install -y tmux" -> TermuxTestReply(exitCode = 9, stderr = "package install failed")
                else -> TermuxTestReply()
            }
        }
        val result = start(context)

        assertEquals("tmux_install_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("9", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("package install failed", result["partial_stderr"]?.jsonPrimitive?.content)
        assertFalse(context.requests.any { it.getOrNull(1) == "new-session" })
        assertEquals(3, context.requests.size)
    }

    private suspend fun start(context: TermuxCommandTestContext): JsonObject = Json.parseToJsonElement(
        (termuxSessionStartTool(context).execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text,
    ).jsonObject
}
