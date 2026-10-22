package me.rerere.rikkahub.data.ai.tools.local

import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.util.concurrent.TimeUnit

internal data class TermuxTestReply(
    val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
    val err: Int = -1,
    val raw: String = "",
)

/** Only the external Termux process is substituted; dispatch, callbacks and tools are real. */
internal class TermuxCommandTestContext(
    private val reply: (List<String>, Int) -> TermuxTestReply?,
) : ContextWrapper(RuntimeEnvironment.getApplication()) {
    val requests = mutableListOf<List<String>>()

    init {
        Shadows.shadowOf(packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED

    override fun startForegroundService(service: Intent): ComponentName {
        val callback = service.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        val arguments = service.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!
        val marker = Regex("[0-9a-f-]{36}").find(arguments[1])!!.value
        val argv = arguments.drop(2)
        requests += argv
        val response = reply(argv, requests.size)
        val result = Bundle().apply {
            putInt("err", response?.err ?: -1)
            putInt("exitCode", response?.exitCode ?: 0)
            putString("errmsg", response?.raw.orEmpty())
            putString("stdout", when {
                response == null -> "partial before timeout\n" // Nonterminal ACK, without completion marker.
                response.err != -1 -> response.stdout
                else -> "${response.stdout}\n$marker\n"
            })
            putString("stderr", response?.stderr.orEmpty())
        }
        callback.send(this, 0, Intent().putExtra("result", result))
        // Screen settling uses Android's clock; coroutine timeouts use the test scheduler.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        return requireNotNull(service.component)
    }
}
