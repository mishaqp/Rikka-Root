package me.rerere.rikkahub.miniapps

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MiniAppRemindersTest {
    @Test fun replacesCancelsAndEnforcesTwentyDefinitions() = runBlocking {
        val root = Files.createTempDirectory("reminders").toFile()
        try {
            val source = root.resolve("source").apply { mkdirs() }
            source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
            source.resolve("index.html").writeText("<p>Water</p>")
            val store = MiniAppStore(root.resolve("installed"))
            store.install(source)
            val scheduled = mutableListOf<MiniAppReminderOccurrence>()
            val cancelled = mutableListOf<Int>()
            val reminders = MiniAppReminders(store, { scheduled.add(it); Unit }, { cancelled.add(it); Unit })
            val daily = Json.parseToJsonElement("""{"time":"8:05"}""")
            reminders.set("water", "morning", daily)
            assertEquals("Water", scheduled.single().title)
            assertNull(scheduled.single().weekday)
            reminders.set("water", "morning", Json.parseToJsonElement("""{"time":"09:30","days":[5,1,1]}"""))
            assertEquals(listOf(1327153032), cancelled)
            assertEquals(listOf(1, 5), scheduled.drop(1).map { it.weekday })
            repeat(19) { reminders.set("water", "r$it", daily) }
            try { reminders.set("water", "overflow", daily); fail("accepted 21 reminders") }
            catch (e: MiniAppException) { assertEquals("too_many_reminders", e.code) }
            reminders.remove("water", "morning")
            assertFalse(reminders.list("water").containsKey("morning"))
            assertEquals(3, cancelled.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun normalizesDaysAndDailySchedule() {
        val weekly = MiniAppReminders.normalize(Json.parseToJsonElement("""{"time":"8:05","days":[5,1,1]}"""), "Water")
        assertEquals("08:05", weekly["time"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("[1,5]"), weekly["days"])
        val daily = MiniAppReminders.normalize(Json.parseToJsonElement("""{"time":"07:00","days":[1,2,3,4,5,6,7]}"""), "Water")
        assertFalse(daily.containsKey("days"))
        assertEquals("Water", daily["title"]!!.jsonPrimitive.content)
        assertEquals(listOf(1327153032), MiniAppReminders.notificationIds("water", "morning", JsonObject(emptyMap())))
    }
    @Test fun rejectsInvalidDaysAndTime() {
        listOf("""{"time":"25:00"}""", """{"time":"08:00","days":[1.0]}""", """{"time":"08:00","days":[]}""").forEach {
            try { MiniAppReminders.normalize(Json.parseToJsonElement(it)); fail("accepted $it") } catch (e: MiniAppException) { assertEquals("invalid_reminder", e.code) }
        }
    }
}
