// Adapted from ExTV/rikkahub-agent, local/NfcResultBuffer.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.atomic.AtomicBoolean

sealed class NfcResult {
    data class ReadOk(val recordsJson: String, val tagIdHex: String, val ndefSupported: Boolean = true) : NfcResult()
    data class WriteOk(val tagIdHex: String) : NfcResult()
    data object Timeout : NfcResult()
    data object Cancelled : NfcResult()
    data class Error(val message: String) : NfcResult()
}

class NfcSession internal constructor(
    val requestId: String,
    val write: Boolean,
    val timeoutSeconds: Int,
    val records: JsonArray?,
) {
    private val result = CompletableDeferred<NfcResult>()
    internal val operationGate = NfcOperationGate()
    private val completing = AtomicBoolean(false)
    internal var claimed = false
    internal val isCompleting: Boolean get() = completing.get()
    internal fun beginCompletion(): Boolean = completing.compareAndSet(false, true)
    suspend fun await(): NfcResult = result.await()
    internal fun finish(value: NfcResult) { result.complete(value) }
}

/** One physical NFC reader. No pending record content leaves process memory. */
class NfcResultBuffer {
    private var pending: NfcSession? = null

    @Synchronized fun register(requestId: String, write: Boolean, timeoutSeconds: Int, records: JsonArray? = null): NfcSession {
        require(requestId.isNotBlank() && requestId.length <= 64 && timeoutSeconds in 5..120)
        require(write == (records != null))
        check(pending == null) { "Уже открыт другой сеанс NFC." }
        return NfcSession(requestId, write, timeoutSeconds, records).also { pending = it }
    }

    @Synchronized fun get(requestId: String): NfcSession? = pending?.takeIf { it.requestId == requestId }
    @Synchronized fun isActive(requestId: String): Boolean = get(requestId)?.isCompleting == false
    @Synchronized fun claim(requestId: String): Boolean {
        val session = get(requestId) ?: return false
        if (session.claimed || session.isCompleting) return false
        session.claimed = true
        return true
    }

    suspend fun complete(requestId: String, result: NfcResult): Boolean {
        val session = get(requestId) ?: return false
        if (!session.beginCompletion()) return false
        session.operationGate.requestStop()
        // This also runs from a cancelled generation. Cleanup must complete, off the UI thread.
        withContext(NonCancellable + Dispatchers.IO) {
            session.operationGate.stopAndClose()
            synchronized(this@NfcResultBuffer) {
                if (pending === session) pending = null
            }
            session.finish(result)
        }
        return true
    }

    suspend fun cancel(requestId: String): Boolean = complete(requestId, NfcResult.Cancelled)
}

internal val sharedNfcSessions = NfcResultBuffer()
