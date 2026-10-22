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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TermuxServiceDispatchTest {
    @Test fun `capture starts foreground Termux service and preserves literal arguments and callback`() = runBlocking {
        val context = DispatchContext()
        val arguments = arrayOf("-c", "printf '[%s]\\n' 'a b'; printf '%s\\n' 'A\n  B' 2>/dev/null")

        val result = runCommandCapture(context, BASH, arguments, WORKDIR, timeoutMs = 1000)

        assertTrue("Expected command completion, got $result", result is CaptureResult.Success)
        result as CaptureResult.Success
        assertEquals("command-output\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(0, result.exitCode)
        assertSingleForegroundDispatch(context)
        val intent = requireNotNull(context.dispatchedIntent)
        assertEquals(BASH, intent.getStringExtra(PATH))
        assertEquals(listOf(BASH, *arguments), intent.getStringArrayExtra(ARGUMENTS)!!.drop(2))
        assertEquals(WORKDIR, intent.getStringExtra(WORKDIR_EXTRA))
        assertTrue(intent.getBooleanExtra(BACKGROUND, false))
        assertNotNull(intent.getParcelableExtra<PendingIntent>(CALLBACK))
    }

    @Test fun `interactive session uses foreground dispatch without changing command or claiming completion`() = runBlocking {
        val context = DispatchContext()
        val command = "printf '%s\\n' 'A\n  B' 2>/dev/null"
        val previousAptWrap = TermuxRuntime.aptWrapEnabled
        TermuxRuntime.aptWrapEnabled = false
        try {
            val parts = termuxRunCommandTool(context).execute(buildJsonObject {
                put("command", command)
                put("interactive", true)
                put("working_dir", WORKDIR)
            })
            val result = kotlinx.serialization.json.Json.parseToJsonElement(
                (parts.single() as UIMessagePart.Text).text,
            ).jsonObject

            assertEquals("true", result["success"]?.jsonPrimitive?.content)
            assertEquals("interactive", result["mode"]?.jsonPrimitive?.content)
            assertNull(result["exit_code"])
            assertTrue(result["note"]?.jsonPrimitive?.content.orEmpty().contains("не возвращает вывод"))
            assertSingleForegroundDispatch(context)
            val intent = requireNotNull(context.dispatchedIntent)
            assertArrayEquals(arrayOf("-c", command), intent.getStringArrayExtra(ARGUMENTS))
            assertEquals(WORKDIR, intent.getStringExtra(WORKDIR_EXTRA))
            assertFalse(intent.getBooleanExtra(BACKGROUND, true))
            assertEquals("0", intent.getStringExtra("com.termux.RUN_COMMAND_SESSION_ACTION"))
            assertFalse(intent.hasExtra(CALLBACK))
        } finally {
            TermuxRuntime.aptWrapEnabled = previousAptWrap
        }
    }

    @Test fun `connection verification uses foreground dispatch and waits for actual result`() = runBlocking {
        val context = DispatchContext(stdout = "RIKKAHUB_OK\n")

        assertEquals(TermuxIntegration.VerifyResult.Ok, TermuxIntegration.verify(context, timeoutMs = 1000))
        assertSingleForegroundDispatch(context)
    }

    @Test fun `foreground startup refusal preserves actual failure and does not retry command`() = runBlocking {
        val rawError = "startForegroundService() not allowed due to mAllowStartForeground false"
        val context = DispatchContext(launchError = IllegalStateException(rawError))

        val result = runCommandCapture(context, BASH, arrayOf("-c", "printf test"), WORKDIR, timeoutMs = 1000)

        assertTrue(result is CaptureResult.OtherError)
        assertEquals(rawError, (result as CaptureResult.OtherError).details.rawError)
        assertNull(result.details.exitCode)
        assertSingleForegroundDispatch(context)
    }

    @Test fun `foreground permission denial remains denied and does not retry command`() = runBlocking {
        val context = DispatchContext(launchError = SecurityException("RUN_COMMAND permission denied"))

        val result = runCommandCapture(context, BASH, arrayOf("-c", "printf test"), WORKDIR, timeoutMs = 1000)

        assertTrue(result is CaptureResult.Denied)
        assertEquals("RUN_COMMAND permission denied", (result as CaptureResult.Denied).details.rawError)
        assertNull(result.details.exitCode)
        assertSingleForegroundDispatch(context)
    }

    private fun assertSingleForegroundDispatch(context: DispatchContext) {
        assertEquals(1, context.foregroundStarts)
        assertEquals("A denied ordinary service start must not be needed", 0, context.ordinaryStarts)
        assertEquals(ComponentName("com.termux", "com.termux.app.RunCommandService"), context.dispatchedIntent?.component)
        assertEquals("com.termux.RUN_COMMAND", context.dispatchedIntent?.action)
    }

    /** Model Android refusing an ordinary start of the stopped/background Termux package. */
    private class DispatchContext(
        private val stdout: String = "command-output\n",
        private val launchError: Exception? = null,
    ) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var ordinaryStarts = 0
        var foregroundStarts = 0
        var dispatchedIntent: Intent? = null

        init {
            Shadows.shadowOf(packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        }

        override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_GRANTED

        override fun startService(service: Intent): ComponentName? {
            ordinaryStarts++
            throw IllegalStateException("Not allowed to start service Intent: app is in background")
        }

        override fun startForegroundService(service: Intent): ComponentName {
            foregroundStarts++
            dispatchedIntent = Intent(service)
            launchError?.let { throw it }
            service.getParcelableExtra<PendingIntent>(CALLBACK)?.let { callback ->
                // A launch ACK alone must not be mistaken for command completion.
                callback.send(this, 0, Intent())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val markerScript = service.getStringArrayExtra(ARGUMENTS)!![1]
                val marker = markerScript.substringAfter("printf '\\n%s' '").substringBefore("'")
                callback.send(this, 0, Intent().putExtra("result", Bundle().apply {
                    putInt("err", -1)
                    putInt("exitCode", 0)
                    putString("stdout", "$stdout\n$marker\n")
                    putString("stderr", "")
                }))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            return requireNotNull(service.component)
        }
    }

    private companion object {
        const val BASH = "/data/data/com.termux/files/usr/bin/bash"
        const val WORKDIR = "/data/data/com.termux/files/home"
        const val PATH = "com.termux.RUN_COMMAND_PATH"
        const val ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val WORKDIR_EXTRA = "com.termux.RUN_COMMAND_WORKDIR"
        const val BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val CALLBACK = "com.termux.RUN_COMMAND_PENDING_INTENT"
    }
}
