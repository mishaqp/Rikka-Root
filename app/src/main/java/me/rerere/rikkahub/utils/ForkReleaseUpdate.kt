package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Locale

private const val DOWNLOAD_PREFIX = "https://github.com/mishaqp/Rikka-Root/releases/download/"
private val forkVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+-root\\.[0-9]+")
private val apkName = Regex("[A-Za-z0-9_.-]+\\.apk", RegexOption.IGNORE_CASE)
private val nightlyApkName = Regex(
    "Rikka-Root-[0-9]+\\.[0-9]+\\.[0-9]+-root\\.[0-9]+-([a-f0-9]{7,40})-arm64-v8a\\.apk",
    RegexOption.IGNORE_CASE,
)
private val gitCommitHash = Regex("[a-f0-9]{7,40}", RegexOption.IGNORE_CASE)

/** The nightly tag moves before upload; only an uploaded APK identifies the installable build. */
internal fun decodeNightlyReleaseUpdate(body: String): UpdateInfo? {
    val release = Json.parseToJsonElement(body) as? JsonObject
        ?: error("Некорректный ответ GitHub о nightly-сборке.")
    if (release.primitive("draft")?.booleanOrNull == true ||
        release.string("tag_name") != "nightly"
    ) return null
    val releaseDate = release.string("published_at").validInstant()
    val candidates = (release["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
        val asset = element as? JsonObject ?: return@mapNotNull null
        val name = asset.string("name") ?: return@mapNotNull null
        val hash = nightlyApkName.matchEntire(name)?.groupValues?.get(1)
            ?.lowercase(Locale.ROOT) ?: return@mapNotNull null
        if (asset.string("state")?.let { it != "uploaded" } == true) return@mapNotNull null
        val url = asset.string("browser_download_url") ?: return@mapNotNull null
        if (url != "${DOWNLOAD_PREFIX}nightly/$name") return@mapNotNull null
        val size = asset.primitive("size")?.longOrNull ?: return@mapNotNull null
        if (size <= 0) return@mapNotNull null
        val publishedAt = asset.string("updated_at").validInstant()
            ?: asset.string("created_at").validInstant()
            ?: releaseDate ?: return@mapNotNull null
        UpdateInfo(
            version = hash,
            publishedAt = publishedAt.toString(),
            changelog = "",
            downloads = listOf(UpdateDownload(name, url, String.format(Locale.ROOT, "%.1f MB", size / 1048576.0))),
            commitHash = hash,
        )
    }
    return candidates.maxByOrNull { Instant.parse(it.publishedAt) }
}

internal fun isGitCommitHash(value: String): Boolean = gitCommitHash.matches(value)

internal fun sameGitCommit(current: String, latest: String): Boolean {
    if (!isGitCommitHash(current) || !isGitCommitHash(latest)) return false
    return current.startsWith(latest, ignoreCase = true) || latest.startsWith(current, ignoreCase = true)
}

internal enum class NightlyCommitRelation { AHEAD, BEHIND, IDENTICAL, DIVERGED }

internal data class NightlyComparison(
    val relation: NightlyCommitRelation,
    val commitTitles: List<String>,
    val totalCommits: Int,
)

internal fun decodeNightlyComparison(body: String): NightlyComparison {
    val comparison = Json.parseToJsonElement(body) as? JsonObject
        ?: error("Некорректный ответ GitHub при сравнении коммитов.")
    val relation = when (comparison.string("status")) {
        "ahead" -> NightlyCommitRelation.AHEAD
        "behind" -> NightlyCommitRelation.BEHIND
        "identical" -> NightlyCommitRelation.IDENTICAL
        "diverged" -> NightlyCommitRelation.DIVERGED
        else -> error("GitHub не смог определить порядок коммитов.")
    }
    val titles = (comparison["commits"] as? JsonArray).orEmpty().mapNotNull { element ->
        val commit = (element as? JsonObject)?.get("commit") as? JsonObject
        commit?.string("message")?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.take(30)
    return NightlyComparison(
        relation = relation,
        commitTitles = titles,
        totalCommits = comparison.primitive("total_commits")?.intOrNull?.coerceAtLeast(0) ?: titles.size,
    )
}

private fun String?.validInstant(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun decodeForkReleaseUpdate(body: String): UpdateInfo? {
    val release = Json.parseToJsonElement(body) as? JsonObject
        ?: error("Invalid GitHub release response")
    if (release.primitive("draft")?.booleanOrNull == true ||
        release.primitive("prerelease")?.booleanOrNull == true
    ) return null
    val tag = release.string("tag_name") ?: return null
    val version = tag.removePrefix("v")
    if (!forkVersion.matches(version)) return null
    val publishedAt = release.string("published_at") ?: return null
    if (runCatching { Instant.parse(publishedAt) }.isFailure) return null
    val downloads = (release["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
        val asset = element as? JsonObject ?: return@mapNotNull null
        val name = asset.string("name") ?: return@mapNotNull null
        if (!apkName.matches(name) || name.contains("debug", ignoreCase = true) ||
            name.contains("unsigned", ignoreCase = true)
        ) return@mapNotNull null
        val url = asset.string("browser_download_url") ?: return@mapNotNull null
        if (url != "$DOWNLOAD_PREFIX$tag/$name") return@mapNotNull null
        val size = asset.primitive("size")?.longOrNull ?: return@mapNotNull null
        if (size <= 0) return@mapNotNull null
        UpdateDownload(name, url, String.format(Locale.ROOT, "%.1f MB", size / 1048576.0))
    }
    if (downloads.isEmpty()) return null
    return UpdateInfo(version, publishedAt, release.string("body").orEmpty(), downloads)
}

private fun JsonObject.primitive(key: String) = this[key] as? JsonPrimitive
private fun JsonObject.string(key: String) = primitive(key)?.contentOrNull
