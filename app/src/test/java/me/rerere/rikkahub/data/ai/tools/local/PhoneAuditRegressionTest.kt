package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.speech.tts.TextToSpeech
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.readClipboardText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PhoneAuditRegressionTest {
    private val context = mockk<Context>(relaxed = true)
    private suspend fun result(tool: Tool, input: JsonObject = buildJsonObject {}): JsonObject =
        Json.parseToJsonElement((tool.execute(input).single() as UIMessagePart.Text).text).jsonObject

    private fun grantUsagePermission(allowed: Boolean) {
        val ops = mockk<AppOpsManager>()
        every { context.getSystemService(Context.APP_OPS_SERVICE) } returns ops
        every { context.packageName } returns "test.phone"
        every { ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, any(), "test.phone") } returns
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED
    }

    @Test fun futureScreenTimeEndIsClampedBeforeTheAndroidQuery() = runBlocking {
        grantUsagePermission(true)
        val manager = mockk<UsageStatsManager>()
        val events = mockk<UsageEvents>()
        every { events.hasNextEvent() } returns false
        var queriedEnd = Long.MAX_VALUE
        every { manager.queryEvents(any(), any()) } answers { queriedEnd = secondArg(); events }
        every { context.getSystemService(Context.USAGE_STATS_SERVICE) } returns manager
        val before = System.currentTimeMillis()
        val payload = result(buildScreenTimeTool(context, AppEventBus()), buildJsonObject {
            put("begin", (before - 60_000).toString())
            put("end", (before + 86_400_000).toString())
        })
        val after = System.currentTimeMillis()
        assertTrue("Query end $queriedEnd must be the current instant", queriedEnd in before..after)
        assertTrue(ZonedDateTime.parse(payload.getValue("end").jsonPrimitive.content).toInstant().toEpochMilli() <= after)
    }

    @Test fun deniedScreenTimeDoesNotOpenSettings() = runBlocking {
        grantUsagePermission(false)
        val bus = AppEventBus()
        val emitted = mutableListOf<AppEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { emitted += it } }
        try {
            val payload = result(buildScreenTimeTool(context, bus))
            assertEquals("NO_PERMISSION", payload.getValue("error").jsonPrimitive.content)
            kotlinx.coroutines.yield()
            assertTrue("Tool must not navigate without the user", emitted.isEmpty())
        } finally { collector.cancelAndJoin() }
    }

    @Test fun screenTimeErrorsGiveRussianGuidanceWithoutEchoingRawInput() = runBlocking {
        grantUsagePermission(true)
        val tool = buildScreenTimeTool(context, AppEventBus())
        val invalid = result(tool, buildJsonObject { put("begin", "private-token") })
        assertEquals("INVALID_TIME", invalid.getValue("error").jsonPrimitive.content)
        val message = invalid.getValue("message").jsonPrimitive.content
        assertTrue(message, message.any { it in 'А'..'я' })
        assertFalse(message.contains("private-token"))
        val range = result(tool, buildJsonObject { put("begin", "1000"); put("end", "999") })
        assertEquals("INVALID_RANGE", range.getValue("error").jsonPrimitive.content)
        assertTrue(range.toString(), range.getValue("message").jsonPrimitive.content.any { it in 'А'..'я' })
    }

    @Test fun screenTimePermissionErrorExplainsManualGrantInRussian() = runBlocking {
        grantUsagePermission(false)
        val payload = result(buildScreenTimeTool(context, AppEventBus()))
        assertTrue(payload.toString(), payload.getValue("message").jsonPrimitive.content.any { it in 'А'..'я' })
        assertFalse(buildScreenTimeTool(context, AppEventBus()).description.contains("opened automatically"))
    }

    @Test fun unavailableScreenTimeServiceReturnsRussianErrorInsteadOfThrowing() = runBlocking {
        grantUsagePermission(true)
        every { context.getSystemService(Context.USAGE_STATS_SERVICE) } returns null
        val outcome = runCatching { result(buildScreenTimeTool(context, AppEventBus())) }
        assertTrue("Unavailable Android service must return a tool result: $outcome", outcome.isSuccess)
        val error = outcome.getOrThrow().getValue("error").jsonPrimitive.content
        assertTrue(error, error.any { it in 'А'..'я' })
    }

    @Test fun clipboardUriAndIntentItemsAreCoercedToText() {
        val app = RuntimeEnvironment.getApplication()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val uri = Uri.parse("content://test.phone/images/42")
        clipboard.setPrimaryClip(ClipData.newRawUri("image", uri))
        // Android intentionally yields empty text for content images without a text stream.
        assertEquals("", app.readClipboardText())
        val webUri = Uri.parse("https://example.org/images/42")
        clipboard.setPrimaryClip(ClipData.newRawUri("web", webUri))
        assertEquals(webUri.toString(), app.readClipboardText())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org"))
        clipboard.setPrimaryClip(ClipData.newIntent("intent", intent))
        assertEquals(intent.toUri(Intent.URI_INTENT_SCHEME), app.readClipboardText())
        clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""))
        assertEquals("", app.readClipboardText())
    }

    private fun calendarResolver(): ContentResolver {
        every { context.checkPermission(Manifest.permission.READ_CALENDAR, any(), any()) } returns PackageManager.PERMISSION_GRANTED
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        return resolver
    }

    @Test fun calendarBeginWithoutEndUsesExactlyOneDayAndIgnoresRangePreset() = runBlocking {
        val resolver = calendarResolver()
        every { resolver.query(any(), any(), any(), any(), any<String>()) } returns null
        val start = ZonedDateTime.now().plusDays(30).withNano(0)
        for (range in listOf("today", "week", "month")) {
            val payload = result(buildCalendarQueryTool(context), buildJsonObject {
                put("begin", start.toOffsetDateTime().toString()); put("range", range)
            })
            assertFalse(payload.toString(), payload.containsKey("error"))
            assertEquals(start.plusDays(1).toInstant(), ZonedDateTime.parse(payload.getValue("range_end").jsonPrimitive.content).toInstant())
        }
    }

    @Test fun calendarKeywordTreatsPercentUnderscoreAndBackslashAsLiteralCharacters() = runBlocking {
        val resolver = calendarResolver()
        val database = SQLiteDatabase.create(null)
        try {
            database.execSQL("CREATE TABLE events (event_id INTEGER, title TEXT, description TEXT, eventLocation TEXT, begin INTEGER, end INTEGER, allDay INTEGER, calendar_displayName TEXT)")
            val keyword = "50%_\\done"
            database.execSQL("INSERT INTO events VALUES (1, ?, '', '', 1000, 2000, 0, 'Test')", arrayOf("prefix $keyword suffix"))
            database.execSQL("INSERT INTO events VALUES (2, ?, '', '', 1000, 2000, 0, 'Test')", arrayOf("50AAAx\\done"))
            every { resolver.query(any(), any(), any(), any(), any<String>()) } answers {
                val selection = thirdArg<String?>()
                val arguments = arg<Array<String>?>(3)
                database.rawQuery("SELECT event_id,title,description,eventLocation,begin,end,allDay,calendar_displayName FROM events WHERE $selection", arguments)
            }
            val payload = result(buildCalendarQueryTool(context), buildJsonObject { put("query", keyword) })
            val events = payload.getValue("events").jsonArray
            assertEquals("Only the literal matching title must be returned: $payload", 1, events.size)
            assertEquals("prefix $keyword suffix", events.single().jsonObject.getValue("title").jsonPrimitive.content)
        } finally { database.close() }
    }

    @Test fun calendarDocumentationDoesNotPromiseNowOrAutomaticPermissionRequests() {
        val tool = buildCalendarQueryTool(context)
        val end = (tool.parameters() as InputSchema.Obj).properties.getValue("end").jsonObject
        assertFalse(end.toString(), end.toString().contains("Defaults to now"))
        assertFalse(tool.description, tool.description.contains("triggered automatically"))
        assertFalse(buildCalendarCreateTool(context).description.contains("triggered automatically"))
    }

    @Test fun oversizedTtsIsRejectedBeforeSpeakIsEmittedAndLimitSizedTextIsAccepted() = runBlocking {
        val bus = AppEventBus()
        // These constructor dependencies are never called by execute; the event bus is real.
        val tool = buildTextToSpeechTool(bus, mockk(), mockk())
        val emitted = mutableListOf<AppEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { emitted += it } }
        try {
            // Long text is chunked by the app TTS, so the bound is the tool cap, not one Android utterance.
            val limit = MAX_SPEAK_CHARS
            assertTrue(limit > TextToSpeech.getMaxSpeechInputLength())
            assertTrue(limit > 0)
            val rejected = result(tool, buildJsonObject { put("text", "я".repeat(limit + 1)) })
            kotlinx.coroutines.yield()
            assertTrue("No playback event for rejected text", emitted.isEmpty())
            val error = rejected.getValue("error").jsonPrimitive.content
            assertTrue(error, error.any { it in 'А'..'я' } && error.contains(limit.toString()))
            val accepted = result(tool, buildJsonObject { put("text", "я".repeat(limit)) })
            kotlinx.coroutines.yield()
            assertEquals(true, accepted.getValue("success").jsonPrimitive.boolean)
            assertEquals(listOf(AppEvent.Speak("я".repeat(limit))), emitted)
        } finally { collector.cancelAndJoin() }
    }
}
