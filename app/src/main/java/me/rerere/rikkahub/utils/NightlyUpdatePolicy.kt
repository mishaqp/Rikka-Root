package me.rerere.rikkahub.utils

import kotlinx.serialization.Serializable

private const val NIGHTLY_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1_000L

@Serializable
data class NightlyUpdateState(
    val isChecking: Boolean = false,
    val update: UpdateInfo? = null,
    val error: String? = null,
    val checkedAt: Long? = null,
) {
    val hasUpdate: Boolean get() = update?.isNewer == true
}

@Serializable
internal data class NightlyUpdateCache(
    val currentCommit: String,
    val state: NightlyUpdateState,
) {
    fun matchesInstalledCommit(commit: String): Boolean =
        currentCommit == commit || sameGitCommit(currentCommit, commit)
}

/** Failed attempts count too, so an offline launch never causes a request on every restart. */
internal fun shouldCheckNightly(lastAttemptAt: Long?, now: Long, force: Boolean = false): Boolean =
    force || lastAttemptAt == null ||
        (now >= lastAttemptAt && now - lastAttemptAt >= NIGHTLY_CHECK_INTERVAL_MS)
