package me.rerere.rikkahub.miniapps

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.caverock.androidsvg.SVG
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.getActivity

/** Launch/share/archive compatibility with Moru; all exported files are immutable snapshots. */
object MiniAppLauncher {
    private const val MAX_ARCHIVE_BYTES = 25L * 1024 * 1024

    fun intent(context: Context, id: String): Intent {
        require(Regex("^[a-z0-9][a-z0-9-]{0,39}$").matches(id)) { "Недопустимый id мини-приложения." }
        return Intent(context, MiniAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(MiniAppStore.linkFor(id))
        }
    }

    fun open(context: Context, id: String) {
        context.startActivity(intent(context, id).apply {
            if (context.getActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun isArchiveName(name: String) = name.lowercase(Locale.ROOT).endsWith(MiniAppStore.archiveExtension)

    fun archiveName(context: Context, uri: Uri): String? = when (uri.scheme) {
        "content" -> context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        else -> uri.lastPathSegment
    }

    suspend fun importUri(context: Context, uri: Uri, store: MiniAppStore): MiniAppImportResult = withContext(Dispatchers.IO) {
        val name = archiveName(context, uri) ?: uri.lastPathSegment.orEmpty()
        if (!isArchiveName(name)) throw MiniAppException("invalid_archive", "Выберите файл .moruapp.")
        val folder = File(context.cacheDir, "mini-app-import/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(folder, "import.moruapp")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw MiniAppException("invalid_archive", "Не удалось прочитать файл мини-приложения.")
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val size = source.read(buffer)
                    if (size < 0) break
                    total += size
                    if (total > MAX_ARCHIVE_BYTES) throw MiniAppException("invalid_archive", "Архив больше 25 МиБ.")
                    output.write(buffer, 0, size)
                }
            } }
            store.importArchive(file)
        } finally { folder.deleteRecursively() }
    }

    suspend fun exportUri(context: Context, store: MiniAppStore, app: MiniApp, withData: Boolean): Uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "mini-app-share/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = store.exportArchive(app.id, withData, folder)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
    }

    fun share(context: Context, app: MiniApp, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, app.name)
            clipData = ClipData.newRawUri(app.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться мини-приложением").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    suspend fun pin(context: Context, app: MiniApp): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val icon = withContext(Dispatchers.IO) { renderIcon(app) }
        return ShortcutManagerCompat.requestPinShortcut(context,
            ShortcutInfoCompat.Builder(context, "mini_app_${app.id}")
                .setShortLabel(app.name).setLongLabel(app.name).setIcon(IconCompat.createWithBitmap(icon))
                .setIntent(intent(context, app.id)).build(), null)
    }

    internal fun renderIcon(app: MiniApp, size: Int = 192): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.clipPath(Path().apply { addRoundRect(bounds, size * .22f, size * .22f, Path.Direction.CW) })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 141, 222) }
        canvas.drawRoundRect(bounds, size * .22f, size * .22f, paint)
        val rendered = app.iconFile?.takeIf { it.isFile }?.let { file ->
            runCatching { file.inputStream().use { SVG.getFromInputStream(it).renderToCanvas(canvas, bounds) } }.isSuccess
        } ?: false
        if (!rendered) {
            paint.color = Color.rgb(90, 141, 222)
            canvas.drawRect(bounds, paint)
            paint.color = Color.WHITE
            paint.textSize = size * .5f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            canvas.drawText(miniAppInitial(app.name), size / 2f,
                size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        return bitmap
    }
}

/** Keep emoji/combining marks intact in both catalog and launcher fallback icons. */
internal fun miniAppInitial(name: String): String {
    if (name.isEmpty()) return "M"
    val iterator = android.icu.text.BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(name)
    return name.substring(0, iterator.next()).uppercase(Locale.ROOT)
}
