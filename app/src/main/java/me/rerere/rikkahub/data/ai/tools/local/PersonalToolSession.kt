// Foreground result bridge adapted from Agent CameraResultBuffer/BiometricResultBuffer (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.ui.activity.PersonalToolActivity
import kotlinx.serialization.json.JsonObject

internal sealed interface PersonalUiRequest {
    data class Camera(val output: File) : PersonalUiRequest
    data class Record(val durationMs: Int) : PersonalUiRequest
    data class Speech(val language: String, val timeoutMs: Int, val preferOffline: Boolean) : PersonalUiRequest
    data class Biometric(val title: String, val subtitle: String?, val allowCredential: Boolean) : PersonalUiRequest
    data class Encrypt(val alias: String, val encoding: String) : PersonalUiRequest
    class Reveal(val bytes: ByteArray) : PersonalUiRequest {
        override fun toString(): String = "Reveal(redacted)"
    }
    fun destroy() { if (this is Reveal) bytes.fill(0) }
}
internal sealed interface PersonalUiResult {
    data object Acknowledged : PersonalUiResult
    data object Cancelled : PersonalUiResult
    data class Error(val message: String) : PersonalUiResult
    data class Photo(val file: File) : PersonalUiResult
    data class Recording(val file: File, val durationMs: Int) : PersonalUiResult
    data class Speech(val text: String) : PersonalUiResult
    data class Authentication(val method: String) : PersonalUiResult
    class Secret(val bytes: ByteArray) : PersonalUiResult { override fun toString(): String = "Secret(redacted)" }
    fun destroy() { when (this) {
        is Secret -> bytes.fill(0)
        is Recording -> file.delete()
        is Photo -> file.delete()
        else -> Unit
    } }
}

internal class PersonalUiSession(val id: String, val request: PersonalUiRequest) {
    private val result = CompletableDeferred<PersonalUiResult>()
    private val closing = AtomicBoolean(false)
    internal var claimed = false
    internal var cancellationAction: (suspend () -> Unit)? = null
    private var completed: PersonalUiResult? = null
    private var discarded = false
    val isClosing: Boolean get() = closing.get()
    internal fun beginClose(): Boolean = closing.compareAndSet(false,true)
    @Synchronized internal fun finish(value: PersonalUiResult) { if (discarded) value.destroy(); completed = value; result.complete(value) }
    suspend fun await(): PersonalUiResult = result.await()
    @Synchronized internal fun destroyUndeliveredResult() { discarded = true; completed?.destroy() }
}

/** Cancellation drains the Android controller before another session can own hardware/UI. */
internal class PersonalToolSessions {
    private var pending: PersonalUiSession? = null
    @Synchronized fun register(request: PersonalUiRequest): PersonalUiSession {
        check(pending == null) { "Уже открыт другой сеанс камеры, микрофона или защищённого ввода." }
        return PersonalUiSession(UUID.randomUUID().toString(), request).also { pending = it }
    }
    @Synchronized fun get(id: String): PersonalUiSession? = pending?.takeIf { it.id == id }
    @Synchronized fun isBusy(): Boolean = pending != null
    @Synchronized fun isActive(id: String): Boolean = get(id)?.isClosing == false
    @Synchronized fun claim(id: String): Boolean {
        val session=get(id) ?: return false
        if (session.claimed || session.isClosing) return false
        session.claimed=true; return true
    }
    @Synchronized fun setCancellationAction(id: String, cleanup: suspend () -> Unit): Boolean {
        val session=get(id) ?: return false
        if (session.isClosing || session.cancellationAction != null) return false
        session.cancellationAction=cleanup; return true
    }
    private fun finish(session: PersonalUiSession, result: PersonalUiResult) {
        session.request.destroy()
        synchronized(this) { if (pending === session) pending=null }
        session.finish(result)
    }
    /** Successful controllers call this only after stop/release/destroy has finished. */
    suspend fun complete(id: String, result: PersonalUiResult): Boolean {
        val session=get(id)
        if (session == null || !session.beginClose()) { result.destroy(); return false }
        finish(session,result); return true
    }
    suspend fun cancel(id: String): Boolean {
        val session=get(id) ?: return false
        if (!session.beginClose()) return false
        withContext(NonCancellable) {
            var result: PersonalUiResult = PersonalUiResult.Cancelled
            try { session.cancellationAction?.invoke() } catch (_: Exception) {
                result = PersonalUiResult.Error("Не удалось полностью закрыть сеанс. Проверьте, что камера или микрофон остановлены.")
            } finally { finish(session,result) }
        }
        return true
    }
    suspend fun run(request: PersonalUiRequest, timeoutMs: Long, isForeground: suspend () -> Boolean, startUi: suspend (String) -> Unit): PersonalUiResult {
        require(timeoutMs in 1..600000)
        if (!isForeground()) { request.destroy(); return PersonalUiResult.Error("Для этой функции откройте Rikka-Root. Фоновый запуск не разрешён.") }
        val session = try { register(request) } catch (_: IllegalStateException) {
            request.destroy(); return PersonalUiResult.Error("Уже открыт другой сеанс. Завершите или отмените его.")
        }
        var delivered=false
        try {
            if (!isForeground()) return PersonalUiResult.Error("Приложение ушло в фон. Откройте Rikka-Root и повторите запрос.")
            startUi(session.id)
            val result=withTimeoutOrNull(timeoutMs) { session.await() }
                ?: return PersonalUiResult.Error("Время сеанса истекло.")
            delivered=true
            return result
        } finally {
            cancel(session.id)
            if (!delivered) session.destroyUndeliveredResult()
        }
    }
}
internal val sharedPersonalSessions = PersonalToolSessions()

internal suspend fun awaitPersonalToolUi(context: Context, request: PersonalUiRequest, timeoutMs: Long): PersonalUiResult =
    sharedPersonalSessions.run(request,timeoutMs,
        isForeground={ withContext(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) } },
        startUi={ id -> withContext(Dispatchers.Main.immediate) {
            check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sharedPersonalSessions.isActive(id))
            context.startActivity(Intent(context,PersonalToolActivity::class.java).apply { putExtra(PersonalToolActivity.EXTRA_REQUEST_ID,id); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } })

internal fun personalUiError(result: PersonalUiResult): JsonObject = deviceToolError(when (result) {
    is PersonalUiResult.Error -> result.message
    PersonalUiResult.Cancelled -> "Функция отменена пользователем или при уходе приложения в фон."
    else -> "Android вернул неожиданный результат сеанса."
})
