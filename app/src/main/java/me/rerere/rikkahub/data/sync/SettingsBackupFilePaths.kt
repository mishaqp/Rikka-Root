package me.rerere.rikkahub.data.sync

import androidx.core.net.toUri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Avatar
import java.io.File

/** Rebase only app images whose bytes were selected and staged from this archive. */
internal fun Settings.remapRestoredFileUrls(filesDir: File, restoredPaths: Set<String>): Settings {
    if (restoredPaths.isEmpty()) return this
    fun remap(url: String): String {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return url
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty()) return url
        val path = uri.path ?: return url
        val relative = path.substringAfterLast("/files/", "")
        if (!relative.startsWith("${FileFolders.UPLOAD}/") ||
            '/' in relative.substringAfter('/') || relative !in restoredPaths
        ) return url
        return restoredFileUrl(filesDir, relative)
    }
    fun remapAvatar(avatar: Avatar): Avatar = if (avatar is Avatar.Image) avatar.copy(url = remap(avatar.url)) else avatar

    return copy(
        displaySetting = displaySetting.copy(
            userAvatar = remapAvatar(displaySetting.userAvatar),
            chatBackgroundImagePath = remap(displaySetting.chatBackgroundImagePath),
        ),
        assistants = assistants.map { assistant -> assistant.copy(
            avatar = remapAvatar(assistant.avatar), background = assistant.background?.let(::remap),
        ) },
    )
}

private val CHAT_FILES_PATH = Regex("""^/data/(?:user(?:_de)?/[0-9]+|data)/[^/]+/files/(.+)$""")

/** Rebase only attachment URL fields. User text, tool input and metadata remain unchanged. */
internal fun remapRestoredMessageFileUrls(messagesJson: String, filesDir: File, restoredPaths: Set<String>): String {
    if (restoredPaths.isEmpty() || "/files/" !in messagesJson) return messagesJson
    fun remap(url: String): String {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return url
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return url
        val path = uri.path ?: return url
        if ('\\' in path || path.any(Char::isISOControl) || path.split('/').any { it == "." || it == ".." }) return url
        val relative = CHAT_FILES_PATH.matchEntire(path)?.groupValues?.get(1) ?: return url
        if (relative !in restoredPaths || relative.split('/').any { it.isEmpty() }) return url
        return restoredFileUrl(filesDir, relative)
    }
    fun remapUrl(part: JsonObject): JsonObject {
        val original = (part["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return part
        val restored = remap(original)
        return if (restored == original) part else JsonObject(part + ("url" to JsonPrimitive(restored)))
    }
    fun remapPart(element: JsonElement): JsonElement {
        val part = element as? JsonObject ?: return element
        return when ((part["type"] as? JsonPrimitive)?.contentOrNull) {
            "image", "document", "audio", "video" -> remapUrl(part)
            "tool" -> {
                val output = part["output"] as? JsonArray ?: return part
                JsonObject(part + ("output" to JsonArray(output.map(::remapPart))))
            }
            else -> part
        }
    }
    val messages = JsonInstant.parseToJsonElement(messagesJson) as? JsonArray
        ?: error("Не удалось прочитать вложения истории. Восстановление не применено; исходные данные сохранены.")
    val remapped = JsonArray(messages.map { element ->
        val message = element as? JsonObject ?: return@map element
        val parts = message["parts"] as? JsonArray
        if (parts != null) JsonObject(message + ("parts" to JsonArray(parts.map(::remapPart))))
        // Preserve the earlier helper's narrow legacy attachment-only representation.
        else if (message.keys == setOf("url")) remapUrl(message)
        else message
    })
    return if (remapped == messages) messagesJson else JsonInstant.encodeToString(remapped)
}

/**
 * resolveInside validates containment but returns a canonical path, and on Android
 * /data/user/0 is a link to /data/data. Keep the app's own filesDir spelling so the URL
 * matches what FilesManager and the attachment-reference checks build.
 */
private fun restoredFileUrl(filesDir: File, relative: String): String {
    PendingRestore.resolveInside(filesDir, relative)
    return File(filesDir, relative).toUri().toString()
}
