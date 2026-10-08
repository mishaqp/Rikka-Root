package me.rerere.ai.provider

import android.util.Log
import java.security.MessageDigest

/** Provider diagnostics accept metadata only; request/response content never reaches logcat. */
internal object ProviderLog {
    enum class Provider(val id: String) { OPENAI("openai"), GOOGLE("google"), CLAUDE("claude") }
    enum class Operation {
        REQUEST, RESPONSE, STREAM_OPEN, STREAM_EVENT, STREAM_FAILED,
        PARSE_FAILED, CHUNK_DROPPED, IMAGE_ENCODING_FAILED, STREAM_CLOSED,
    }

    private val modelIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}(?:/[A-Za-z0-9][A-Za-z0-9._-]{0,63}){0,2}")
    private val keyPattern = Regex("(?:^|/)(?:sk-|tp-|gsk_|xai-|AIza)")

    fun record(
        provider: Provider,
        operation: Operation,
        modelId: String? = null,
        httpCode: Int? = null,
        sizeBytes: Long? = null,
    ) {
        // Model IDs can be supplied by users or remote endpoints. Keep compact IDs useful,
        // but never interpolate arbitrary text, URLs or recognizable provider keys.
        val model = when {
            modelId == null -> "none"
            modelIdPattern.matches(modelId) && !keyPattern.containsMatchIn(modelId) -> modelId
            else -> "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(modelId.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        Log.i(
            "ProviderMetadata",
            "provider=${provider.id} model=$model http_code=${httpCode ?: "none"} " +
                "size_bytes=${sizeBytes ?: -1} operation=${operation.name.lowercase()}",
        )
    }
}
