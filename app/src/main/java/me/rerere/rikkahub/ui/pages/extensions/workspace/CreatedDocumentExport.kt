package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A new SAF document is committed only after the stream closes and cancellation is checked. */
internal suspend fun <D : Any> exportCreatedDocument(
    create: suspend () -> D?, open: (D) -> OutputStream?,
    write: suspend (OutputStream) -> Unit, delete: (D) -> Unit,
) {
    currentCoroutineContext().ensureActive()
    // Record the provider's new document even if the caller is cancelled while creating it.
    val document=withContext(NonCancellable) { create() } ?: error("Не удалось создать файл назначения.")
    var committed=false
    try {
        currentCoroutineContext().ensureActive()
        val output=open(document) ?: error("Не удалось открыть файл назначения.")
        output.use { write(it) }
        currentCoroutineContext().ensureActive()
        committed=true
    } finally {
        if (!committed) withContext(NonCancellable+Dispatchers.IO) { runCatching { delete(document) } }
    }
}
