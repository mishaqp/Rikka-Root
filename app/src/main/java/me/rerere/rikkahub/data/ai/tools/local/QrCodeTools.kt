package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.ByteArrayOutputStream
import java.util.UUID

internal fun qrCodeTools(
    context: Context,
    access: LocalFileAccess,
    modelCanReadImages: Boolean = false,
): List<Tool> {
    val sources = access.withQrScreenshotReads(context).imageSources()
    return listOf(
        Tool(
            name = "qr_generate",
            description = "Создать QR-код в PNG для текста от 1 до 2000 символов. size — целое число от 128 до 1024, по умолчанию 512; увеличьте размер, если текст не помещается. Файл сохраняется в /scratch; результат содержит path, file_uri и размеры. Изображение возвращается только модели с поддержкой изображений.",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("text", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 2000) })
                put("size", buildJsonObject { put("type", "integer"); put("minimum", 128); put("maximum", 1024); put("default", 512) })
            }, required = listOf("text")) },
            needsApproval = { true },
            execute = { input -> deviceToolParts { withContext(Dispatchers.IO) {
                val obj = requireNotNull(input as? JsonObject) { "Параметры должны быть объектом." }
                val text = fileArgument(obj, "text")
                require(text.isNotEmpty() && text.length <= 2000) { "Текст должен содержать от 1 до 2000 символов." }
                val size = fileInteger(obj, "size", 512, 128..1024)
                val png = encodeQrPng(text, size)
                currentCoroutineContext().ensureActive()
                val name = "qr-${UUID.randomUUID()}.png"
                val saved = access.createNewChild(access.resolve("/scratch"), name)
                var complete = false
                try {
                    access.write(saved, png, overwrite = true)
                    currentCoroutineContext().ensureActive()
                    val file = (saved as LocalFileSource.Local).file
                    val metadata = buildJsonObject {
                        put("success", true); put("path", "/scratch/$name"); put("file_uri", "file://${file.absolutePath}")
                        put("width", size); put("height", size); put("size_bytes", png.size)
                        put("visible_to_you", modelCanReadImages)
                    }
                    val parts = buildList {
                        if (modelCanReadImages) add(UIMessagePart.Image("file://${file.absolutePath}"))
                        add(UIMessagePart.Text(metadata.toString()))
                    }
                    complete = true
                    parts
                } finally {
                    if (!complete) withContext(NonCancellable) { runCatching { access.delete(saved) } }
                }
            } } },
        ),
        Tool(
            name = "qr_scan",
            description = "Прочитать QR-коды и штрихкоды из локального изображения: путь в /scratch или выбранном рабочем пространстве, разрешённый content:// URI, file_path снимка экрана либо attachment:1 из последнего сообщения пользователя с изображениями. Каждый найденный код возвращается с текстом и форматом; если кодов нет, возвращается пустой массив. Ограничения: 16 МиБ и 8 миллионов пикселей. Исходное изображение сохраняется.",
            parameters = { fileSchema(listOf("path"), "path" to "string") },
            needsApproval = { false },
            execute = { input -> deviceToolResult { withContext(Dispatchers.IO) {
                val obj = requireNotNull(input as? JsonObject) { "Параметры должны быть объектом." }
                val path = fileArgument(obj, "path")
                val source = sources.resolve(path)
                val bytes = requireNotNull(sources.openInput(source) { context.contentResolver.openInputStream(Uri.parse(it)) }) {
                    "Не удалось открыть изображение. Выберите файл или прикрепите его заново."
                }.use { readBoundedImage(it) }
                currentCoroutineContext().ensureActive()
                val codes = scanQrImage(bytes)
                currentCoroutineContext().ensureActive()
                buildJsonObject {
                    put("success", true); put("path", path)
                    put("codes", buildJsonArray { codes.forEach { code -> add(buildJsonObject {
                        put("text", code.text); put("format", code.barcodeFormat.name)
                    }) } })
                    put("count", codes.size)
                }
            } } },
        ),
    )
}

private fun encodeQrPng(text: String, size: Int): ByteArray {
    val matrix = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    } catch (failure: com.google.zxing.WriterException) {
        throw IllegalArgumentException("Не удалось создать QR-код. Сократите текст или увеличьте размер.", failure)
    }
    require(matrix.width == size && matrix.height == size) { "Текст не помещается в выбранный размер QR-кода. Увеличьте size." }
    val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) -0x1000000 else -1 }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    return try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Не удалось сохранить QR-код в PNG. Проверьте свободное место." }
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
}

private suspend fun scanQrImage(bytes: ByteArray): List<Result> {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Файл не является читаемым изображением. Выберите исправный PNG или JPEG." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 8_388_608L) {
            "Изображение превышает 8 миллионов пикселей. Выберите изображение меньшего размера."
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "Не удалось прочитать изображение. Прикрепите исправный файл заново." }
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
            val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
            val codes = mutableListOf<Result>()
            currentCoroutineContext().ensureActive()
            try { codes.addAll(QRCodeMultiReader().decodeMultiple(image, hints)) } catch (_: NotFoundException) { }
            currentCoroutineContext().ensureActive()
            try {
                GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(image, hints).forEach { code ->
                    // QRCodeMultiReader preserves separate copies of equal-valued QR codes.
                    // Skip generic rediscoveries of those same QR results, retaining all others.
                    if (codes.none { it.barcodeFormat == code.barcodeFormat && it.text == code.text }) codes += code
                }
            } catch (_: NotFoundException) { }
            codes
        } finally { bitmap.recycle() }
    } catch (failure: OutOfMemoryError) {
        throw IllegalArgumentException("Недостаточно памяти для изображения. Выберите изображение меньшего размера.", failure)
    }
}
