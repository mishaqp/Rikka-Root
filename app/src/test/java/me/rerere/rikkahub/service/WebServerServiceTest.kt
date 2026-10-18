package me.rerere.rikkahub.service

import android.app.Application
import android.content.Intent
import android.os.Looper
import io.mockk.mockk
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.WebServerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket

/** Real service and manager; the server never binds because the port is already taken. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebServerServiceTest {
    private lateinit var manager: WebServerManager

    @Before fun setUp() {
        manager = WebServerManager(
            RuntimeEnvironment.getApplication(), AppScope(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        startKoin { modules(module { single { manager }; single { settingsStore } }) }
    }

    @After fun tearDown() { stopKoin() }

    @Test fun `failed start leaves the foreground and stops the service`() {
        ServerSocket(0).use { busy ->
            val intent = Intent(WebServerService.ACTION_START)
                .putExtra(WebServerService.EXTRA_PORT, busy.localPort)
                .putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, true)
            val controller = Robolectric.buildService(WebServerService::class.java, intent).create().startCommand(0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            val state = manager.state.value
            assertFalse(state.isRunning)
            assertFalse(state.isLoading)
            assertNotNull("The start attempt must report its failure", state.error)
            val service = shadowOf(controller.get())
            assertTrue("A failed start must remove the foreground notification", service.isForegroundStopped)
            assertTrue("A failed start must not keep the service alive", service.isStoppedBySelf)
            assertEquals(1, service.stopSelfId)
            controller.destroy()
        }
    }

    @Test fun `stop request for a server that is not running stops the service`() {
        val controller = Robolectric.buildService(WebServerService::class.java, Intent(WebServerService.ACTION_STOP))
            .create().startCommand(0, 3)
        shadowOf(Looper.getMainLooper()).idle()

        val service = shadowOf(controller.get())
        assertTrue(service.isStoppedBySelf)
        assertEquals(3, service.stopSelfId)
        controller.destroy()
    }

    @Test fun `start marks the state as loading before its coroutine runs`() {
        ServerSocket(0).use { busy ->
            manager.start(port = busy.localPort, localhostOnly = true)
            assertTrue("Observers must not see the idle state as a finished start", manager.state.value.isLoading)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(manager.state.value.isLoading)
        }
    }

    @Test fun `idle state is final only when the server neither runs nor loads`() {
        val idle = manager.state.value
        assertTrue(shouldStopWebServerService(idle))
        assertFalse(shouldStopWebServerService(idle.copy(isLoading = true)))
        assertFalse(shouldStopWebServerService(idle.copy(isRunning = true)))
    }
}
