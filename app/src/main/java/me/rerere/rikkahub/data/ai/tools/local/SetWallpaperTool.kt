// Adapted from ExTV/rikkahub-agent, local/SetWallpaperTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException

/** Immutable, bounded bytes keep the image header and actual decode consistent. */
internal fun readBoundedImage(input: InputStream, maxBytes: Int = 16_777_216): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= maxBytes)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun setWallpaperTool(context: Context, sources: LocalImageSources = LocalImageSources(File(context.filesDir, "upload"))): Tool = Tool(
    name = "set_wallpaper",
    description = "Set home, lock, or both wallpapers from a local image (at most 16 MiB): file://, content://, a PRoot/workspace path such as /workspace/image.png, or attachment:1 for the first image in the latest user message containing images. Relative paths use this chat's workspace current directory. Large images are sampled safely. Default target: both.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("file_uri", buildJsonObject { put("type", "string"); put("description", "Local URI, PRoot/workspace file path, or attachment:1 (1-based image index)") })
        put("target", buildJsonObject { put("type", "string"); put("description", "home, lock, or both (default both)") })
    }, required = listOf("file_uri")) },
    needsApproval = { true },
    systemPrompt = { _, _ -> sources.attachmentPrompt() },
    execute = { input -> deviceToolResult { withContext(Dispatchers.IO) {
        val obj = input as? JsonObject ?: return@withContext deviceToolError("Параметры должны быть объектом.")
        val source = obj.textArgument("file_uri")?.takeIf { it.length <= 8192 }
            ?: return@withContext deviceToolError("Укажите file_uri: путь изображения, file://, content:// или attachment:1.")
        val target = if (obj.containsKey("target")) obj.textArgument("target") else "both"
        val flags = when (target) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> return@withContext deviceToolError("target должен быть home, lock или both.")
        }
        val resolved = try {
            sources.resolve(source)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext deviceToolError("Не удалось найти или разрешить изображение «$source». Нужен локальный путь в выбранном workspace, file://, content:// с доступом Android или attachment:1 из последнего сообщения с изображениями.")
        }
        if (resolved is LocalImageSource.LocalFile) {
            if (!resolved.file.isFile || !resolved.file.canRead()) return@withContext deviceToolError(resolved.unavailableMessage())
            if (resolved.file.length() !in 1..16_777_216L) return@withContext deviceToolError("Изображение «$source» пустое или превышает 16 МиБ.")
        }
        val manager = WallpaperManager.getInstance(context)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return@withContext deviceToolError("Android запретил смену обоев на этом устройстве или в этом профиле.")
        try {
            val bytes = try {
                val stream = when (resolved) {
                    is LocalImageSource.LocalFile -> resolved.file.inputStream()
                    is LocalImageSource.ContentUri -> context.contentResolver.openInputStream(Uri.parse(resolved.uri))
                } ?: return@withContext deviceToolError(resolved.unavailableMessage())
                stream.use { readBoundedImage(it) }
            } catch (_: IllegalArgumentException) {
                return@withContext deviceToolError("Изображение «$source» недопустимо или превышает 16 МиБ.")
            } catch (_: IOException) {
                return@withContext deviceToolError(resolved.unavailableMessage())
            } catch (_: SecurityException) {
                return@withContext deviceToolError("Нет доступа к изображению «$source». Прикрепите его в чат или выберите снова через системное меню Android.")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext deviceToolError("Файл «$source» не является читаемым изображением.")
            val options = BitmapFactory.Options().apply { inSampleSize = wallpaperSampleSize(bounds.outWidth, bounds.outHeight); inScaled = false }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext deviceToolError("Не удалось прочитать изображение.")
            try {
                val id = manager.setBitmap(bitmap, null, true, flags)
                if (id <= 0) deviceToolError("Android не применил обои.")
                else buildJsonObject { put("success", true); put("target", target); put("wallpaper_id", id) }
            } finally {
                bitmap.recycle()
            }
        } catch (_: OutOfMemoryError) {
            deviceToolError("Для этого изображения недостаточно памяти. Выберите изображение меньшего размера.")
        }
    } } },
)
