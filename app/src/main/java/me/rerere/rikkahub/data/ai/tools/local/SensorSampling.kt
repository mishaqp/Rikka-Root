package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.delay

/** Shared with the Android adapter so cancellation and failed registration always close it. */
internal suspend fun collectSensorValues(
    durationMs: Int,
    register: ((FloatArray) -> Unit) -> Boolean,
    unregister: () -> Unit,
): List<Double>? {
    val lock = Any()
    var sums: DoubleArray? = null
    var count = 0
    val receive: (FloatArray) -> Unit = { values ->
        synchronized(lock) {
            if (values.isNotEmpty() && values.all { it.isFinite() }) {
                val current = sums ?: DoubleArray(values.size).also { sums = it }
                if (values.size == current.size) {
                    values.forEachIndexed { index, value -> current[index] += value.toDouble() }
                    count++
                }
            }
        }
    }
    try {
        if (!register(receive)) return null
        delay(durationMs.coerceIn(1, 5000).toLong())
    } finally {
        unregister()
    }
    return synchronized(lock) { sums?.takeIf { count > 0 }?.map { it / count } }
}
