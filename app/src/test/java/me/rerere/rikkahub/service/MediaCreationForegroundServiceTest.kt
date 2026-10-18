package me.rerere.rikkahub.service

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MediaCreationForegroundServiceTest {
    private val sent = mutableListOf<Intent>()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun startForegroundService(service: Intent): ComponentName? {
            sent += service
            return service.component
        }

        override fun startService(service: Intent): ComponentName? {
            sent += service
            return service.component
        }
    }

    @Test fun `release stops only up to its own start id so a queued acquire survives`() {
        val first = Uuid.random()
        val second = Uuid.random()
        MediaCreationForegroundService.acquire(context, first, Uuid.random())
        MediaCreationForegroundService.release(context, first)
        MediaCreationForegroundService.acquire(context, second, Uuid.random())
        val (acquireFirst, releaseFirst, acquireSecond) = sent

        val controller = Robolectric.buildService(MediaCreationForegroundService::class.java).create()
        controller.withIntent(acquireFirst).startCommand(0, 1)
        controller.withIntent(releaseFirst).startCommand(0, 2)
        val service = shadowOf(controller.get())
        assertTrue(service.isStoppedBySelf)
        assertEquals("stopSelf() without an id would also drop the queued ACQUIRE #3", 2, service.stopSelfId)

        controller.withIntent(acquireSecond).startCommand(0, 3)
        assertFalse("The queued acquire enters the foreground again", service.isForegroundStopped)
        assertEquals(MediaCreationForegroundService.NOTIFICATION_ID, service.lastForegroundNotificationId)
        controller.destroy()
    }

    @Test fun `unknown command stops with its start id`() {
        val controller = Robolectric.buildService(MediaCreationForegroundService::class.java, Intent("unknown"))
            .create().startCommand(0, 5)
        assertEquals(5, shadowOf(controller.get()).stopSelfId)
        controller.destroy()
    }
}
