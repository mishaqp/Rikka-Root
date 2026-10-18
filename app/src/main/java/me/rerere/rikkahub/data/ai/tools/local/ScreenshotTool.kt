// Adapted from RikkaHub Agent (AGPL-3.0): root replaces the Accessibility backend.
package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import org.koin.core.context.GlobalContext
import kotlin.coroutines.cancellation.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SCREENSHOT_CACHE_DIR = "screenshots"
private const val PICTURES_SUBDIR = "RikkaHub/Screenshots"
private const val PRUNE_OLDER_THAN_MS = 60L * 60L * 1000L  // 1 hour — cache only

private fun pruneOldCacheScreenshots(dir: File) {
    val cutoff = System.currentTimeMillis() - PRUNE_OLDER_THAN_MS
    dir.listFiles()?.forEach { f ->
        if (f.lastModified() < cutoff) f.delete()
    }
}

/**
 * The cache copy is pruned after an hour, but the image stays in the chat history. Move it to the
 * chat's managed uploads, which are deleted together with the conversation that references them.
 */
internal suspend fun keepWithChat(
    cacheFile: File,
    displayName: String,
    files: FilesManager? = GlobalContext.getOrNull()?.getOrNull<FilesManager>(),
): File {
    if (files == null) return cacheFile
    return try {
        val entity = files.saveUploadFromBytes(cacheFile.readBytes(), displayName, "image/png")
        files.getFile(entity).also { cacheFile.delete() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        cacheFile
    }
}

fun takeScreenshotTool(context: Context, service: RootScreenService): Tool = Tool(
    name = "take_screenshot",
    description = "Снять основной экран (display_id=0) через root screencap и вернуть изображение модели. PNG по умолчанию сохраняется приватно с чатом; save_to_gallery=true — только по просьбе пользователя, публичная копия появится в Pictures/RikkaHub/Screenshots, путь — gallery_path. Банковские/DRM/защищённые поверхности могут быть пустыми. Захват ограничен тайм-аутом, результат содержит file_path и screen_state. Для проверки выполненного действия сначала используйте его after; снимок нужен для визуальных деталей.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("display_id", buildJsonObject {
                    put("type", "integer"); put("description", "Поддерживается только основной физический экран: 0, по умолчанию 0. Другие дисплеи не поддерживаются root-захватом."); put("enum", JsonArray(listOf(JsonPrimitive(0)))); put("minimum", 0); put("default", 0) })
                put("save_to_gallery", buildJsonObject {
                    put("type", "boolean"); put("description", "Сохранить публичную копию в галерее только по просьбе пользователя; по умолчанию false, файл остаётся приватным."); put("default", false) })
            }
        )
    },
    execute = { input ->
        screenArgumentError(input, "display_id" to "integer", "save_to_gallery" to "boolean")
            ?.let { return@Tool listOf(UIMessagePart.Text(it.toString())) }
        val displayId = input.jsonObject["display_id"]?.jsonPrimitive?.longOrNull ?: 0L
        val shouldSaveToGallery = input.jsonObject["save_to_gallery"]?.jsonPrimitive?.booleanOrNull ?: false
        if (displayId != 0L) return@Tool listOf(UIMessagePart.Text(buildJsonObject {
            put("error", "unsupported_display")
            put("message", "Для root-захвата поддерживается только основной экран: укажите display_id=0.")
        }.toString()))

        val outcome = service.withService(treeRequired = false) { svc ->
            val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_DIR).apply { mkdirs() }
            pruneOldCacheScreenshots(cacheDir)
            val res = svc.captureScreenshot(displayId.toInt())
            when (res) {
                is RootScreenService.ScreenshotOutcome.Failure -> {
                    buildJsonObject {
                        put("error", "screenshot_unavailable")
                        put("reason", res.reason)
                    }
                }

                is RootScreenService.ScreenshotOutcome.Success -> {
                    val ts = System.currentTimeMillis()
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))
                    val displayName = "Screenshot_${timestamp}.png"

                    // 1) Always write a cache copy — this is the path attached to the LLM as
                    //    inline vision (file:// uri readable by the encoder; reliable across
                    //    Android versions, regardless of MediaStore success).
                    val cacheFile = File(File(context.cacheDir, SCREENSHOT_CACHE_DIR), "screen-$ts.png")
                    try {
                        FileOutputStream(cacheFile).use { os ->
                            check(res.bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                                "Не удалось записать снимок. Проверьте свободное место."
                            }
                        }
                    } catch (t: Throwable) {
                        res.bitmap.recycle()
                        cacheFile.delete()
                        return@withService buildJsonObject {
                            put("error", "write_failed")
                            put("reason", deviceToolFailureReason(t))
                        }
                    }

                    // Public storage is an explicit opt-in; normal tool captures stay private.
                    val galleryPath = if (shouldSaveToGallery) saveToGallery(context, res.bitmap, displayName) else null
                    res.bitmap.recycle()
                    val storedFile = keepWithChat(cacheFile, displayName)

                    buildJsonObject {
                        put("success", true)
                        put("file_path", storedFile.absolutePath)
                        if (shouldSaveToGallery) {
                            put("gallery_path", galleryPath ?: "(gallery_save_failed)")
                            if (galleryPath == null) put("warning", "Снимок получен, но копию в галерею сохранить не удалось. Проверьте свободное место и доступ к фото; сам снимок доступен в чате.")
                            else put("saved_to", "Pictures/$PICTURES_SUBDIR")
                        }
                        put("screen_state", screenStateJson(svc, screenChanged = null))
                    }
                }
            }
        }

        val parts = mutableListOf<UIMessagePart>()
        outcome.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(outcome.toString()))
        parts
    }
)

/**
 * Persist [bitmap] as a PNG into the device gallery at Pictures/RikkaHub/Screenshots/.
 *
 * Q+ (API 29+): use MediaStore (no permission required for own-app inserts; visible to
 * the user's Gallery app via media indexing).
 *
 * Pre-Q (API 26-28): write directly to the public Pictures directory. WRITE_EXTERNAL_STORAGE
 * is granted from the manifest's pre-Q permission; if it isn't, the write throws and we
 * return null — the LLM still gets the cache copy, only the gallery copy is missing.
 *
 * Returns the absolute on-device path the user can navigate to, or null on any failure.
 */
private fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): String? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$PICTURES_SUBDIR"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

            try {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("Не удалось открыть файл снимка в галерее.")
                stream.use { os ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                        "Не удалось записать снимок в галерею."
                    }
                }

                val finalize = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(context.contentResolver.update(uri, finalize, null, null) > 0) {
                    "Не удалось опубликовать снимок в галерее."
                }

                // Resolve the public on-device path for the JSON envelope and ActionLog.
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DATA),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: "Pictures/$PICTURES_SUBDIR/$displayName"
            } catch (error: Throwable) {
                // Failed writes/finalization must not leave a hidden IS_PENDING row behind.
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(pictures, PICTURES_SUBDIR).apply { mkdirs() }
            val out = File(targetDir, displayName)
            try {
                FileOutputStream(out).use { os ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                        "Не удалось записать снимок в галерею."
                    }
                }
            } catch (error: Throwable) {
                // A half-written PNG must not appear in the user's gallery.
                out.delete()
                throw error
            }
            // Tell MediaScanner so the gallery picks it up promptly.
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(out.absolutePath), arrayOf("image/png"), null
            )
            out.absolutePath
        }
    }.getOrNull()
}
