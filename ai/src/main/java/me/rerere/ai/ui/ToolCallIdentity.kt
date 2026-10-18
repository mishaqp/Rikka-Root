package me.rerere.ai.ui

import kotlin.uuid.Uuid

/** Allocate before merging fragments or persisting approval state. */
internal fun uniqueToolCallId(preferred: String?, used: MutableSet<String>): String {
    if (!preferred.isNullOrBlank() && used.add(preferred)) return preferred
    var generated: String
    do { generated = "call_${Uuid.random()}" } while (!used.add(generated))
    return generated
}
