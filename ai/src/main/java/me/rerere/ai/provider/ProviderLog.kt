package me.rerere.ai.provider

import android.util.Log
import java.security.MessageDigest

/** Provider diagnostics accept metadata only; request/response content never reaches logcat. */
internal object ProviderLog {
    enum class Provider(val id: String) { OPENAI("openai"), GOOGLE("google"), CLAUDE("claude") }
    private val modelIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,63}){0,2}")
    private val keyPattern = Regex("(?:^|/)(?:sk-|tp-|gsk_|xai-|AIza)")

    private fun safeModel(modelId: String?): String {
        // Model IDs can be supplied by users or remote endpoints. Keep compact IDs useful,
        // but never interpolate arbitrary text, URLs or recognizable provider keys.
        return when {
            modelId == null -> "none"
            modelIdPattern.matches(modelId) && !keyPattern.containsMatchIn(modelId) -> modelId
            else -> "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(modelId.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    /** One instance per HTTP response; callbacks may race with cancellation/cleanup. */
    class Summary(private val provider: Provider, modelId: String? = null) {
        private val model = safeModel(modelId)
        private val startedNanos = System.nanoTime()
        private var httpCode: Int? = null
        private var eventCount = 0L
        private var sizeBytes = 0L
        private var finished = false

        @Synchronized
        fun response(code: Int) {
            if (!finished) httpCode = code
        }

        @Synchronized
        fun event(size: Long) {
            if (!finished) {
                eventCount++
                sizeBytes += size.coerceAtLeast(0)
            }
        }

        @Synchronized
        fun bodySize(size: Long) {
            if (!finished) sizeBytes += size.coerceAtLeast(0)
        }

        /** Called from the request/flow's finally block, including errors and cancellation. */
        @Synchronized
        fun finish() {
            if (finished) return
            finished = true
            val durationMs = (System.nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000
            Log.i(
                "ProviderMetadata",
                "provider=${provider.id} model=$model http_code=${httpCode ?: "none"} " +
                    "event_count=$eventCount size_bytes=$sizeBytes duration_ms=$durationMs",
            )
        }
    }
}
