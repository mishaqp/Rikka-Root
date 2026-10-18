// Adapted from ExTV/rikkahub-agent, local/SetWallpaperTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
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
        require(output.size().toLong() + count <= maxBytes) { "Изображение превышает допустимый размер $maxBytes байт. Выберите изображение меньшего размера." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun setWallpaperTool(context: Context, sources: LocalImageSources = LocalImageSources(
    File(context.filesDir, "upload"), fileAccess = LocalFileAccess(context))): Tool = Tool(
    name = "set_wallpaper",
    description = "Установить обои home, lock или both (по умолчанию) из локального изображения до 16 МиБ. file_uri принимает file://, разрешённый content://, путь рабочего пространства или attachment:1 из последнего сообщения с изображениями; относительный путь считается от текущей папки чата. Большие изображения уменьшаются при чтении. Для создания QR-изображения используйте qr_generate. Возвращает success, target и wallpaper_id; профиль Android может запрещать смену обоев.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("file_uri", buildJsonObject { put("type", "string"); put("description", "Путь локального изображения, file://, разрешённый content:// или attachment:1. До 8192 символов; изображение до 16 МиБ."); put("maxLength", 8192) })
        put("target", buildJsonObject { put("type", "string"); put("description", "Экран обоев: home — главный, lock — блокировка, both — оба (по умолчанию)."); put("enum", JsonArray(listOf(JsonPrimitive("home"), JsonPrimitive("lock"), JsonPrimitive("both")))); put("default", "both") })
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
            if (!resolved.file.isFile || !resolved.file.canRead()) return@withContext deviceToolError(resolved.unavailableMessage() + " Прикрепите изображение в чат или выберите файл снова через системное меню Android.")
            if (resolved.file.length() !in 1..16_777_216L) return@withContext deviceToolError("Изображение «$source» пустое или превышает 16 МиБ. Выберите непустое изображение меньшего размера.")
        }
        val manager = WallpaperManager.getInstance(context)
        if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return@withContext deviceToolError("Android запретил смену обоев на этом устройстве или в этом профиле. Проверьте ограничения профиля в настройках Android.")
        try {
            val bytes = try {
                val stream = sources.openInput(resolved) { uri -> context.contentResolver.openInputStream(Uri.parse(uri)) }
                    ?: return@withContext deviceToolError(resolved.unavailableMessage() + " Прикрепите изображение в чат или выберите файл снова через системное меню Android.")
                stream.use { readBoundedImage(it) }
            } catch (_: IllegalArgumentException) {
                return@withContext deviceToolError("Изображение «$source» недопустимо или превышает 16 МиБ. Выберите другое изображение меньшего размера.")
            } catch (_: IOException) {
                return@withContext deviceToolError(resolved.unavailableMessage() + " Прикрепите изображение в чат или выберите файл снова через системное меню Android.")
            } catch (_: SecurityException) {
                return@withContext deviceToolError("Нет доступа к изображению «$source». Прикрепите его в чат или выберите снова через системное меню Android.")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext deviceToolError("Файл «$source» не является читаемым изображением. Выберите исправное изображение JPEG, PNG или другого поддерживаемого Android формата.")
            val options = BitmapFactory.Options().apply { inSampleSize = wallpaperSampleSize(bounds.outWidth, bounds.outHeight); inScaled = false }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext deviceToolError("Не удалось прочитать изображение. Выберите другое изображение или прикрепите его в чат заново.")
            try {
                val id = manager.setBitmap(bitmap, null, true, flags)
                if (id <= 0) deviceToolError("Android не применил обои. Проверьте ограничения профиля и выберите другое изображение.")
                else buildJsonObject { put("success", true); put("target", target); put("wallpaper_id", id) }
            } finally {
                bitmap.recycle()
            }
        } catch (_: OutOfMemoryError) {
            deviceToolError("Для этого изображения недостаточно памяти. Выберите изображение меньшего размера.")
        }
    } } },
)
