package me.rerere.rikkahub.miniapps

import com.dokar.quickjs.QuickJs
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Execute the shipped JS and native dispatcher together, rather than matching script strings. */
class MiniAppScriptIntegrationTest {
    @Test fun javascriptStoragePromisesRoundTripExactJsonAndExternalChangeEvents() = runBlocking {
        withRuntime { js, store, bridge ->
            val value = buildJsonObject { put("text", "a b\n  B 'quoted' \"double\" 2>/dev/null"); put("n", 3) }
            assertEquals(JsonNull, call(js, bridge, "moru.storage.set('journal', $value)"))
            assertEquals(value, store.storageGet("tracker", "journal"))
            assertEquals(value, call(js, bridge, "moru.storage.get('journal')"))
            assertEquals(JsonArray(listOf(JsonPrimitive("journal"))), call(js, bridge, "moru.storage.keys()"))
            js.evaluate<Any?>(MiniAppBridge.changedScript("journal"))
            assertEquals("journal", js.evaluate<String>("events[0].detail.key"))
            assertEquals(JsonNull, call(js, bridge, "moru.storage.remove('journal')"))
            assertEquals(JsonNull, call(js, bridge, "moru.storage.get('journal')"))
        }
    }

    @Test fun allHostApisUseCompatibleWireFormatAndNativeErrorsRejectPromises() = runBlocking {
        val notifications = mutableListOf<Pair<String, String>>()
        val scheduled = mutableListOf<MiniAppReminderOccurrence>()
        val calendarCalls = mutableListOf<Pair<String, JsonObject>>()
        withRuntime(host = { store -> MiniAppHost(
            ask = { prompt, system -> "$system: $prompt" },
            notify = { title, body -> notifications += title to body },
            reminders = MiniAppReminders(store, schedule = { scheduled += it }, cancel = {}),
            calendar = { method, args -> calendarCalls += method to args; buildJsonObject { put("success", true) } },
            fetch = MiniAppFetch(OkHttpClient.Builder().addInterceptor { chain -> Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"temperature\":21}".toResponseBody()).build() }.build()),
        ) }) { js, _, bridge ->
            assertEquals(JsonPrimitive("Explain: hello"), call(js, bridge, "moru.ai.ask('hello', {system:'Explain'})"))
            call(js, bridge, "moru.notify('Water', 'Drink')")
            assertEquals(listOf("Water" to "Drink"), notifications)
            call(js, bridge, "moru.reminders.set('drink', {time:'9:15',days:[1,5],title:'Water'})")
            assertEquals(listOf(1, 5), scheduled.map { it.weekday })
            assertTrue(call(js, bridge, "moru.reminders.list()").jsonObject.containsKey("drink"))
            call(js, bridge, "moru.reminders.remove('drink')")
            call(js, bridge, "moru.calendar.list({range:'today'})")
            call(js, bridge, "moru.calendar.add({title:'Event',start:'2026-10-23T10:00:00Z'})")
            assertEquals(listOf("queryCalendar", "createCalendarEvent"), calendarCalls.map { it.first })
            val response = call(js, bridge, "moru.fetch('https://api.example.com/weather').then(function(r){return {status:r.status,json:r.json(),text:r.text()};})").jsonObject
            assertEquals(JsonPrimitive(200), response["status"])
            assertEquals(JsonPrimitive(21), response["json"]!!.jsonObject["temperature"])
            assertEquals(JsonPrimitive("{\"temperature\":21}"), response["text"])
            assertEquals(JsonPrimitive("tracker"), call(js, bridge, "moru.app.info()").jsonObject["id"])
            val error = call(js, bridge, "moru.storage.get(12)", expectSuccess = false).jsonPrimitive.content
            assertTrue(error.contains("key"))
            assertTrue(bridge.failedCalls.any { it.startsWith("storage.get:") })
        }
    }

    private suspend fun call(js: QuickJs, bridge: MiniAppBridge, expression: String, expectSuccess: Boolean = true): JsonElement {
        js.evaluate<Any?>("""
            globalThis.outcome = null;
            ($expression).then(function(value){outcome={ok:true,value:value};},
                function(error){outcome={ok:false,value:error.message};});
            null;
        """.trimIndent())
        val request = js.evaluate<String>("messages.shift()")
        js.evaluate<Any?>(requireNotNull(bridge.handle(request)))
        val outcome = Json.parseToJsonElement(js.evaluate<String>("JSON.stringify(outcome)")).jsonObject
        assertEquals(expectSuccess, outcome.getValue("ok").jsonPrimitive.boolean)
        return outcome["value"] ?: JsonNull
    }

    private suspend fun withRuntime(
        host: (MiniAppStore) -> MiniAppHost = { MiniAppHost() },
        block: suspend (QuickJs, MiniAppStore, MiniAppBridge) -> Unit,
    ) {
        val root = Files.createTempDirectory("mini-script-store").toFile()
        val source = Files.createTempDirectory("mini-script-source").toFile()
        val js = QuickJs.create(Dispatchers.Unconfined)
        try {
            source.resolve("moru-app.json").writeText("""{"id":"tracker","name":"Tracker","network":["api.example.com"],"permissions":["calendar"]}""")
            source.resolve("index.html").writeText("<p>Tracker</p>")
            val store = MiniAppStore(root)
            store.install(source)
            js.evaluate<Any?>("""
                globalThis.window = globalThis;
                var messages=[], events=[], listeners={};
                var MoruBridge={postMessage:function(message){messages.push(message);}};
                function CustomEvent(type,options){this.type=type;this.detail=options.detail;}
                window.addEventListener=function(type,listener){listeners[type]=listener;};
                window.dispatchEvent=function(event){events.push(event);};
                null;
            """.trimIndent())
            js.evaluate<Any?>(MiniAppBridgeScript.source)
            block(js, store, MiniAppBridge(store, "tracker", host(store)))
        } finally { js.close(); root.deleteRecursively(); source.deleteRecursively() }
    }
}
