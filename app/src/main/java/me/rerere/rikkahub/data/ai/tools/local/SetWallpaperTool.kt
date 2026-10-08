// Adapted from ExTV/rikkahub-agent, local/SetWallpaperTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI

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

fun setWallpaperTool(context: Context): Tool = Tool(
    name = "set_wallpaper",
    description = "Set home, lock, or both wallpapers from a readable local file:// image (at most 16 MiB). Large images are sampled safely. Default target: both.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("file_uri", buildJsonObject { put("type", "string") })
        put("target", buildJsonObject { put("type", "string"); put("description", "home, lock, or both (default both)") })
    }, required = listOf("file_uri")) },
    needsApproval = { true },
    execute = { input -> deviceToolResult { withContext(Dispatchers.IO) {
        val obj = input as? JsonObject ?: return@withContext deviceToolError("Параметры должны быть объектом.")
        val source = obj.textArgument("file_uri")?.takeIf { it.length <= 8192 }
            ?: return@withContext deviceToolError("Укажите file_uri локального изображения.")
        val target = if (obj.containsKey("target")) obj.textArgument("target") else "both"
        val flags = when (target) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> return@withContext deviceToolError("target должен быть home, lock или both.")
        }
        val file = try {
            val uri = URI(source)
            require(uri.scheme == "file" && uri.authority == null && uri.query == null && uri.fragment == null)
            File(uri)
        } catch (_: IllegalArgumentException) {
            return@withContext deviceToolError("Нужен локальный file:// URI без сетевого адреса, параметров и фрагмента.")
        }
        if (!file.isFile || file.length() !in 1..16_777_216L) return@withContext deviceToolError("Изображение отсутствует, пустое или превышает 16 МиБ.")
        val manager = WallpaperManager.getInstance(context)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return@withContext deviceToolError("Android запретил смену обоев на этом устройстве или в этом профиле.")
        try {
            val bytes = try { file.inputStream().use { readBoundedImage(it) } } catch (_: IllegalArgumentException) {
                return@withContext deviceToolError("Изображение превышает 16 МиБ.")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext deviceToolError("Файл не является читаемым изображением.")
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
