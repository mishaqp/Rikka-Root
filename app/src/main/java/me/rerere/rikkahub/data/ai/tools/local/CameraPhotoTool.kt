// Adapted from ExTV/rikkahub-agent, local/CameraPhotoTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import java.io.ByteArrayOutputStream
import java.io.File

fun cameraPhotoTool(context: Context, files: FilesManager): Tool = Tool(
    name="take_photo", description="Open the system camera so the user explicitly takes a photo. Requires foreground app and CAMERA permission. Saves a durable image attachment to this chat's upload area and returns file_uri, workspace_path (/upload/...), width and height. Cancelled/empty photos are removed; no automatic gallery copy.",
    parameters={ InputSchema.Obj(buildJsonObject {}) }, needsApproval={ true }, execute={ input -> deviceToolParts {
        if (input !is JsonObject) return@deviceToolParts listOf(UIMessagePart.Text(deviceToolError("Параметры должны быть объектом.").toString()))
        val missing=missingLocalToolPermissions(context,LocalToolOption.CameraPhoto)
        if (missing.isNotEmpty()) return@deviceToolParts listOf(UIMessagePart.Text(deviceToolError("Разрешите камеру в настройках функции.",missing.first()).toString()))
        val directory=File(context.cacheDir,"tool-photos").apply { mkdirs() }
        val temporary=File.createTempFile("photo_", ".jpg",directory)
        try {
            val result=awaitPersonalToolUi(context,PersonalUiRequest.Camera(temporary),300000)
            if (result !is PersonalUiResult.Photo) return@deviceToolParts listOf(UIMessagePart.Text(personalUiError(result).toString()))
            withContext(Dispatchers.IO) {
                if (!temporary.isFile || temporary.length() !in 1..33_554_432L) return@withContext listOf(UIMessagePart.Text(deviceToolError("Камера не создала допустимое фото. Пустой или слишком большой файл удалён.").toString()))
                try {
                    val bytes=temporary.inputStream().use { readBoundedImage(it,33_554_432) }
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext listOf(UIMessagePart.Text(deviceToolError("Камера вернула повреждённое изображение.").toString()))
                    val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {
                        inSampleSize=wallpaperSampleSize(bounds.outWidth,bounds.outHeight); inScaled=false
                    }) ?: return@withContext listOf(UIMessagePart.Text(deviceToolError("Не удалось обработать фото.").toString()))
                    val bitmap=try {
                        val orientation=temporary.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }
                        val matrix=Matrix()
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f,1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f,-1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f,1f) }
                            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f,1f) }
                        }
                        if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
                    } catch (error: Throwable) { decoded.recycle(); throw error }
                    if (bitmap !== decoded) decoded.recycle()
                    try {
                        val encoded=ByteArrayOutputStream()
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,encoded))
                        require(encoded.size() in 1..16_777_216)
                        val entity=files.saveManagedFromBytes(FileFolders.UPLOAD,encoded.toByteArray(),"Фото.jpg","image/jpeg")
                        val file=files.getFile(entity)
                        listOf(UIMessagePart.Image(file.toUri().toString()),UIMessagePart.Text(buildJsonObject {
                            put("success",true); put("file_uri",file.toUri().toString()); put("workspace_path","/upload/${file.name}")
                            put("width",bitmap.width); put("height",bitmap.height); put("saved_to","chat_attachment")
                        }.toString()))
                    } finally { bitmap.recycle() }
                } catch (_: OutOfMemoryError) { listOf(UIMessagePart.Text(deviceToolError("Недостаточно памяти для фото. Выберите меньший размер в приложении камеры.").toString())) }
            }
        } finally { temporary.delete() }
    } },
)
