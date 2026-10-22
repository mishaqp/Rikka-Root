package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.ui.components.message.TimelineStatus
import me.rerere.rikkahub.ui.components.message.ToolResultPreview
import me.rerere.rikkahub.ui.components.message.presentToolDetails
import me.rerere.rikkahub.ui.components.message.presentToolResult
import me.rerere.rikkahub.ui.components.message.presentToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxCommandFailureDiagnosisTest {
    @Test fun `Termux tool documentation requires checking completion after a PID or startup log`() {
        val description = termuxRunCommandTool(RuntimeEnvironment.getApplication()).description
        assertTrue(description, description.contains("PID"))
        assertTrue(description, description.contains("стартовая строка журнала"))
        assertTrue(description, description.contains("не подтверждают завершение"))
        assertTrue(description, description.contains("проверьте результат", ignoreCase = true))
    }

    @Test fun `missing known command followed by digits suggests a possible space without changing execution`() = runBlocking {
        val cases = listOf(
            Triple("sleep40", "sleep 40", "bash: line 1: sleep40: command not found\n"),
            Triple("sleep12", "sleep 12", "/system/bin/sh: sleep12: inaccessible or not found\n"),
            Triple("chmod755", "chmod 755", "chmod755: not found\n"),
            Triple("chmod0644", "chmod 0644", "bash: command not found: chmod0644\n"),
        )
        for ((missing, corrected, stderr) in cases) {
            val command = if (missing.startsWith("chmod")) "$missing /scratch/file" else missing
            val execution = executeCaptured(command, 127, "original output\n", stderr)
            assertPreserved(execution.result, 127, "original output\n", stderr)
            assertTrue("Original command must reach Termux unchanged", execution.arguments.last().endsWith(command))
            val note = execution.result["note"]?.jsonPrimitive?.content.orEmpty()
            assertTrue(note, note.contains("пробел", ignoreCase = true))
            assertTrue(note, note.contains("вероят", ignoreCase = true) || note.contains("возможно", ignoreCase = true))
            assertTrue(note, note.contains(corrected))
            assertFalse(note, note.contains("install $missing"))
            listOf("модел", "провайдер", "model", "provider", "stream", "стрим").forEach { unsupportedCause ->
                assertFalse(note, note.contains(unsupportedCause, ignoreCase = true))
            }
        }
    }

    @Test fun `an actual missing python3 retains the Termux installation hint`() = runBlocking {
        val stderr = "bash: line 1: python3: command not found\n"
        val execution = executeCaptured("python3 --version", 127, "", stderr)
        assertPreserved(execution.result, 127, "", stderr)
        val note = execution.result["note"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(note, note.contains("pkg install python3"))
        assertFalse(note, note.contains("пробел", ignoreCase = true))
    }

    @Test fun `malformed looking input alone does not override a different missing command diagnostic`() = runBlocking {
        val execution = executeCaptured("sleep40; python3 --version", 127, "sleep40: command not found\n",
            "python3: command not found\n")
        val note = execution.result["note"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(note, note.contains("pkg install python3"))
        assertFalse(note, note.contains("sleep 40"))
    }

    @Test fun `Termux data adb denial suggests root exec and preserves command and diagnostics`() = runBlocking {
        val command = "cat /data/adb/modules/example/module.prop"
        val stderr = "cat: /data/adb/modules/example/module.prop: Permission denied\n"
        val execution = executeCaptured(command, 1, "partial output\n", stderr)
        assertPreserved(execution.result, 1, "partial output\n", stderr)
        assertTrue("The tool must not add su or otherwise rewrite the command", execution.arguments.last().endsWith(command))
        val note = execution.result["note"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(note, note.contains("root_exec"))
        assertTrue(note, note.contains("/data/adb"))
        assertFalse(note, note.contains("chmod"))
    }

    @Test fun `denial without a path can use the submitted data adb command as evidence`() = runBlocking {
        val execution = executeCaptured("ls -la /data/adb", 1, "", "ls: Permission denied\n")
        assertTrue(execution.result.toString(), execution.result["note"]?.jsonPrimitive?.content.orEmpty().contains("root_exec"))
    }

    @Test fun `data adb denial on stdout also explains the missing privilege without moving streams`() = runBlocking {
        val stdout = "cat: /data/adb/modules/example/module.prop: Permission denied\n"
        val execution = executeCaptured("cat /data/adb/modules/example/module.prop", 1, stdout, "")
        assertPreserved(execution.result, 1, stdout, "")
        assertTrue(execution.result.toString(), execution.result["note"]?.jsonPrimitive?.content.orEmpty().contains("root_exec"))
    }

    @Test fun `raw executable arguments supply context for a pathless denial without being rewritten`() = runBlocking {
        val executable = "/data/data/com.termux/files/usr/bin/cat"
        val path = "/data/adb/modules/example/module.prop"
        val execution = executeCaptured(buildJsonObject {
            put("executable", executable)
            put("arguments", buildJsonArray { add(path) })
            put("timeout_seconds", 1)
        }, 1, "", "cat: Permission denied\n")
        assertEquals(listOf(executable, path), execution.arguments.takeLast(2))
        assertTrue(execution.result.toString(), execution.result["note"]?.jsonPrimitive?.content.orEmpty().contains("root_exec"))
    }

    @Test fun `a path without denial or an unrelated denied path does not claim root is needed`() = runBlocking {
        val cases = listOf(
            Triple("cat /data/adb/modules/example/module.prop", 1, "cat: Input/output error\n"),
            Triple("cat /data/adblock/file", 1, "cat: /data/adblock/file: Permission denied\n"),
            Triple("cat /sdcard/example", 1, "cat: /sdcard/example: Permission denied\n"),
            Triple("cat /data/adb/module.prop; cat /sdcard/example", 1, "cat: /sdcard/example: Permission denied\n"),
            Triple("cat /data/adb/modules/example/module.prop", 0, ""),
        )
        for ((command, code, stderr) in cases) {
            val execution = executeCaptured(command, code, "", stderr)
            assertFalse(execution.result.toString(), execution.result["note"]?.jsonPrimitive?.content.orEmpty().contains("root_exec"))
        }
    }

    @Test fun `pathless denial cannot be attributed to data adb from a mention or compound command`() = runBlocking {
        val cases = listOf(
            "printf '/data/adb'; probe" to "probe: Permission denied\n",
            "echo /data/adb" to "echo: Permission denied\n",
            "echo '/data/adb'" to "echo: Permission denied\n",
            "printf '%s' '/data/adb'" to "printf: Permission denied\n",
            "probe '/data/adb'" to "probe: Permission denied\n",
            "ls /data/adb; probe" to "probe: Permission denied\n",
            "cat '/data/adb/modules/example/module.prop' && probe" to "cat: Permission denied\n",
            "cat /data/adb/module.prop /sdcard/private" to "cat: Permission denied\n",
        )
        for ((command, stderr) in cases) {
            val execution = executeCaptured(command, 1, "partial result\n", stderr)
            assertPreserved(execution.result, 1, "partial result\n", stderr)
            val note = execution.result["note"]?.jsonPrimitive?.content.orEmpty()
            assertFalse("$command: $note", note.contains("root_exec"))
            assertFalse("$command: $note", note.contains("/data/adb"))
        }
    }

    @Test fun `an unrelated executable cannot use its data adb argument to attribute pathless denial`() = runBlocking {
        val executable = "/data/data/com.termux/files/usr/bin/probe"
        val execution = executeCaptured(buildJsonObject {
            put("executable", executable)
            put("arguments", buildJsonArray { add("/data/adb") })
            put("timeout_seconds", 1)
        }, 1, "", "probe: Permission denied\n")
        assertEquals(listOf(executable, "/data/adb"), execution.arguments.takeLast(2))
        val note = execution.result["note"]?.jsonPrimitive?.content.orEmpty()
        assertFalse(note, note.contains("root_exec"))
        assertFalse(note, note.contains("/data/adb"))
    }

    @Test fun `historical cards replace stale installation reasons only for evidenced missing space diagnostics`() {
        for (toolName in listOf("root_exec", "termux_run_command")) {
            for ((missing, corrected) in listOf("sleep40" to "sleep 40", "chmod755" to "chmod 755")) {
                val stdout = "original output\npartial result"
                val stderr = "sh: $missing: command not found"
                val recovery = "Перед повтором проверьте созданный файл: часть действий могла выполниться."
                val output = buildJsonObject {
                    put("success", true)
                    put("exit_code", 127)
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("reason", "Программа $missing не установлена или недоступна в PATH (код 127). Установите её: pkg install $missing.")
                    put("recovery", recovery)
                }
                val preview = presentToolResult(toolName, output.toString(), output) as ToolResultPreview.Command
                assertEquals(stdout, preview.stdout)
                assertEquals(stderr, preview.stderr)
                assertEquals(127, preview.exitCode)
                for (text in listOf(preview.reason.orEmpty(), presentToolDetails(toolName, output))) {
                    assertTrue(text, text.contains(corrected))
                    assertTrue(text, text.contains("пробел", ignoreCase = true))
                    assertTrue(text, text.contains("вероят", ignoreCase = true) || text.contains("возможно", ignoreCase = true))
                    assertFalse(text, text.contains("install $missing"))
                    assertTrue(text, text.contains(recovery))
                }
                assertHistoricalFailure(toolName, output)
                assertEquals("true", output["success"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test fun `historical Termux cards prefer the evidenced root hint over a generic old reason`() {
        val diagnostic = "cat: /data/adb/modules/example/module.prop: Permission denied"
        for ((stdout, stderr) in listOf("partial result" to diagnostic, diagnostic to "")) {
            val output = buildJsonObject {
                put("success", true)
                put("exit_code", 1)
                put("stdout", stdout)
                put("stderr", stderr)
                put("reason", "Команда завершилась с ошибкой (код 1). Проверьте вывод и результат перед новым запуском.")
            }
            val preview = presentToolResult("termux_run_command", output.toString(), output) as ToolResultPreview.Command
            assertEquals(stdout, preview.stdout)
            assertEquals(stderr, preview.stderr)
            assertEquals(1, preview.exitCode)
            assertTrue(preview.reason.orEmpty(), preview.reason.orEmpty().contains("root_exec"))
            assertTrue(presentToolDetails("termux_run_command", output).contains("root_exec"))
            assertHistoricalFailure("termux_run_command", output)
        }
    }

    @Test fun `focused command hints retain authored partial execution warnings and recovery`() {
        val warning = "Часть действий могла выполниться. Проверьте созданный файл перед повтором."
        val recovery = "Не повторяйте исходную команду вслепую."
        for (warningKey in listOf("reason", "message", "note")) {
            val staleKey = if (warningKey == "reason") "note" else "reason"
            val output = buildJsonObject {
                put("success", true)
                put("exit_code", 127)
                put("stdout", "partial result")
                put("stderr", "sh: sleep40: not found")
                put(warningKey, warning)
                put(staleKey, "Программа sleep40 не установлена или недоступна в PATH (код 127). Установите её: pkg install sleep40.")
                put("recovery", recovery)
            }
            val preview = presentToolResult("termux_run_command", output.toString(), output) as ToolResultPreview.Command
            assertEquals("partial result", preview.stdout)
            assertEquals("sh: sleep40: not found", preview.stderr)
            assertEquals(127, preview.exitCode)
            for (text in listOf(preview.reason.orEmpty(), presentToolDetails("termux_run_command", output))) {
                assertTrue(text, text.contains("sleep 40"))
                assertTrue("$warningKey: $text", text.contains(warning))
                assertTrue(text, text.contains(recovery))
                assertFalse(text, text.contains("install sleep40"))
            }
            assertHistoricalFailure("termux_run_command", output)
            assertEquals("true", output["success"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `historical custom reasons and recovery keep priority without a specific evidenced diagnostic`() {
        val reason = "Окружение намеренно изолировано. Проверьте его настройки."
        val recovery = "Не устанавливайте пакеты автоматически."
        val output = buildJsonObject {
            put("exit_code", 127)
            put("stdout", "partial result")
            put("stderr", "python3: command not found")
            put("reason", reason)
            put("recovery", recovery)
        }
        val preview = presentToolResult("termux_run_command", output.toString(), output) as ToolResultPreview.Command
        assertEquals("$reason\n$recovery", preview.reason)
        assertHistoricalFailure("termux_run_command", output)
    }

    private data class Execution(val result: JsonObject, val arguments: List<String>)

    private fun assertHistoricalFailure(toolName: String, output: JsonObject) {
        val call = UIMessagePart.Tool(toolCallId = "historical", toolName = toolName, input = "{}",
            output = listOf(UIMessagePart.Text(output.toString())), approvalState = ToolApprovalState.Auto,
            metadata = JsonObject(emptyMap()))
        assertEquals(TimelineStatus.Failed, presentToolStatus(call, false))
    }

    private suspend fun executeCaptured(command: String, exitCode: Int, stdout: String, stderr: String): Execution =
        executeCaptured(buildJsonObject { put("command", command); put("timeout_seconds", 1) }, exitCode, stdout, stderr)

    private suspend fun executeCaptured(input: JsonObject, exitCode: Int, stdout: String, stderr: String): Execution {
        var dispatchedArguments = emptyList<String>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
            override fun startService(service: Intent): ComponentName {
                dispatchedArguments = service.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!.toList()
                val marker = Regex("printf '\\\\n%s' '([^']+)'").find(dispatchedArguments[1])!!.groupValues[1]
                val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
                callback.send(this, 0, Intent().putExtra("result", Bundle().apply {
                    putInt("err", -1)
                    putInt("exitCode", exitCode)
                    putString("stdout", if (exitCode == 0) "$stdout\n$marker" else stdout)
                    putString("stderr", stderr)
                }))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        val parts = termuxRunCommandTool(context).execute(input)
        return Execution(Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject, dispatchedArguments)
    }

    private fun assertPreserved(result: JsonObject, code: Int, stdout: String, stderr: String) {
        assertEquals("true", result["success"]?.jsonPrimitive?.content)
        assertFalse(result.toString(), result.containsKey("error"))
        assertEquals(code.toString(), result["exit_code"]?.jsonPrimitive?.content)
        assertEquals(stdout, result["stdout"]?.jsonPrimitive?.content)
        assertEquals(stderr, result["stderr"]?.jsonPrimitive?.content.orEmpty())
    }
}
