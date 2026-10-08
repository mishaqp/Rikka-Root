package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.integerArgument(name: String): Int? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

/** Bounded, non-repeating vibration; never silently truncate or accept numeric strings. */
internal fun validateVibrationArguments(input: JsonObject): LongArray {
    require(!(input.containsKey("duration_ms") && input.containsKey("pattern")))
    if (input.containsKey("pattern")) {
        val pattern = input["pattern"] as? JsonArray ?: throw IllegalArgumentException()
        require(pattern.size in 2..20)
        val timings = pattern.map { element ->
            val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: throw IllegalArgumentException()
            require(value in 0..5000)
            value
        }.toLongArray()
        require(timings.sum() <= 5000 && timings.indices.any { it % 2 == 1 && timings[it] > 0 })
        return timings
    }
    val duration = if (input.containsKey("duration_ms")) input.integerArgument("duration_ms")
        ?: throw IllegalArgumentException() else 500
    require(duration in 1..5000)
    return longArrayOf(0, duration.toLong())
}

internal fun brightnessValue(value: Int): Int = value.coerceIn(1, 255)

// STREAM_SYSTEM is aliased to STREAM_RING on ordinary Android phones.
internal fun volumeNeedsDndAccess(stream: String?): Boolean = stream == "ring" || stream == "notification" || stream == "system"

internal fun volumeStep(percent: Int, min: Int, max: Int): Int {
    require(percent in 0..100 && min >= 0 && max >= min)
    return (min.toLong() + ((max.toLong() - min) * percent + 50) / 100).toInt()
}

/** Decode at most 4 MP and 4096 pixels per side, even for absurd image headers. */
internal fun wallpaperSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (width.toLong() / sample * (height.toLong() / sample) > 4_194_304L ||
        width / sample > 4096 || height / sample > 4096) {
        sample *= 2
    }
    return sample
}
