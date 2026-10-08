package me.rerere.rikkahub.utils

/**
 * Phase 12 — relative-time formatter, used by the Workflows list / detail "Ran 2h ago"
 * subtitle. Uses string-resource ids so the caller can pass the localised forms in.
 *
 * Bands: <60s "just now", <60m "Xm ago", <24h "Xh ago", else "Xd ago".
 *
 * The caller passes the string-resource id args from the calling Composable (rather than
 * pulling them in here) so this stays free of Compose deps.
 */
data class RelativeTimeStrings(
    val justNow: String,
    val secondsAgo: String,
    val minutesAgo: String,
    val hoursAgo: String,
    val daysAgo: String,
)

fun formatRelativeAgo(thenMs: Long, nowMs: Long, strings: RelativeTimeStrings): String {
    val deltaSec = ((nowMs - thenMs) / 1000L).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> if (deltaSec < 5) strings.justNow else String.format(strings.secondsAgo, deltaSec)
        deltaSec < 60 * 60 -> String.format(strings.minutesAgo, deltaSec / 60)
        deltaSec < 24 * 60 * 60 -> String.format(strings.hoursAgo, deltaSec / 3600)
        else -> String.format(strings.daysAgo, deltaSec / 86400)
    }
}
