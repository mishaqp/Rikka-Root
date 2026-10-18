package me.rerere.rikkahub.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ChatCompletedNotificationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Before fun grant() { shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }

    @Test fun `reply notifications of different conversations do not replace each other`() {
        val first = Uuid.random()
        val second = Uuid.random()
        assertTrue(app.sendChatCompletedNotification(first, "Ассистент", "Первый ответ", intent()))
        assertTrue(app.sendChatCompletedNotification(second, "Ассистент", "Второй ответ", intent()))
        assertEquals(2, manager.activeNotifications.size)

        app.sendChatCompletedNotification(first, "Ассистент", "Новый ответ", intent())
        val active = manager.activeNotifications
        assertEquals("A new reply replaces only the notification of its own conversation", 2, active.size)
        val byId = active.associateBy { it.id }
        assertEquals("Новый ответ", byId.getValue(chatCompletedNotificationId(first)).notification.extras.getString("android.text"))
        assertEquals("Второй ответ", byId.getValue(chatCompletedNotificationId(second)).notification.extras.getString("android.text"))
    }

    @Test fun `reply notifications never replace foreground service notifications`() {
        val fixedIds = listOf(WebServerService.NOTIFICATION_ID, ChatGenerationForegroundService.NOTIFICATION_ID,
            MediaCreationForegroundService.NOTIFICATION_ID)
        fixedIds.forEach { id -> manager.notify(id, android.app.Notification.Builder(app, "test").build()) }
        repeat(20) { app.sendChatCompletedNotification(Uuid.random(), "Ассистент", "Ответ", intent()) }

        val untagged = manager.activeNotifications.filter { it.tag == null }.map { it.id }.sorted()
        assertEquals(fixedIds.sorted(), untagged)
        assertTrue(manager.activeNotifications.filter { it.tag != null }.all { it.tag == CHAT_COMPLETED_NOTIFICATION_TAG })
    }

    private fun intent(): PendingIntent = PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
}
