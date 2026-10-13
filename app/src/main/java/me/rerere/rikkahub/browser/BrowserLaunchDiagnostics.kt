package me.rerere.rikkahub.browser

import android.os.Looper
import android.os.SystemClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only fixed event names and primitive lifecycle state may enter the diagnostic journal. */
enum class BrowserLaunchEvent {
    ATTEMPT_STARTED, START_ACTIVITY_RETURNED, START_ACTIVITY_FAILED, LAUNCH_CANCELLED,
    BIND_WAIT_STARTED, BIND_TIMEOUT, BIND_WAIT_SUCCEEDED, BIND_WAIT_CANCELLED,
    ON_CREATE_ENTER, ON_CREATE_RETURNED, ON_CREATE_FAILED, SET_CONTENT_RETURNED,
    ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY_ENTER, ON_DESTROY_RETURNED,
    WINDOW_FOCUS_CHANGED, WEBVIEW_CREATE_ENTER, WEBVIEW_CREATION_FAILED, WEBVIEW_READY, CONTROLLER_BOUND,
}

enum class BrowserLaunchOrigin { TOOL, MANUAL }

/**
 * Bounded process-local evidence, independent of logcat availability. No URLs, intent payloads,
 * conversation ids, page content or throwable messages are accepted. The ordinal passed through
 * the launch Intent correlates delayed Activity callbacks with the correct attempt.
 */
class BrowserLaunchJournal(private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }) {
    private data class Attempt(
        val id: Long,
        val origin: BrowserLaunchOrigin,
        val startedAtMs: Long,
        val events: ArrayDeque<JsonObject> = ArrayDeque(),
        var stage: String = "activity_not_observed",
        var droppedEvents: Int = 0,
    )

    private val attempts = ArrayDeque<Attempt>()
    // A retained Activity Intent must not match a new process's first tool attempt.
    private var nextId = System.nanoTime().ushr(1)

    @Synchronized
    fun begin(origin: BrowserLaunchOrigin, appResumed: Boolean? = null): Long {
        val attempt = Attempt(++nextId, origin, nowMs())
        attempts.addLast(attempt)
        while (attempts.size > MAX_ATTEMPTS) attempts.removeFirst()
        record(attempt.id, BrowserLaunchEvent.ATTEMPT_STARTED, appResumed = appResumed)
        return attempt.id
    }

    @Synchronized
    fun record(
        id: Long,
        event: BrowserLaunchEvent,
        finishing: Boolean? = null,
        changingConfigurations: Boolean? = null,
        windowFocus: Boolean? = null,
        appResumed: Boolean? = null,
        activityInstanceId: Int? = null,
        error: Throwable? = null,
    ) {
        val attempt = attempts.firstOrNull { it.id == id } ?: return
        when (event) {
            BrowserLaunchEvent.START_ACTIVITY_FAILED -> attempt.stage = "start_activity_failed"
            BrowserLaunchEvent.ON_CREATE_ENTER -> attempt.stage = "activity_created_without_bind"
            BrowserLaunchEvent.ON_CREATE_FAILED -> attempt.stage = "activity_creation_failed"
            BrowserLaunchEvent.WEBVIEW_CREATE_ENTER -> attempt.stage = "webview_not_ready"
            BrowserLaunchEvent.WEBVIEW_CREATION_FAILED -> attempt.stage = "webview_creation_failed"
            BrowserLaunchEvent.WEBVIEW_READY -> attempt.stage = "webview_ready_without_bind"
            BrowserLaunchEvent.CONTROLLER_BOUND -> attempt.stage = "controller_bound"
            BrowserLaunchEvent.ON_DESTROY_ENTER -> attempt.stage = "activity_destroyed"
            else -> Unit
        }
        attempt.events.addLast(buildJsonObject {
            put("event", event.name)
            put("elapsed_ms", (nowMs() - attempt.startedAtMs).coerceAtLeast(0))
            put("thread", if (Looper.myLooper() == Looper.getMainLooper()) "main" else "other")
            finishing?.let { put("finishing", it) }
            changingConfigurations?.let { put("changing_configurations", it) }
            windowFocus?.let { put("window_focus", it) }
            appResumed?.let { put("app_resumed", it) }
            activityInstanceId?.let { put("activity_instance", it) }
            // Class names are code identifiers; message, cause and stack are deliberately excluded.
            error?.let { put("exception_type", it.javaClass.name) }
        })
        while (attempt.events.size > MAX_EVENTS) {
            attempt.events.removeFirst()
            attempt.droppedEvents++
        }
    }

    @Synchronized
    fun snapshot(id: Long): JsonObject = attempts.firstOrNull { it.id == id }?.let(::snapshotOf)
        ?: buildJsonObject {
            put("stage", "history_expired")
            put("reason", reason(id))
        }

    @Synchronized
    fun reason(id: Long): String = when (attempts.firstOrNull { it.id == id }?.stage) {
        "start_activity_failed" -> "Вызов Android startActivity завершился исключением; тип указан в отчёте."
        "activity_creation_failed" -> "Создание окна браузера (onCreate) завершилось исключением; тип указан в отчёте."
        "activity_created_without_bind" -> "Начало создания окна (onCreate) зарегистрировано, но WebView ещё не подключился."
        "webview_not_ready" -> "Окно создано; начало создания WebView зарегистрировано, но готовность WebView ещё не подтверждена."
        "webview_creation_failed" -> "Создание или настройка WebView завершились исключением; тип указан в отчёте."
        "webview_ready_without_bind" -> "WebView готов, но подключение браузерного контроллера не зарегистрировано."
        "controller_bound" -> "Подключение контроллера зарегистрировано; журнал покажет последующие этапы окна."
        "activity_destroyed" -> "Зарегистрировано закрытие окна браузера (onDestroy)."
        "activity_not_observed" -> "Начало создания окна браузера (BrowserActivity.onCreate) не зарегистрировано. " +
            "Причина на устройстве не установлена: возврат startActivity не подтверждает появление окна."
        else -> "Подробности попытки уже вышли за пределы сохранённой истории."
    }

    @Synchronized
    fun report(): String = "Браузер: журнал запуска и жизненного цикла текущего процесса.\n" +
        "До $MAX_ATTEMPTS попыток, до $MAX_EVENTS событий на попытку. elapsed_ms — время от начала попытки.\n" +
        "TOOL — browser_open; MANUAL — окно, открытое без идентификатора инструмента.\n" +
        "Возврат startActivity не доказывает разрешение запуска Android. Причины HyperOS не предполагаются.\n" +
        "URL, страницы, данные сайтов и сообщения исключений не записываются.\n" +
        buildJsonArray { attempts.forEach { add(snapshotOf(it)) } }.toString()

    private fun snapshotOf(attempt: Attempt): JsonObject = buildJsonObject {
        put("attempt_id", attempt.id)
        put("origin", attempt.origin.name)
        put("started_uptime_ms", attempt.startedAtMs)
        put("stage", attempt.stage)
        put("reason", reason(attempt.id))
        put("dropped_events", attempt.droppedEvents)
        put("events", buildJsonArray { attempt.events.forEach { add(it) } })
    }

    companion object {
        internal const val MAX_ATTEMPTS = 12
        internal const val MAX_EVENTS = 64
    }
}

object BrowserLaunchDiagnostics {
    val journal = BrowserLaunchJournal()
    fun report(): String = journal.report()
}
