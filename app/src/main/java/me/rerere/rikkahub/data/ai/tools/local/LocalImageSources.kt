package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Paths

internal sealed interface LocalImageSource {
    val requested: String
    data class LocalFile(val file: File, override val requested: String) : LocalImageSource
    data class ContentUri(val uri: String, override val requested: String) : LocalImageSource

    fun unavailableMessage(): String = when (this) {
        is LocalFile -> "Изображение не найдено или недоступно: «$requested» (путь приложения: «${file.path}»)."
        is ContentUri -> "Изображение не найдено или недоступно: «$requested» (URI: «$uri»)."
    }
}

/** The caller supplies the conversation's workspace, never the assistant's mutable global defaults. */
class LocalImageSources(
    private val uploadDir: File,
    private val workspaceCwd: String? = null,
    private val chatImages: List<String> = emptyList(),
    private val resolveWorkspacePath: (suspend (String) -> File)? = null,
) {
    internal suspend fun resolve(reference: String): LocalImageSource {
        require(reference.isNotBlank() && reference.length <= 8192 && reference.none { it.isISOControl() }) { "Некорректный путь изображения." }
        val source = if (reference.startsWith("attachment:")) {
            val index = reference.removePrefix("attachment:").toIntOrNull()
            require(index != null && index in 1..chatImages.size) { "Вложение «$reference» не найдено в последнем сообщении пользователя с изображениями." }
            chatImages[index - 1]
        } else reference
        require(source.length <= 8192 && source.none { it.isISOControl() }) { "Некорректный путь изображения «$reference»." }
        if (source.startsWith("content:")) {
            val uri = localImageUri(source, reference)
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.fragment == null) { "Некорректный content:// URI: «$reference»." }
            return LocalImageSource.ContentUri(source, reference)
        }
        val path = if (source.startsWith("file:")) {
            val uri = localImageUri(source, reference)
            require(uri.scheme == "file" && uri.authority == null && uri.query == null && uri.fragment == null) { "Нужен локальный file:// URI: «$reference»." }
            File(uri).path
        } else {
            require(!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(source)) { "Нужен локальный путь, content:// URI или вложение: «$reference»." }
            source
        }
        val host = File(path)
        val file = when {
            path == "/upload" || path.startsWith("/upload/") -> {
                val root = uploadDir.canonicalFile
                File(root, path.removePrefix("/upload").trimStart('/')).canonicalFile.also {
                    require(it.toPath().startsWith(root.toPath())) { "Путь выходит из /upload: «$reference»." }
                }
            }
            resolveWorkspacePath != null && (!source.startsWith("file:") || path == "/workspace" || path.startsWith("/workspace/")) -> {
                val cwd = Paths.get("/workspace").resolve(workspaceCwd?.takeIf { it.isNotBlank() } ?: ".")
                val rootfsPath = cwd.resolve(path).normalize().toString()
                resolveWorkspacePath(rootfsPath).canonicalFile
            }
            else -> {
                require(host.isAbsolute) { "Для относительного пути «$reference» выберите workspace в этом чате." }
                require(!path.startsWith("/workspace/")) { "Для пути «$reference» выберите workspace в этом чате." }
                host.canonicalFile
            }
        }
        return LocalImageSource.LocalFile(file, reference)
    }

    internal fun attachmentPrompt(): String = if (chatImages.isEmpty()) "" else
        "set_wallpaper can use the images in the latest user message containing images: " +
            chatImages.indices.joinToString { "attachment:${it + 1}" } + ". Attachment numbers are 1-based in message order."
}

private fun localImageUri(source: String, requested: String): URI = try { URI(source) } catch (_: URISyntaxException) {
    throw IllegalArgumentException("Некорректный URI изображения: «$requested».")
}

internal fun wallpaperChatImages(messages: List<UIMessage>): List<String> = messages.asReversed()
    .firstOrNull { it.role == MessageRole.USER && it.parts.any { part -> part is UIMessagePart.Image } }
    ?.parts?.filterIsInstance<UIMessagePart.Image>()?.map { it.url }.orEmpty()
