package me.rerere.rikkahub.miniapps

import android.Manifest
import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MiniAppCalendarTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: RecordingCalendar
    @Before fun setup() {
        provider = RecordingCalendar()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
    private fun execute(method: String, args: String) = runBlocking {
        MiniAppCalendar(context).execute(method, Json.parseToJsonElement(args).jsonObject)
    }
    @Test fun deniedPermissionDoesNoIo() {
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.READ_CALENDAR)
        assertEquals("NO_PERMISSION", execute("queryCalendar", "{}")["error"]!!.jsonPrimitive.content)
        assertEquals("NO_PERMISSION", execute("createCalendarEvent", """{"title":"a","start":"2026-01-01"}""")["error"]!!.jsonPrimitive.content)
        assertEquals(0, provider.queries)
        assertTrue(provider.inserts.isEmpty())
    }
    @Test fun literalQueryAndCalendarFilterAndCalendarListing() {
        val result = execute("queryCalendar", """{"begin":"2026-01-01","end":"2026-01-02","query":"100%_","calendar_id":7,"include_calendars":true}""")
        assertEquals(listOf("%100\\%\\_%", "7"), provider.instanceArgs)
        assertTrue(provider.instanceSelection!!.contains("calendar_id = ?"))
        assertEquals(1, result["calendars"]!!.jsonArray.size)
    }
    @Test fun chosenCalendarMustBeWritable() {
        provider.writable = false
        assertEquals("INVALID_CALENDAR", execute("createCalendarEvent", """{"title":"a","start":"2026-01-01","calendar_id":7}""")["error"]!!.jsonPrimitive.content)
        assertTrue(provider.inserts.isEmpty())
    }
    @Test fun allDayUsesUtcAndActualReminderRecords() {
        val result = execute("createCalendarEvent", """{"title":"a","start":"2026-01-01","end":"2026-01-03","all_day":true,"calendar_id":7,"reminders":[-15,"30",15]}""")
        assertEquals(42, result["event_id"]!!.jsonPrimitive.int)
        val event = provider.inserts.first().second
        assertEquals("UTC", event.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), event.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(Json.parseToJsonElement("[15,30]"), result["reminders"])
        val reminders = provider.inserts.filter { it.first == CalendarContract.Reminders.CONTENT_URI }
        assertEquals(listOf(15,30), reminders.map { it.second.getAsInteger(CalendarContract.Reminders.MINUTES) })
        assertTrue(reminders.all { it.second.getAsLong(CalendarContract.Reminders.EVENT_ID) == 42L })
    }
    @Test fun invalidRangeDoesNotInsert() {
        assertEquals("INVALID_RANGE", execute("createCalendarEvent", """{"title":"a","start":"2026-01-03","end":"2026-01-01"}""")["error"]!!.jsonPrimitive.content)
        assertTrue(provider.inserts.isEmpty())
    }
    @Test fun partialReminderFailureReportsSavedEventWithoutRetry() {
        provider.rejectMinute = 30
        val result = execute("createCalendarEvent", """{"title":"a","start":"2026-01-01","calendar_id":7,"reminders":[15,30]}""")
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(Json.parseToJsonElement("[15]"), result["reminders"])
        assertEquals(Json.parseToJsonElement("[15,30]"), result["reminders_requested"])
        assertNotNull(result["warning"])
        assertEquals(1, provider.inserts.count { it.first == CalendarContract.Events.CONTENT_URI })
    }
    class RecordingCalendar : ContentProvider() {
        var queries = 0
        var writable = true
        var rejectMinute: Int? = null
        var instanceSelection: String? = null
        var instanceArgs: List<String>? = null
        val inserts = mutableListOf<Pair<Uri, ContentValues>>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
            queries++
            val columns = projection!!.map { it }.toTypedArray()
            val cursor = MatrixCursor(columns)
            if (uri.toString().contains("instances")) {
                instanceSelection = selection; instanceArgs = selectionArgs?.toList()
            } else if (columns.size == 1) {
                if (columns[0] == CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) cursor.addRow(arrayOf(if (writable) 700 else 100))
                else cursor.addRow(arrayOf(7))
            } else cursor.addRow(arrayOf<Any>(7, "Personal", "account", 700, 1, 1))
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            inserts.add(uri to ContentValues(values!!))
            if (uri == CalendarContract.Reminders.CONTENT_URI && values.getAsInteger(CalendarContract.Reminders.MINUTES) == rejectMinute) return null
            return ContentUris.withAppendedId(uri, 42)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 1
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }
}
