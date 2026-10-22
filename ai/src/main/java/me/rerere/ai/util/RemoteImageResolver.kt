package me.rerere.ai.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.awaitBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** Resolve URL images only for protocols that require raw inline bytes. No provider headers are copied. */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun resolveRemoteImages(
    messages: List<UIMessage>,
    client: OkHttpClient,
    maxEncodedImageBytes: Long,
): List<UIMessage> {
    val resolved = mutableMapOf<String, String>()
    suspend fun imageUrl(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return url
        resolved[url]?.let { return it }
        val rawLimit = maxEncodedImageBytes / 4 * 3
        val request = Request.Builder().url(url).get().build()
        val dataUrl = client.newCall(request).awaitBody { response ->
            if (!response.isSuccessful) throw HttpException("Не удалось загрузить изображение (HTTP ${response.code}). Проверьте вложение и повторите запрос.")
            val body = response.body
            if (body.contentLength() > rawLimit) throw IllegalArgumentException("Изображение слишком велико для провайдера. Уменьшите размер вложения.")
            val buffer = Buffer()
            val source = body.source()
            var total = 0L
            while (true) {
                val count = source.read(buffer, minOf(8192L, rawLimit - total + 1))
                if (count == -1L) break
                total += count
                if (total > rawLimit) throw IllegalArgumentException("Изображение слишком велико для провайдера. Уменьшите размер вложения.")
            }
            val contentType = body.contentType()
            val mimeType = contentType?.let { "${it.type}/${it.subtype}" } ?: "image/png"
            if (!mimeType.startsWith("image/")) throw IllegalArgumentException("Сервер вложения не вернул изображение. Проверьте ссылку и повторите запрос.")
            "data:$mimeType;base64,${Base64.encode(buffer.readByteArray())}"
        }
        resolved[url] = dataUrl
        return dataUrl
    }
    suspend fun resolve(part: UIMessagePart): UIMessagePart = when (part) {
        is UIMessagePart.Image -> part.copy(url = imageUrl(part.url))
        is UIMessagePart.Tool -> part.copy(output = part.output.map { output ->
            if (output is UIMessagePart.Image) output.copy(url = imageUrl(output.url)) else output
        })
        else -> part
    }
    return messages.map { message -> message.copy(parts = message.parts.map { resolve(it) }) }
}
