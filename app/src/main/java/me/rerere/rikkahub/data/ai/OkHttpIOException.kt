package me.rerere.rikkahub.data.ai

import java.io.IOException

/** OkHttp asynchronous callbacks report IOExceptions, but rethrow unchecked failures on Dispatcher. */
internal inline fun <T> withOkHttpIOException(block: () -> T): T = try {
    block()
} catch (error: RuntimeException) {
    // Preserve actual IOExceptions and fatal Errors: neither is caught by this boundary.
    throw IOException(error.message ?: "Не удалось выполнить HTTP-запрос.", error)
}
