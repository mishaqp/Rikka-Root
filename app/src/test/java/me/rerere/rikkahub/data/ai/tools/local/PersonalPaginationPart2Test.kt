package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEventBus
import org.robolectric.shadows.ShadowUsageStatsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real resolver/provider transport and cursors; the tools still execute their permission checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PersonalPaginationPart2Test {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: RowsProvider
    private val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS, Manifest.permission.READ_CALENDAR)

    @Before fun prepare() {
        shadowOf(app).grantPermissions(*permissions)
        provider = RowsProvider()
        val authorities = listOf(ContactsContract.AUTHORITY, CallLog.AUTHORITY,
            Telephony.Sms.CONTENT_URI.authority!!, CalendarContract.AUTHORITY)
        provider.attachInfo(app, ProviderInfo().apply {
            authority = authorities.joinToString(";")
            name = RowsProvider::class.java.name
            packageName = app.packageName
        })
        authorities.forEach { ShadowContentResolver.registerProviderInternal(it, provider) }
    }

    private suspend fun result(tool: Tool, input: JsonObject): JsonObject {
        val parts = tool.execute(input)
        assertEquals("A personal read returns one structured text result", 1, parts.size)
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
    private fun input(search: Boolean = false) = buildJsonObject {
        put("limit", 2)
        if (search) put("query", "50%_\\")
    }
    private fun calendarInput() = buildJsonObject {
        put("limit", 2); put("query", "50%_\\")
        put("begin", "2026-10-18T00:00:00Z"); put("end", "2026-10-19T00:00:00Z")
    }
    private fun page(payload: JsonObject, key: String, expectedCount: Int, more: Boolean) {
        assertFalse(payload.toString(), payload.containsKey("error"))
        assertEquals(expectedCount, payload[key]?.jsonArray?.size)
        assertEquals("count describes returned rows, never an invented total", JsonPrimitive(expectedCount), payload["count"])
        assertEquals("truncated must be based on an observed extra row", JsonPrimitive(more), payload["truncated"])
        assertEquals(JsonPrimitive(more), payload["has_more"])
        if (more) {
            val advice = (payload["continuation"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            assertTrue("Explain how to refine the query or raise its supported limit in Russian: $payload",
                Regex("[А-Яа-яЁё]").containsMatchIn(advice))
        }
        assertFalse("There is no offset API to promise", payload.containsKey("next_offset"))
        assertFalse("There is no cursor API to promise", payload.containsKey("next_cursor"))
        assertTrue("Every result cursor must be closed, including lookahead", provider.cursors.all { it.isClosed })
    }

    @Test fun contactsListLooksAheadBeyondProviderLimitWithoutReturningTheExtraContact() = runBlocking {
        val payload = result(listContactsTool(app), input())
        page(payload, "contacts", 2, true)
        val query = provider.requests.first()
        assertEquals("3", query.uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY))
        assertEquals(ContactsContract.Contacts.CONTENT_URI.path, query.uri.path)
        assertEquals("${ContactsContract.Contacts.DISPLAY_NAME} ASC", query.order)
        assertEquals(listOf(1L, 2L), payload.getValue("contacts").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        assertFalse(payload.toString().contains("Contact 3"))
    }

    @Test fun contactsSearchPreservesLiteralFilterAndDetectsTheProviderExtraRow() = runBlocking {
        val payload = result(searchContactsTool(app), input(search = true))
        page(payload, "contacts", 2, true)
        val query = provider.requests.first()
        assertEquals("50%_\\", query.uri.lastPathSegment)
        assertEquals("3", query.uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY))
        assertNull(query.selection)
    }

    @Test fun callLogLooksAheadAndPreservesTypeSinceAndDescendingOrder() = runBlocking {
        val payload = result(callLogTool(app), buildJsonObject {
            put("limit", 2); put("since_ms", 1000L); put("type", "missed")
        })
        page(payload, "calls", 2, true)
        val query = provider.requests.single()
        assertEquals("3", query.uri.getQueryParameter("limit"))
        assertEquals("${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?", query.selection)
        assertEquals(listOf("3", "1000"), query.args)
        assertEquals("${CallLog.Calls.DATE} DESC", query.order)
        assertEquals(listOf(3000L, 2000L), payload.getValue("calls").jsonArray.map { it.jsonObject.getValue("date_ms").jsonPrimitive.long })
    }

    @Test fun smsInboxReportsAnExtraRowAndPreservesBodyAndSinceFilter() = runBlocking {
        provider.longSms = true
        val payload = result(listSmsInboxTool(app), buildJsonObject { put("limit", 2); put("since_ms", 1000L) })
        page(payload, "messages", 2, true)
        val query = provider.requests.single()
        assertEquals(Telephony.Sms.Inbox.CONTENT_URI, query.uri)
        assertEquals("date >= ?", query.selection)
        assertEquals(listOf("1000"), query.args)
        assertEquals("date DESC", query.order)
        val first = payload.getValue("messages").jsonArray.first().jsonObject
        assertEquals(16384, first.getValue("body").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), first["body_truncated"])
    }

    @Test fun smsSearchReportsAnExtraRowWithoutBroadeningLiteralWildcards() = runBlocking {
        val payload = result(searchSmsTool(app), buildJsonObject {
            put("limit", 2); put("since_ms", 1000L); put("query", "50%_\\")
        })
        page(payload, "messages", 2, true)
        val query = provider.requests.single()
        assertEquals("date >= ? AND body LIKE ? ESCAPE '\\'", query.selection)
        assertEquals(listOf("1000", "%50\\%\\_\\\\%"), query.args)
        assertEquals("date DESC", query.order)
    }

    @Test fun calendarReportsTheObservedExtraEventAndKeepsItsRangeAndLiteralFilter() = runBlocking {
        val payload = result(buildCalendarQueryTool(app), calendarInput())
        page(payload, "events", 2, true)
        val query = provider.requests.single()
        assertEquals(listOf("1792281600000", "1792368000000"), query.uri.pathSegments.takeLast(2))
        assertEquals("${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'", query.selection)
        assertEquals(listOf("%50\\%\\_\\\\%"), query.args)
        assertEquals("${CalendarContract.Instances.BEGIN} ASC", query.order)
        assertEquals(listOf(1L, 2L), payload.getValue("events").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long })
    }

    @Test fun exactLimitAndEmptyResultsNeverClaimThereAreMoreRows() = runBlocking {
        for (rowCount in listOf(2, 0)) {
            provider.rows = rowCount
            for ((tool, key, args) in reads()) {
                provider.clearTracking()
                page(result(tool, args), key, rowCount, false)
            }
        }
    }

    @Test fun contactPhoneAndEmailCapsDiscloseNestedTruncationWithoutReadingNextContactDetails() = runBlocking {
        provider.rows = 1; provider.details = 21
        val payload = result(listContactsTool(app), input())
        val contact = payload.getValue("contacts").jsonArray.single().jsonObject
        assertEquals(20, contact.getValue("phones").jsonArray.size)
        assertEquals(20, contact.getValue("emails").jsonArray.size)
        assertEquals(JsonPrimitive(true), contact["phones_truncated"])
        assertEquals(JsonPrimitive(true), contact["emails_truncated"])
        assertTrue(provider.requests.drop(1).all { it.args == listOf("1") })
        assertTrue(provider.cursors.all { it.isClosed })
    }

    @Test fun revokedPersonalPermissionsReturnGuidanceBeforeAnyProviderQuery() = runBlocking {
        shadowOf(app).denyPermissions(*permissions)
        for ((tool, _, args) in reads()) {
            provider.clearTracking()
            val payload = result(tool, args)
            assertTrue(payload.toString(), payload.containsKey("error"))
            assertTrue("Permission failure explains the manual next step in Russian", Regex("[А-Яа-яЁё]").containsMatchIn(payload.toString()))
            assertTrue("Denied read must not query the provider", provider.requests.isEmpty())
        }
    }

    @Test fun contactsDiscloseTruncatedNamesNumbersAndEmailsWithoutInventingFullValues() = runBlocking {
        provider.rows = 1; provider.details = 1; provider.longScalars = true
        val payload = result(listContactsTool(app), input())
        val contact = payload.getValue("contacts").jsonArray.single().jsonObject
        assertEquals(4096, contact.getValue("display_name").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), contact["display_name_truncated"])
        val phone = contact.getValue("phones").jsonArray.single().jsonObject
        assertEquals(4096, phone.getValue("number").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), phone["number_truncated"])
        val email = contact.getValue("emails").jsonArray.single().jsonObject
        assertEquals(4096, email.getValue("address").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), email["address_truncated"])
        assertTrue(provider.cursors.all { it.isClosed })
    }

    @Test fun callLogDisclosesTruncatedCallerNameAndNumber() = runBlocking {
        provider.rows = 1; provider.longScalars = true
        val payload = result(callLogTool(app), input())
        val call = payload.getValue("calls").jsonArray.single().jsonObject
        assertEquals(256, call.getValue("number").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), call["number_truncated"])
        assertEquals(4096, call.getValue("name").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), call["name_truncated"])
        assertTrue(provider.cursors.all { it.isClosed })
    }

    @Test fun smsDisclosesTruncatedSenderAddressWithoutChangingTheBody() = runBlocking {
        provider.rows = 1; provider.longScalars = true
        val payload = result(listSmsInboxTool(app), input())
        val sms = payload.getValue("messages").jsonArray.single().jsonObject
        assertEquals(256, sms.getValue("address").jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(true), sms["address_truncated"])
        assertEquals(JsonPrimitive("50%_\\ message 1"), sms["body"])
        assertTrue(provider.cursors.all { it.isClosed })
    }

    private fun usageEvents(): Pair<Long, Long> {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        val manager = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = shadowOf(manager)
        val begin = System.currentTimeMillis() - 100_000
        fun event(packageName: String, offset: Long, type: Int) {
            events.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(packageName).setClass("Activity").setTimeStamp(begin + offset)
                .setEventType(type).build())
        }
        event("test.first", 0, UsageEvents.Event.ACTIVITY_RESUMED)
        event("test.first", 30_000, UsageEvents.Event.ACTIVITY_PAUSED)
        event("test.second", 30_000, UsageEvents.Event.ACTIVITY_RESUMED)
        event("test.second", 50_000, UsageEvents.Event.ACTIVITY_PAUSED)
        event("test.third", 50_000, UsageEvents.Event.ACTIVITY_RESUMED)
        event("test.third", 60_000, UsageEvents.Event.ACTIVITY_PAUSED)
        return begin to begin + 60_000
    }

    @Test fun screenTimeTopReportsOmittedAppsButKeepsTheAllAppTotal() = runBlocking {
        val (begin, end) = usageEvents()
        val payload = result(buildScreenTimeTool(app, AppEventBus()), buildJsonObject {
            put("begin", begin.toString()); put("end", end.toString()); put("top", 1)
        })
        assertFalse(payload.toString(), payload.containsKey("error"))
        assertEquals(JsonPrimitive(60_000L), payload["total_ms"])
        val apps = payload.getValue("apps").jsonArray
        assertEquals(1, apps.size)
        assertEquals(JsonPrimitive("test.first"), apps.single().jsonObject["package"])
        assertEquals(JsonPrimitive(30_000L), apps.single().jsonObject["total_ms"])
        assertEquals(JsonPrimitive(1), payload["returned"])
        assertEquals(JsonPrimitive(3), payload["total_apps"])
        assertEquals(JsonPrimitive(true), payload["truncated"])
        assertEquals(JsonPrimitive(true), payload["has_more"])
        assertTrue(Regex("[А-Яа-яЁё]").containsMatchIn((payload["continuation"] as? JsonPrimitive)?.contentOrNull.orEmpty()))
        assertFalse(payload.containsKey("next_offset"))
        assertFalse(payload.containsKey("next_cursor"))
    }

    @Test fun screenTimeExactAndLargerTopDoNotClaimMoreApps() = runBlocking {
        val (begin, end) = usageEvents()
        for (top in listOf(3, 5)) {
            val payload = result(buildScreenTimeTool(app, AppEventBus()), buildJsonObject {
                put("begin", begin.toString()); put("end", end.toString()); put("top", top)
            })
            assertEquals(JsonPrimitive(60_000L), payload["total_ms"])
            assertEquals(3, payload.getValue("apps").jsonArray.size)
            assertEquals(JsonPrimitive(3), payload["returned"])
            assertEquals(JsonPrimitive(3), payload["total_apps"])
            assertEquals(JsonPrimitive(false), payload["truncated"])
            assertEquals(JsonPrimitive(false), payload["has_more"])
        }
    }

    private fun reads(): List<Triple<Tool, String, JsonObject>> = listOf(
        Triple(listContactsTool(app), "contacts", input()),
        Triple(searchContactsTool(app), "contacts", input(search = true)),
        Triple(callLogTool(app), "calls", input()),
        Triple(listSmsInboxTool(app), "messages", input()),
        Triple(searchSmsTool(app), "messages", input(search = true)),
        Triple(buildCalendarQueryTool(app), "events", calendarInput()),
    )

    private data class Request(val uri: Uri, val selection: String?, val args: List<String>?, val order: String?)
    private class RowsProvider : ContentProvider() {
        var rows = 3
        var details = 0
        var longSms = false
        var longScalars = false
        val requests = mutableListOf<Request>()
        val cursors = mutableListOf<MatrixCursor>()
        fun clearTracking() { requests.clear(); cursors.clear() }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            requests += Request(uri, selection, selectionArgs?.toList(), sortOrder)
            val columns = requireNotNull(projection)
            val cursor = MatrixCursor(columns).also(cursors::add)
            val detail = uri.authority == ContactsContract.AUTHORITY && columns.size == 2 && columns[0] != ContactsContract.Contacts._ID
            val number = if (detail) details else rows
            // Emulate Android's provider-side cap: requesting only N hides the N+1st row.
            val providerLimit = uri.getQueryParameter("limit")?.toInt() ?: Int.MAX_VALUE
            repeat(minOf(number, providerLimit)) { index ->
                val id = index + 1L
                cursor.addRow(columns.map { column ->
                    when (uri.authority) {
                        ContactsContract.AUTHORITY -> when {
                            detail && column == columns[0] -> if (longScalars) "d".repeat(4097) else "detail $id"
                            detail -> 1
                            column == ContactsContract.Contacts._ID -> id
                            else -> if (longScalars) "c".repeat(4097) else "Contact $id"
                        }
                        CallLog.AUTHORITY -> when (column) {
                            "_id" -> id; "number" -> if (longScalars) "1".repeat(257) else "+1234567"
                            "name" -> if (longScalars) "c".repeat(4097) else "Caller $id"
                            "type" -> CallLog.Calls.MISSED_TYPE; "date" -> (rows - index) * 1000L; else -> 60L
                        }
                        Telephony.Sms.CONTENT_URI.authority -> when (column) {
                            "_id" -> id; "address" -> if (longScalars) "1".repeat(257) else "+1234567"
                            "body" -> if (longSms) "x".repeat(16385) else "50%_\\ message $id"
                            "date" -> (rows - index) * 1000L; else -> 1
                        }
                        else -> when (column) {
                            CalendarContract.Instances.EVENT_ID -> id
                            CalendarContract.Instances.TITLE -> "50%_\\ event $id"
                            CalendarContract.Instances.DESCRIPTION -> "notes $id"
                            CalendarContract.Instances.EVENT_LOCATION -> "location $id"
                            CalendarContract.Instances.BEGIN -> 1792281600000L + index * 60000L
                            CalendarContract.Instances.END -> 1792281660000L + index * 60000L
                            CalendarContract.Instances.ALL_DAY -> 0
                            else -> "calendar"
                        }
                    }
                }.toTypedArray<Any?>())
            }
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
