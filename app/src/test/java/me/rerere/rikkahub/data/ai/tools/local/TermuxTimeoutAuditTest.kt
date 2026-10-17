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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxTimeoutAuditTest {
    @Test fun `timeout warns about an unconfirmed running command and forbids blind retry`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED
            override fun startService(service: Intent): ComponentName {
                val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
                // A start ACK with partial output is not a terminal callback.
                callback.send(this, 0, Intent().putExtra("result", Bundle().apply {
                    putInt("err", -1); putInt("exitCode", 0)
                    putString("stdout", "side effect may already have started")
                    putString("stderr", "partial diagnostic")
                }))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        val parts = termuxRunCommandTool(context).execute(Json.parseToJsonElement(
            """{"command":"sleep 30","timeout_seconds":1}"""))
        val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("timeout", result["error"]?.jsonPrimitive?.content)
        assertEquals("true", result["may_still_be_running"]?.jsonPrimitive?.content)
        val recovery = result["recovery"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(recovery, recovery.contains("не повтор", ignoreCase = true))
        assertTrue(recovery, recovery.contains("проверь", ignoreCase = true))
        assertEquals("side effect may already have started", result["partial_stdout"]?.jsonPrimitive?.content)
        assertEquals("partial diagnostic", result["partial_stderr"]?.jsonPrimitive?.content)
    }
}
