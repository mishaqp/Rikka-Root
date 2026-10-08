package me.rerere.rikkahub.service

/** Rearming computes work only. Payloads are always executed by the normal claimed worker. */
object CatchupPlanner {
    const val MAX_MISSED = 20
    private const val MAX_SCAN = 100000
    data class Plan(val missed: List<Long>, val next: Long?)
    fun plan(firstDue: Long?, now: Long, policy: String, next: (Long) -> Long?): Plan {
        require(policy in setOf("skip", "fire_once", "fire_all"))
        if (firstDue == null) return Plan(emptyList(), null)
        if (firstDue > now) return Plan(emptyList(), firstDue)
        val future = next(now)?.takeIf { it > now }
        if (policy == "skip") return Plan(emptyList(), future)
        var window = 20 * 60000L
        repeat(48) {
            val basis = maxOf(firstDue, (now - window).coerceAtLeast(0L))
            var current: Long? = if (basis <= firstDue) firstDue else next(basis - 1)
            val recent = ArrayDeque<Long>()
            var scanned = 0
            while (current != null && current <= now && scanned++ < MAX_SCAN) {
                recent.addLast(current)
                if (recent.size > MAX_MISSED) recent.removeFirst()
                val previous = current
                current = next(previous)?.takeIf { it > previous }
            }
            if (recent.size == MAX_MISSED || basis <= firstDue) {
                return Plan(if (policy == "fire_once") recent.lastOrNull()?.let(::listOf) ?: emptyList() else recent.toList(), future)
            }
            window = if (window <= Long.MAX_VALUE / 2) window * 2 else Long.MAX_VALUE
        }
        return Plan(emptyList(), future)
    }
}
