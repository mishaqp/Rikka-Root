package me.rerere.rikkahub.service

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.web.WebServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WebServerAutoStartTest {
    private val app = RuntimeEnvironment.getApplication()
    private val enabled = Settings.dummy().copy(webServerEnabled = true, webServerPort = 9123, webServerLocalhostOnly = true)

    @Test fun `enabled stopped server is started in the foreground`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(WebServerAutoStart.startIfEnabled(app, enabled, WebServerState()))

        val intent = shadowOf(app).nextStartedService
        assertEquals(WebServerService.ACTION_START, intent.action)
        assertEquals(9123, intent.getIntExtra(WebServerService.EXTRA_PORT, 0))
        assertTrue(intent.getBooleanExtra(WebServerService.EXTRA_LOCALHOST_ONLY, false))
    }

    @Test fun `running, starting or disabled server is left alone`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(WebServerAutoStart.startIfEnabled(app, enabled, WebServerState(isRunning = true)))
        assertFalse(WebServerAutoStart.startIfEnabled(app, enabled, WebServerState(isLoading = true)))
        assertFalse(WebServerAutoStart.startIfEnabled(app, enabled.copy(webServerEnabled = false), WebServerState()))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `missing notification permission skips the start`() {
        assertFalse(WebServerAutoStart.startIfEnabled(app, enabled, WebServerState()))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `background start refusal is reported instead of crashing`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val background = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false")
        }
        assertFalse(WebServerAutoStart.startIfEnabled(background, enabled, WebServerState()))
    }
}
