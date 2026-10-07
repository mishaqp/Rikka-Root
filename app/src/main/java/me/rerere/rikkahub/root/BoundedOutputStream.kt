package me.rerere.rikkahub.root

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Drains arbitrary output while retaining only a UTF-8-safe prefix and discarded-byte count. */
internal class BoundedOutputStream(private val cap: Int) : OutputStream() {
    private val bytes = ByteArrayOutputStream(minOf(cap + 4, 16_384))
    private var total = 0L

    @Synchronized override fun write(value: Int) {
        total++
        if (bytes.size() < cap + 4) bytes.write(value)
    }

    @Synchronized override fun write(source: ByteArray, offset: Int, length: Int) {
        total += length
        val remaining = cap + 4 - bytes.size()
        if (remaining > 0) bytes.write(source, offset, minOf(length, remaining))
    }

    @Synchronized fun snapshot(): String {
        val captured = bytes.toByteArray()
        if (total <= cap) return String(captured, Charsets.UTF_8)
        var keep = minOf(cap, captured.size)
        while (keep in 1 until captured.size && (captured[keep].toInt() and 0xc0) == 0x80) keep--
        return String(captured, 0, keep, Charsets.UTF_8) + "\n…[truncated; ${total - keep} bytes more]"
    }
}
