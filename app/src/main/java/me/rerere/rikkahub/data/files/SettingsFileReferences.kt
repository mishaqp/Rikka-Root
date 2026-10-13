package me.rerere.rikkahub.data.files

import androidx.core.net.toUri
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import java.io.File

/** Selected files remain owned by settings even when their display option is disabled. */
fun Settings.localFileUrls(filesDir: File? = null): Set<String> = buildSet {
    fun addImage(url: String?) {
        if (url?.startsWith("file:") == true) add(url)
    }

    addImage((displaySetting.userAvatar as? Avatar.Image)?.url)
    addImage(displaySetting.chatBackgroundImagePath)
    assistants.forEach { assistant ->
        addImage((assistant.avatar as? Avatar.Image)?.url)
        addImage(assistant.background)
    }

    if (filesDir != null) {
        listOf(displaySetting.chatCustomFontPath, displaySetting.appCustomFontPath,
            displaySetting.codeCustomFontPath).filter { it.isNotBlank() }.forEach { path ->
            val file = runCatching {
                File(filesDir, path).canonicalFile.takeIf {
                    it.path.startsWith(filesDir.canonicalPath + File.separator)
                }
            }.getOrNull() ?: return@forEach
            add(file.toUri().toString())
            add(File(file.path + ".license").toUri().toString())
        }
    }
}
