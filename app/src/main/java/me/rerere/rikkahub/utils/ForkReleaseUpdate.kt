package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Locale

private const val DOWNLOAD_PREFIX = "https://github.com/mishaqp/Rikka-Root/releases/download/"
private val forkVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+-root\\.[0-9]+")
private val apkName = Regex("[A-Za-z0-9_.-]+\\.apk", RegexOption.IGNORE_CASE)

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
