package me.rerere.rikkahub.data.sync

import androidx.core.net.toUri
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

private val CHAT_UPLOAD_URL = Regex("""file://(/[^"\\\s]*?)/files/(${FileFolders.UPLOAD}/[^"\\/\s]+)""")

/**
 * Chat messages store absolute file URLs of the device that made the backup. A restore into
 * another app ID or user profile puts the bytes under this filesDir, so rebase the restored
 * upload files there; anything not restored from this archive is left untouched.
 */
internal fun remapRestoredMessageFileUrls(messagesJson: String, filesDir: File, restoredPaths: Set<String>): String {
    if (restoredPaths.isEmpty() || "/files/${FileFolders.UPLOAD}/" !in messagesJson) return messagesJson
    return CHAT_UPLOAD_URL.replace(messagesJson) { match ->
        val relative = match.groupValues[2]
        if (relative !in restoredPaths) match.value
        else restoredFileUrl(filesDir, relative)
    }
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
