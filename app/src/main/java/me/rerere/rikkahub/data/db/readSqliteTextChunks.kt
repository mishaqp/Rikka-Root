package me.rerere.rikkahub.data.db

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TEXT_CHUNK_BYTES = 16 * 1024

/** Call inside one database transaction. BLOB slicing uses byte offsets (no repeated UTF-8
 * prefix scanning); the incremental decoder retains split multibyte characters losslessly.
 * Only the final text is accumulated: no second complete byte-array copy or truncation.
 */
internal suspend fun readSqliteTextChunks(readChunk: suspend (start: Int, size: Int) -> ByteArray?): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val bytes = ByteBuffer.allocate(TEXT_CHUNK_BYTES + 4)
    val chars = CharBuffer.allocate(TEXT_CHUNK_BYTES)
    val text = StringBuilder()
    var start = 1
    while (true) {
        currentCoroutineContext().ensureActive()
        val chunk = checkNotNull(readChunk(start, TEXT_CHUNK_BYTES)) {
            "Строка истории исчезла во время чтения. Откройте чат заново."
        }
        check(chunk.size <= TEXT_CHUNK_BYTES) { "Не удалось прочитать историю ограниченными фрагментами." }
        val end = chunk.isEmpty()
        bytes.put(chunk).flip()
        do {
            chars.clear()
            val result = decoder.decode(bytes, chars, end)
            result.throwExceptionIfError()
            chars.flip()
            text.append(chars)
        } while (result.isOverflow)
        bytes.compact()
        if (end) {
            chars.clear()
            decoder.flush(chars).throwExceptionIfError()
            chars.flip()
            text.append(chars)
            return text.toString()
        }
        start = Math.addExact(start, chunk.size)
    }
}

private fun java.nio.charset.CoderResult.throwExceptionIfError() {
    if (isError) throw IllegalStateException("Не удалось прочитать UTF-8 историю. Исходные данные сохранены; восстановите корректную копию.")
}
