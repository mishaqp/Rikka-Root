// Foreground SAF bridge adapted from ExTV/rikkahub-agent SafPickerResultBuffer (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.ui.activity.SafToolActivity
import java.util.UUID

internal data class SafUiRequest(val initialUri: String?, val label: String?)
internal sealed interface SafUiResult {
    data class Granted(val contentUri: String) : SafUiResult
    data object Cancelled : SafUiResult
    data class Error(val message: String) : SafUiResult
}

internal class SafUiSession(val id: String, val request: SafUiRequest) {
    private val result = CompletableDeferred<SafUiResult>()
    internal var closing = false
    internal var pickerStarted = false
    internal var cancellationAction: (suspend () -> Unit)? = null
    internal fun finish(value: SafUiResult) { result.complete(value) }
    suspend fun await(): SafUiResult = result.await()
}

/** The pending request belongs to the invocation, so Activity recreation can reattach. */
internal class SafToolSessions {
    private var pending: SafUiSession? = null

    @Synchronized fun register(request: SafUiRequest): SafUiSession {
        check(pending == null) { "Уже открыт выбор папки. Завершите или отмените его." }
        return SafUiSession(UUID.randomUUID().toString(), request).also { pending = it }
    }
    @Synchronized fun get(id: String): SafUiSession? = pending?.takeIf { it.id == id }
    @Synchronized fun isBusy(): Boolean = pending != null
    @Synchronized fun isActive(id: String): Boolean = get(id)?.closing == false

    @Synchronized fun attach(id: String, cleanup: suspend () -> Unit): Boolean {
        val session = get(id)?.takeUnless { it.closing } ?: return false
        session.cancellationAction = cleanup
        return true
    }
    @Synchronized fun beginPicker(id: String): Boolean {
        val session = get(id)?.takeUnless { it.closing || it.pickerStarted } ?: return false
        session.pickerStarted = true
        return true
    }
    @Synchronized private fun finish(session: SafUiSession, result: SafUiResult) {
        if (pending === session) pending = null
        session.finish(result)
    }

    /** Persist only while this request still owns the slot; cancelled late callbacks do nothing. */
    @Synchronized fun grant(id: String, result: SafUiResult.Granted, persist: () -> Unit): Boolean {
        val session = get(id)?.takeUnless { it.closing } ?: return false
        val outcome = try { persist(); result } catch (_: Exception) {
            SafUiResult.Error("Android не сохранил доступ к папке. Выберите папку с постоянным доступом для чтения и записи.")
        }
        session.closing = true
        finish(session, outcome)
        return true
    }
    @Synchronized fun complete(id: String, result: SafUiResult): Boolean {
        val session = get(id)?.takeUnless { it.closing } ?: return false
        session.closing = true
        finish(session, result)
        return true
    }
    suspend fun cancel(id: String): Boolean {
        val session = synchronized(this) {
            get(id)?.takeUnless { it.closing }?.also { it.closing = true }
        } ?: return false
        withContext(NonCancellable) {
            var result: SafUiResult = SafUiResult.Cancelled
            try { session.cancellationAction?.invoke() }
            catch (_: Exception) { result = SafUiResult.Error("Не удалось закрыть окно выбора папки. Закройте его в Android.") }
            finally { finish(session, result) }
        }
        return true
    }

    suspend fun run(request: SafUiRequest, timeoutMs: Long, isForeground: suspend () -> Boolean, launch: suspend (String) -> Unit): SafUiResult {
        require(timeoutMs in 1..600_000)
        if (!isForeground()) return SafUiResult.Error("Для выбора папки откройте Rikka-Root. Фоновый запуск не разрешён.")
        val session = try { register(request) } catch (_: IllegalStateException) {
            return SafUiResult.Error("Уже открыт выбор папки. Завершите или отмените его.")
        }
        try {
            if (!isForeground()) return SafUiResult.Error("Приложение ушло в фон. Откройте Rikka-Root и повторите запрос.")
            launch(session.id)
            return withTimeoutOrNull(timeoutMs) { session.await() }
                ?: SafUiResult.Error("Время выбора папки истекло.")
        } finally { cancel(session.id) }
    }
}

internal val sharedSafToolSessions = SafToolSessions()

internal suspend fun awaitSafToolUi(context: Context, request: SafUiRequest): SafUiResult =
    sharedSafToolSessions.run(request, 300_000,
        isForeground = { withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } },
        launch = { id -> withContext(Dispatchers.Main.immediate) {
            check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sharedSafToolSessions.isActive(id))
            context.startActivity(Intent(context, SafToolActivity::class.java).apply {
                putExtra(SafToolActivity.EXTRA_REQUEST_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } })
