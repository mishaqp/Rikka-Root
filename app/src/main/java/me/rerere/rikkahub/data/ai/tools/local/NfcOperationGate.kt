package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Stop completes only after physical I/O has left the gate; never wait on the UI thread. */
internal class NfcOperationGate {
    private val operation = ReentrantLock()
    private val stopping = AtomicBoolean(false)
    private val closer = AtomicReference<(() -> Unit)?>()

    fun requestStop() { stopping.set(true) }

    fun attach(close: () -> Unit): Boolean {
        if (stopping.get()) { runCatching(close); return false }
        check(closer.compareAndSet(null, close))
        if (stopping.get()) {
            if (closer.compareAndSet(close, null)) runCatching(close)
            return false
        }
        return true
    }

    fun detach(close: () -> Unit) { closer.compareAndSet(close, null) }

    fun perform(action: () -> NfcResult): NfcResult = operation.withLock {
        if (stopping.get()) NfcResult.Cancelled else action()
    }

    /** Call from IO: close first to interrupt a blocking transfer, then drain its operation. */
    fun stopAndClose() {
        requestStop()
        closer.getAndSet(null)?.let { runCatching(it) }
        operation.withLock { /* Cancellation is now ordered after any operation already started. */ }
    }
}
