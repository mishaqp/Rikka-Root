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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxToolFailureMessageTest {
    @Test fun `nonzero captured exit explains the code without changing command streams`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
            override fun startService(service: Intent): ComponentName? {
                val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
                val result = Bundle().apply {
                    putInt("err", -1)
                    putInt("exitCode", 127)
                    putString("stdout", "original output\n")
                    putString("stderr", "missing_binary: command not found\n")
                }
                callback.send(this, 0, Intent().putExtra("result", result))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        val parts = termuxRunCommandTool(context).execute(Json.parseToJsonElement("""{"command":"missing_binary","timeout_seconds":1}"""))
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("true", result["success"]?.jsonPrimitive?.content)
        assertEquals("127", result["exit_code"]?.jsonPrimitive?.content)
        assertEquals("original output\n", result["stdout"]?.jsonPrimitive?.content)
        assertEquals("missing_binary: command not found\n", result["stderr"]?.jsonPrimitive?.content)
        assertEquals("Команда завершилась с ошибкой (код 127). Проверьте вывод и результат перед новым запуском.",
            result["note"]?.jsonPrimitive?.content)
    }

    @Test fun `capture startup exception has a Russian action`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startService(service: Intent): ComponentName? = throw IOException("Broken pipe")
        }
        val result = runCommandCapture(context, "/bin/echo", arrayOf("hi"), "/tmp", 1000)
        assertTrue(result is CaptureResult.OtherError)
        val message = (result as CaptureResult.OtherError).message
        assertTrue(message, message.contains("Termux"))
        assertTrue(message, message.contains("Проверьте"))
        assertFalse(message, message.contains("Broken pipe"))
    }

    @Test fun `Termux internal error keeps code and command output with Russian explanation`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
            override fun startService(service: Intent): ComponentName? {
                val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
                val result = Bundle().apply {
                    putInt("err", 7)
                    putString("errmsg", "Service failed")
                    putString("stdout", "downloaded 42 bytes")
                    putString("stderr", "remote warning")
                    putInt("exitCode", 23)
                }
                callback.send(this, 0, Intent().putExtra("result", result))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        val parts = termuxRunCommandTool(context).execute(Json.parseToJsonElement("""{"command":"echo hi","timeout_seconds":1}"""))
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("termux_run_failed", result["error"]?.jsonPrimitive?.content)
        val message = result["reason"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(message, message.contains("Termux"))
        assertTrue(message, message.contains("Проверьте"))
        assertFalse(message, message.startsWith("err="))
        assertEquals("7", result["err"]?.jsonPrimitive?.content)
        assertEquals("Service failed", result["raw"]?.jsonPrimitive?.content)
        assertEquals("downloaded 42 bytes", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals("remote warning", result["partial_stderr"]?.jsonPrimitive?.content)
        assertEquals("23", result["exit_code"]?.jsonPrimitive?.content)
    }

    @Test fun `timeout keeps nonterminal callback output instead of discarding it`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
            override fun startService(service: Intent): ComponentName? {
                val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
                val result = Bundle().apply {
                    putInt("err", -1)
                    putInt("exitCode", 0)
                    putString("stdout", "partial before timeout")
                    putString("stderr", "partial warning")
                }
                callback.send(this, 0, Intent().putExtra("result", result))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        val parts = termuxRunCommandTool(context).execute(Json.parseToJsonElement("""{"command":"echo hi","timeout_seconds":1}"""))
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
        assertEquals("partial before timeout", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals("partial warning", result["partial_stderr"]?.jsonPrimitive?.content)
    }
}
