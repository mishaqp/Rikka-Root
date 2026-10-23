package me.rerere.rikkahub.miniapps

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MiniAppAndroidTest {
    @Test fun deliveryPostsAnAppOpeningNotificationRearmsAndRemovalCancelsBoth() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        notifications.setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        val root = Files.createTempDirectory("mini-reminder-delivery").toFile()
        val store = MiniAppStore(root)
        var now = ZonedDateTime.parse("2026-10-23T08:00:00Z")
        val native = MiniAppAndroidReminders(context, store) { now }
        try {
            installWater(store, root)
            native.reminders.set("water", "drink", buildJsonObject { put("time", "09:00"); put("title", "Water"); put("body", "Drink") })
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
            val first = alarms.scheduledAlarms.single()
            val firstIntent = shadowOf(first.operation).savedIntent
            val id = firstIntent.getIntExtra(MiniAppAndroidReminders.EXTRA_ID, -1)
            val at = firstIntent.getLongExtra(MiniAppAndroidReminders.EXTRA_AT, -1)
            now = now.withHour(9).withMinute(1)
            native.fire("water", id, at)
            val notification = notifications.allNotifications.single()
            assertEquals("Water", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("Drink", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
            assertEquals("rikkahub://app/water", shadowOf(notification.contentIntent).savedIntent.dataString)
            assertEquals(ZonedDateTime.parse("2026-10-24T09:00:00Z").toInstant().toEpochMilli(), alarms.scheduledAlarms.single().triggerAtMs)
            assertEquals(0L, alarms.scheduledAlarms.single().windowLengthMs)
            native.reminders.remove("water", "drink")
            assertTrue(alarms.scheduledAlarms.isEmpty())
            assertTrue(notifications.allNotifications.isEmpty())
            native.fire("water", id, at)
            assertTrue(alarms.scheduledAlarms.isEmpty())
            assertTrue(notifications.allNotifications.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun fallbackIconsKeepWholeEmojiAndCombiningCharacters() {
        assertEquals("💧", miniAppInitial("💧 Вода"))
        assertEquals("❤️", miniAppInitial("❤️ Notes"))
        assertEquals("E\u0301", miniAppInitial("e\u0301 notes"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun recoveryCannotOverwriteAConcurrentReminderEdit() = runTest {
        val root = Files.createTempDirectory("mini-reminder-race").toFile()
        val store = MiniAppStore(root, StandardTestDispatcher(testScheduler))
        val backend = PausingAlarms()
        val native = MiniAppAndroidReminders(ApplicationProvider.getApplicationContext(), store, backend) { ZonedDateTime.parse("2026-10-23T08:00:00Z") }
        try {
            installWater(store, root)
            native.reminders.set("water", "drink", buildJsonObject { put("time", "09:00") })
            backend.pauseNext = true
            val recovery = launch { native.restore() }
            runCurrent()
            assertTrue(backend.entered.isCompleted)
            val edit = launch { native.reminders.set("water", "drink", buildJsonObject { put("time", "10:00") }) }
            runCurrent()
            assertFalse("An edit must wait for the in-flight recovery occurrence", edit.isCompleted)
            backend.release.complete(Unit)
            recovery.join(); edit.join()
            assertEquals(ZonedDateTime.parse("2026-10-23T10:00:00Z").toInstant().toEpochMilli(), backend.active.values.single())
        } finally { backend.release.complete(Unit); coroutineContext.cancelChildren(); runCurrent(); root.deleteRecursively() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun deletionWaitsForDeliveryAndCancelsItsRearmedOccurrence() = runTest {
        val root = Files.createTempDirectory("mini-reminder-delete").toFile()
        val store = MiniAppStore(root, StandardTestDispatcher(testScheduler))
        val backend = PausingAlarms()
        var now = ZonedDateTime.parse("2026-10-23T08:00:00Z")
        val native = MiniAppAndroidReminders(ApplicationProvider.getApplicationContext(), store, backend) { now }
        try {
            installWater(store, root)
            native.reminders.set("water", "drink", buildJsonObject { put("time", "09:00") })
            val (id, at) = backend.active.entries.single().let { it.key to it.value }
            now = now.withHour(9).withMinute(1)
            backend.pauseNext = true
            val delivery = launch { native.fire("water", id, at) }
            runCurrent()
            assertTrue(backend.entered.isCompleted)
            val deletion = launch { store.delete("water") }
            runCurrent()
            assertFalse("Deletion must wait for current delivery before cancelling alarms", deletion.isCompleted)
            backend.release.complete(Unit)
            delivery.join(); deletion.join()
            assertNull(store.byId("water"))
            assertTrue(backend.active.isEmpty())
        } finally { backend.release.complete(Unit); coroutineContext.cancelChildren(); runCurrent(); root.deleteRecursively() }
    }

    @Test fun assistantModelWinsAndMissingModelFallsBackToDefault() {
        val default = Model(modelId = "default")
        val selected = Model(modelId = "assistant")
        val assistant = Assistant(chatModelId = selected.id)
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(default, selected))),
            assistants = listOf(assistant), assistantId = assistant.id, chatModelId = default.id)
        assertEquals(selected, miniAppAskModel(settings))
        assertEquals(default, miniAppAskModel(settings.copy(assistants = listOf(assistant.copy(chatModelId = null)))))
        assertEquals(default, miniAppAskModel(settings.copy(assistants = emptyList())))
        assertNull(miniAppAskModel(settings.copy(providers = emptyList())))
    }

    @Test fun nextReminderUsesLocalWallClockAcrossDstAndWeekBoundaries() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, zone)
        val next = nextMiniAppReminder(2, 30, null, spring)
        assertEquals("2026-03-29T03:30+02:00[Europe/Berlin]", next.toString())
        assertTrue(next.isAfter(spring))
        val monday = ZonedDateTime.of(2026, 10, 19, 9, 0, 0, 0, zone)
        assertEquals(monday.plusWeeks(1), nextMiniAppReminder(9, 0, 1, monday))
        assertEquals(monday.plusDays(1).withHour(8), nextMiniAppReminder(8, 0, 2, monday))
        assertEquals(monday.withHour(10), nextMiniAppReminder(10, 0, null, monday))
    }

    @Test fun definitionsRecoverAsInexactAlarmsAndDeletionCancelsThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("mini-native").toFile()
        val src = Files.createTempDirectory("mini-native-src").toFile()
        try {
            src.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
            src.resolve("index.html").writeText("<p>Water</p>")
            val store = MiniAppStore(root)
            store.install(src)
            val now = ZonedDateTime.parse("2026-10-23T08:00:00Z")
            val native = MiniAppAndroidReminders(context, store) { now }
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            ShadowAlarmManager.setAutoSchedule(false)
            native.reminders.set("water", "drink", buildJsonObject {
                put("time", "09:00"); put("days", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(5))))
            })
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
            assertEquals(2, alarms.scheduledAlarms.size)
            assertTrue(alarms.scheduledAlarms.all { it.isAllowWhileIdle && it.windowLengthMs != 0L })
            val identities = alarms.scheduledAlarms.map { shadowOf(it.operation).savedIntent.dataString }.toSet()
            assertEquals(2, identities.size)
            // Recovery reads durable JSON and replaces the existing PendingIntents.
            native.restore()
            assertEquals(2, alarms.scheduledAlarms.size)
            store.delete("water")
            assertTrue(alarms.scheduledAlarms.isEmpty())
        } finally { root.deleteRecursively(); src.deleteRecursively() }
    }

    @Test fun notificationAndShortcutIntentsHaveDistinctValidatedAppIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = MiniAppLauncher.intent(context, "water")
        val second = MiniAppLauncher.intent(context, "notes")
        assertEquals("rikkahub://app/water", first.dataString)
        assertEquals("rikkahub://app/notes", second.dataString)
        assertNotEquals(first.data, second.data)
        assertThrows(IllegalArgumentException::class.java) { MiniAppLauncher.intent(context, "../outside") }
        assertTrue(MiniAppLauncher.isArchiveName("TRACKER.MORUAPP"))
        assertFalse(MiniAppLauncher.isArchiveName("tracker.zip"))
    }

    private suspend fun installWater(store: MiniAppStore, root: java.io.File) {
        val source = root.resolve(".source").apply { mkdirs() }
        source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
        source.resolve("index.html").writeText("<p>Water</p>")
        store.install(source)
    }

    private class PausingAlarms : MiniAppAlarmBackend {
        var pauseNext = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = mutableMapOf<Int, Long>()
        override suspend fun schedule(occurrence: MiniAppReminderOccurrence, atMillis: Long) {
            if (pauseNext) { pauseNext = false; entered.complete(Unit); release.await() }
            active[occurrence.id] = atMillis
        }
        override suspend fun cancel(id: Int) { active.remove(id) }
    }
}
