package me.rerere.rikkahub.miniapps

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

// Behavioral reference: Moru 2101b5e457031d8adb1098dac880bdbb3019623f mini_app_bridge.dart.
data class MiniAppHost(
    val ask: (suspend (String, String?) -> String)? = null,
    val notify: (suspend (String, String) -> Unit)? = null,
    val calendar: (suspend (String, JsonObject) -> JsonObject)? = null,
    val reminders: MiniAppReminders? = null,
    val fetch: MiniAppFetch? = null,
    // Native permission adapter; does not alter the window.moru protocol.
    val beforeReminderSet: (suspend () -> Unit)? = null,
)

class MiniAppBridge(private val store: MiniAppStore, private val appId: String, private val host: MiniAppHost = MiniAppHost()) {
    private val asking = AtomicBoolean(false)
    val pageErrors = mutableListOf<String>()
    val failedCalls = mutableListOf<String>()

    suspend fun handle(message: String): String? {
        var id: JsonElement = JsonNull
        var method = ""
        return try {
            val call = Json.parseToJsonElement(message).jsonObject
            id = call["id"]?.takeIf { it is JsonPrimitive && !it.isString && it.doubleOrNull != null } ?: JsonNull
            method = call["method"].wireText()
            val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
            if (method == "__report") {
                note(pageErrors, "${args["kind"].wireText()}: ${args["message"].wireText()}")
                null
            } else reply(id, true, dispatch(method, args))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val messageText = e.message ?: e.toString()
            note(failedCalls, "$method: $messageText")
            reply(id, false, JsonPrimitive(messageText))
        }
    }

    private suspend fun dispatch(method: String, args: JsonObject): JsonElement {
        fun text(name: String, max: Int = 1000, required: Boolean = true): String {
            val raw = args[name]
            if (!required && (raw == null || raw == JsonNull)) return ""
            val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null || (required && value.isBlank())) throw MiniAppException("invalid_argument", "$name must be a string.")
            return value.take(max)
        }
        fun key(): String = (args["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw MiniAppException("invalid_key", "key must be a string.")
        return when (method) {
            "storage.get" -> store.storageGet(appId, key())
            "storage.set" -> { store.storageSet(appId, key(), args["value"] ?: JsonNull, fromApp = true); JsonNull }
            "storage.remove" -> { store.storageRemove(appId, key(), fromApp = true); JsonNull }
            "storage.keys" -> JsonArray(store.storageKeys(appId).map(::JsonPrimitive))
            "app.info" -> buildJsonObject { put("id", appId); put("name", store.byId(appId)?.name?.let(::JsonPrimitive) ?: JsonNull); put("platform", "android") }
            "ai.ask" -> {
                val ask = need(host.ask)
                val prompt = text("prompt", MAX_PROMPT_CHARS)
                val system = text("system", MAX_PROMPT_CHARS, false).ifEmpty { null }
                if (!asking.compareAndSet(false, true)) throw MiniAppException("busy", "Wait for the previous moru.ai.ask to finish.")
                try { JsonPrimitive(ask(prompt, system)) } finally { asking.set(false) }
            }
            "notify" -> { need(host.notify)(text("title", 80), text("body", 300, false)); JsonNull }
            "reminders.set" -> {
                val reminders = need(host.reminders)
                val id = text("id", 40)
                val reminder = args["reminder"] ?: JsonNull
                // Validate before asking for native permission; the shared service validates again under its mutex.
                MiniAppReminders.validateId(id)
                MiniAppReminders.normalize(reminder, store.byId(appId)?.name)
                host.beforeReminderSet?.invoke()
                reminders.set(appId, id, reminder)
                JsonNull
            }
            "reminders.remove" -> { need(host.reminders).remove(appId, text("id", 40)); JsonNull }
            "reminders.list" -> need(host.reminders).list(appId)
            "fetch" -> need(host.fetch).fetch(app(), args)
            "calendar.list" -> calendar("queryCalendar", args, LIST_KEYS)
            "calendar.add" -> calendar("createCalendarEvent", args, ADD_KEYS)
            else -> throw MiniAppException("unknown_method", "Unknown method \"$method\".")
        }
    }
    private fun app() = store.byId(appId) ?: throw MiniAppException("not_found", "The app was deleted.")
    private suspend fun calendar(method: String, args: JsonObject, keys: Set<String>): JsonObject {
        val calendar = need(host.calendar)
        if ("calendar" !in app().permissions) throw MiniAppException("permission_not_declared", "Add \"permissions\": [\"calendar\"] to moru-app.json.")
        val result = calendar(method, JsonObject(args.filterKeys { it in keys }))
        result["error"]?.takeUnless { it == JsonNull }?.let { throw MiniAppException(it.wireText(), (result["message"] ?: it).wireText()) }
        return result
    }
    companion object {
        const val MAX_PROBLEMS = 50
        const val MAX_PROMPT_CHARS = 32000
        private val LIST_KEYS = setOf("begin", "end", "range", "query", "limit", "calendar_id", "include_calendars")
        private val ADD_KEYS = setOf("title", "description", "location", "start", "end", "all_day", "reminders", "calendar_id")
        fun changedScript(key: String) = "window.__moruChanged && window.__moruChanged(${JsonPrimitive(key)});"
        private fun reply(id: JsonElement, ok: Boolean, value: JsonElement) = "window.__moruReply($id, $ok, $value);"
        private fun note(list: MutableList<String>, value: String) = synchronized(list) { if (list.size < MAX_PROBLEMS) list.add(value) }
        private fun <T : Any> need(value: T?): T = value ?: throw MiniAppException("unavailable", "This is not available here.")
    }
}

internal fun JsonElement?.wireText(): String = when (this) {
    null, JsonNull -> "null"
    is JsonPrimitive -> content
    else -> toString()
}
