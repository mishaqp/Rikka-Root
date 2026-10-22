package me.rerere.rikkahub.service

import android.app.Application
import android.content.Intent
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class MediaForegroundStartOwnershipPart3Test {
    private fun acquire(id: Uuid) = Intent().setAction("me.rerere.rikkahub.action.MEDIA_CREATION_ACQUIRE")
        .putExtra("record_id", id.toString()).putExtra("session_id", Uuid.random().toString())
    private fun release(id: Uuid) = Intent().setAction("me.rerere.rikkahub.action.MEDIA_CREATION_RELEASE")
        .putExtra("record_id", id.toString())

    @Test fun staleReleaseKeepsNotificationAndUsesItsOwnStartIdWhenNewAcquireIsQueued() {
        val controller = Robolectric.buildService(MediaCreationForegroundService::class.java).create()
        val service = controller.get()
        try {
            val first = Uuid.random()
            service.onStartCommand(acquire(first), 0, 10)
            val app = RuntimeEnvironment.getApplication()
            assertTrue(MediaCreationForegroundService.acquire(app, Uuid.random(), Uuid.random()))
            val queued = shadowOf(app).nextStartedService
            assertEquals("me.rerere.rikkahub.action.MEDIA_CREATION_ACQUIRE", queued.action)
            service.onStartCommand(release(first), 0, 11)
            val record = shadowOf(service)
            // ShadowService records which Android overload was called. stopSelf(11)
            // permits Android to retain the already queued newer foreground start.
            assertEquals("Старый RELEASE должен ограничивать остановку собственным startId", 11, record.stopSelfId)
            assertFalse("До решения Android о startId уведомление нового старта нельзя убирать", record.isForegroundStopped)
            service.onStartCommand(queued, 0, 12)
            assertEquals(MediaCreationForegroundService.NOTIFICATION_ID, record.lastForegroundNotificationId)
            assertTrue(record.isLastForegroundNotificationAttached)
        } finally { controller.destroy() }
    }

    @Test fun releasingOneOfTwoRecordsDoesNotStopForegroundAndFinalReleaseIsScoped() {
        val controller = Robolectric.buildService(MediaCreationForegroundService::class.java).create()
        val service = controller.get()
        try {
            val first = Uuid.random(); val second = Uuid.random()
            service.onStartCommand(acquire(first), 0, 1)
            service.onStartCommand(acquire(second), 0, 2)
            service.onStartCommand(release(first), 0, 3)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertFalse(shadowOf(service).isForegroundStopped)
            service.onStartCommand(release(second), 0, 4)
            assertEquals("Последний RELEASE тоже должен учитывать более новые intents", 4, shadowOf(service).stopSelfId)
        } finally { controller.destroy() }
    }
}
