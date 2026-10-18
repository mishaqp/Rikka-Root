package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxSessionFailureMessageTest {
    @Test fun `start explains denied integration instead of tmux installation`() = runBlocking {
        val result = envelope(termuxSessionStartTool(context(err = 1, message = "PermissionDenied")).execute(JsonObject(emptyMap())))
        assertEquals("termux_permission_denied", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("allow-external-apps"))
        assertFalse(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("pkg install"))
    }

    @Test fun `kill failure does not claim success and keeps technical result`() = runBlocking {
        val result = envelope(termuxSessionKillTool(context(err = 7, message = "Service failed", stdout = "partial output")).execute(sessionInput()))
        assertEquals("session_kill_failed", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("rk_build", result["session_id"]?.jsonPrimitive?.content)
        assertEquals("Service failed", result["raw"]?.jsonPrimitive?.content)
        assertEquals("partial output", result["partial_stdout"]?.jsonPrimitive?.content)
        assertFalse(result["success"]?.jsonPrimitive?.content == "true")
    }

    @Test fun `list failure does not report empty successful session list`() = runBlocking {
        val result = envelope(termuxSessionListTool(context(err = 7, message = "Service failed")).execute(JsonObject(emptyMap())))
        assertEquals("session_list_failed", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("Проверьте"))
        assertEquals("Service failed", result["raw"]?.jsonPrimitive?.content)
        assertFalse(result["success"]?.jsonPrimitive?.content == "true")
    }

    @Test fun `send failure keeps the failing stage and partial output`() = runBlocking {
        val result = envelope(termuxSessionSendTool(context(err = 7, message = "Service failed", stdout = "partial output")).execute(buildJsonObject {
            put("session_id", "rk_build")
            put("input", "pwd")
            put("enter", false)
        }))
        assertEquals("send_failed", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("termux_session_read"))
        assertEquals("Service failed", result["raw"]?.jsonPrimitive?.content)
        assertEquals("partial output", result["partial_stdout"]?.jsonPrimitive?.content)
    }

    @Test fun `nonzero tmux exit identifies missing session with Russian action`() = runBlocking {
        val result = envelope(termuxSessionReadTool(context(err = -1, exitCode = 1, stderr = "can't find session: rk_build")).execute(sessionInput()))
        assertEquals("session_not_found", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("termux_session_start"))
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("rk_build"))
    }

    @Test fun `failed tmux package install retains shell exit and diagnostics`() = runBlocking {
        val result = envelope(termuxSessionStartTool(context(err = -1, exitCode = 1, stderr = "E: package download failed")).execute(JsonObject(emptyMap())))
        assertEquals("tmux_install_failed", result["error"]?.jsonPrimitive?.content)
        assertTrue(result["recovery"]?.jsonPrimitive?.content.orEmpty().contains("pkg install tmux"))
        assertEquals("1", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("E: package download failed", result["partial_stderr"]?.jsonPrimitive?.content)
    }

    @Test fun `initial sends retain both failures and created session without repeating input`() = runBlocking {
        val operations = mutableListOf<String>()
        val context = callbackContext { arguments, marker ->
            val operation = arguments.getOrNull(3).orEmpty()
            operations += operation
            when {
                operation == "send-keys" && "-l" in arguments -> resultBundle(err = 7, message = "Text send failed", stdout = "typed initial prefix")
                operation == "send-keys" -> resultBundle(err = 8, message = "Enter send failed", stderr = "enter warning")
                arguments[2].endsWith("/sh") -> resultBundle(stdout = "/bin/tmux\n$marker\n")
                else -> resultBundle(stdout = "\n$marker\n")
            }
        }
        val result = envelope(termuxSessionStartTool(context).execute(buildJsonObject { put("command", "pwd") }))
        assertEquals("initial_command_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("true", result["session_created"]?.jsonPrimitive?.content)
        assertTrue(result["session_id"]?.jsonPrimitive?.content.orEmpty().startsWith("rk_"))
        val message = result["recovery"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(message, message.contains("termux_session_read"))
        assertTrue(message, message.contains("не повторяется"))
        val textResult = result.getValue("send_text_result").jsonObject
        assertEquals("Text send failed", textResult["raw"]?.jsonPrimitive?.content)
        assertEquals("typed initial prefix", textResult["partial_stdout"]?.jsonPrimitive?.content)
        val enterResult = result.getValue("send_enter_result").jsonObject
        assertEquals("Enter send failed", enterResult["raw"]?.jsonPrimitive?.content)
        assertEquals("enter warning", enterResult["partial_stderr"]?.jsonPrimitive?.content)
        assertEquals(1, operations.count { it == "new-session" })
        assertEquals(2, operations.count { it == "send-keys" })
    }

    @Test fun `read failure keeps the screen captured before polling failed`() = runBlocking {
        var captures = 0
        val context = callbackContext { _, marker ->
            if (captures++ == 0) resultBundle(stdout = "screen before failure\n$marker\n")
            else resultBundle(err = 7, message = "Capture failed")
        }
        val result = envelope(termuxSessionReadTool(context).execute(buildJsonObject {
            put("session_id", "rk_build")
            put("wait_for", "unique-finish-marker")
            put("timeout_seconds", 1)
        }))
        assertEquals("read_failed", result["error"]?.jsonPrimitive?.content)
        assertEquals("screen before failure", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals("Capture failed", result["raw"]?.jsonPrimitive?.content)
    }

    private fun context(err: Int, message: String = "", stdout: String = "", stderr: String = "", exitCode: Int = 0): Context =
        callbackContext { _, _ -> resultBundle(err, message, stdout, stderr, exitCode) }

    private fun resultBundle(err: Int = -1, message: String = "", stdout: String = "", stderr: String = "", exitCode: Int = 0) = Bundle().apply {
        putInt("err", err)
        putString("errmsg", message)
        putString("stdout", stdout)
        putString("stderr", stderr)
        putInt("exitCode", exitCode)
    }

    private fun callbackContext(response: (Array<String>, String) -> Bundle): Context {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
            override fun startService(service: Intent): ComponentName? {
                val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
                val arguments = service.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!
                val marker = Regex("[0-9a-f-]{36}").find(arguments[1])!!.value
                val result = response(arguments, marker)
                callback.send(this, 0, Intent().putExtra("result", result))
                // Coroutine delays use real time; advance Robolectric's Android clock too
                // so the real settle/timeout loop can make progress during a screen read.
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        return context
    }

    private fun sessionInput() = buildJsonObject { put("session_id", "rk_build") }
    private fun envelope(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    @Test fun `capture command reports actual stdout truncation with safe continuation`() = runBlocking {
        val raw = "Ж".repeat(me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStdoutBytes)
        val context = callbackContext { _, marker -> resultBundle(stdout = "$raw\n$marker\n", stderr = "remote stderr") }
        val result = envelope(termuxRunCommandTool(context).execute(buildJsonObject { put("command", "printf test") }))
        assertEquals("true", result["truncated"]?.jsonPrimitive?.content)
        assertEquals("true", result["stdout_truncated"]?.jsonPrimitive?.content)
        assertEquals("false", result["stderr_truncated"]?.jsonPrimitive?.content)
        assertEquals("remote stderr", result["stderr"]?.jsonPrimitive?.content)
        val recovery = result["recovery"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(recovery, recovery.contains("не повтор", ignoreCase = true))
        assertTrue(result["stdout"]!!.jsonPrimitive.content.startsWith(takeFirstUtf8Bytes(raw,
            me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStdoutBytes)))
    }

    @Test fun `session read reports actual tail truncation and keeps the latest content`() = runBlocking {
        val raw = "Ж".repeat(me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStdoutBytes) + "LATEST"
        val context = callbackContext { _, marker -> resultBundle(stdout = "$raw\n$marker\n") }
        val result = envelope(termuxSessionReadTool(context).execute(sessionInput()))
        assertEquals("true", result["truncated"]?.jsonPrimitive?.content)
        assertTrue(result["screen"]!!.jsonPrimitive.content.contains("LATEST"))
        val recovery = result["recovery"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(recovery, Regex("[А-Яа-яЁё]").containsMatchIn(recovery))
        assertTrue(recovery, recovery.contains("не повтор", ignoreCase = true))
    }

    @Test fun `failure diagnostics flag clipped streams without rewriting remote output`() {
        val raw = "X".repeat(me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStderrBytes + 1)
        val result = CaptureFailureDetails(stdout = "untouched", stderr = raw).json()
        assertEquals("true", result["truncated"]?.jsonPrimitive?.content)
        assertEquals("false", result["stdout_truncated"]?.jsonPrimitive?.content)
        assertEquals("true", result["stderr_truncated"]?.jsonPrimitive?.content)
        assertEquals("untouched", result["partial_stdout"]?.jsonPrimitive?.content)
        assertTrue(result["partial_stderr"]!!.jsonPrimitive.content.startsWith(raw.dropLast(1)))
    }

    @Test fun `Termux clipped multibyte marker counts bytes actually omitted`() = runBlocking {
        val old = me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStdoutBytes
        me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStdoutBytes = 5
        try {
            val context = callbackContext { _, marker -> resultBundle(stdout = "😀😀\n$marker\n") }
            val result = envelope(termuxRunCommandTool(context).execute(buildJsonObject { put("command", "printf test") }))
            val output = result["stdout"]!!.jsonPrimitive.content
            assertTrue(output, output.startsWith("😀\n"))
            assertTrue(output, output.contains("ещё 4 байт"))
            assertFalse(output, output.contains('\uFFFD'))
        } finally { me.rerere.rikkahub.data.preferences.TermuxRuntime.maxStdoutBytes = old }
    }

    @Test fun `printed truncation words in short Termux output remain ordinary external content`() = runBlocking {
        val raw = "remote [truncated] вывод сокращён"
        val context = callbackContext { _, marker -> resultBundle(stdout = "$raw\n$marker\n") }
        val result = envelope(termuxRunCommandTool(context).execute(buildJsonObject { put("command", "printf test") }))
        assertEquals(raw, result["stdout"]?.jsonPrimitive?.content)
        assertFalse(result["truncated"]?.jsonPrimitive?.content == "true")
        assertFalse(result.containsKey("recovery"))
    }
}
