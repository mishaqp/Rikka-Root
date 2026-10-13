package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import me.rerere.rikkahub.data.files.FileFolders

data class GoogleFontEntry(val family: String, val url: String, val subsets: List<String>) {
    val packageName: String get() = family.lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^a-z0-9-]"), "")
}

data class DownloadedGoogleFont(val file: File, val license: String) {
    fun dispose() { file.parentFile?.deleteRecursively() }
}

/** Moru's public Expo snapshot, regular TTF face and licence use native app-managed files. */
class GoogleFontsRepository(private val directory: File, client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun loadCatalog(refresh: Boolean = false): List<GoogleFontEntry> = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val cache = File(directory, "catalog.json")
        val cached = runCatching { if (cache.length() <= CATALOG_LIMIT) parseCatalog(cache.readText()) else null }.getOrNull()
        if (!refresh && cached != null && System.currentTimeMillis() - cache.lastModified() < 7 * 86400000L) return@withContext cached
        try {
            val bytes = getBytes(CATALOG_URL, CATALOG_LIMIT)
            val fonts = parseCatalog(bytes.toString(Charsets.UTF_8))
            runCatching {
                val temporary = File.createTempFile("catalog-", ".tmp", directory)
                try { temporary.writeBytes(bytes); if (!temporary.renameTo(cache)) { cache.writeBytes(bytes) } }
                finally { temporary.delete() }
            }
            fonts
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!refresh && cached != null) cached else throw error
        }
    }

    suspend fun download(font: GoogleFontEntry, onProgress: (Float?) -> Unit = {}): DownloadedGoogleFont = withContext(Dispatchers.IO) {
        require(isFontUrl(font.url) && font.packageName.isNotBlank())
        directory.mkdirs()
        val temporary = File(directory, "download-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val license = getBytes("https://raw.githubusercontent.com/expo/google-fonts/main/font-packages/${font.packageName}/LICENSE_FONT", 256 * 1024).toString(Charsets.UTF_8)
            require(license.isNotBlank() && !license.trimStart().startsWith("<"))
            val bytes = getBytes(font.url, FONT_LIMIT, onProgress)
            require(validFontHeader(bytes))
            val file = File(temporary, "${font.packageName}.ttf").apply { writeBytes(bytes) }
            Typeface.createFromFile(file)
            DownloadedGoogleFont(file, license)
        } catch (error: Throwable) { temporary.deleteRecursively(); throw error }
    }

    private suspend fun getBytes(url: String, limit: Int, onProgress: (Float?) -> Unit = {}): ByteArray {
        val call = client.newCall(Request.Builder().url(url).build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
                }
            })
        }
        return response.use {
            check(it.code == 200) { "HTTP ${it.code}" }
            val body = checkNotNull(it.body)
            val total = body.contentLength()
            require(total <= limit) { "Response too large" }
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= limit) { "Response too large" }
                    output.write(buffer, 0, size)
                    onProgress(if (total > 0) output.size().toFloat() / total else null)
                }
                if (total > 0) check(output.size().toLong() == total) { "Incomplete response" }
                output.toByteArray()
            }
        }
    }

    companion object {
        const val CATALOG_URL = "https://raw.githubusercontent.com/expo/google-fonts/main/packages/generator/data/directory-data.json"
        private const val CATALOG_LIMIT = 8 * 1024 * 1024
        internal const val FONT_LIMIT = 64 * 1024 * 1024

        fun isFontUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let {
            it.isHttps && it.host == "fonts.gstatic.com" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath.endsWith(".ttf")
        } == true

        fun parseCatalog(text: String): List<GoogleFontEntry> {
            val fonts = Json.parseToJsonElement(text).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val family = row["family"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val original = row["files"]?.jsonObject?.get("regular")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = original.replaceFirst(Regex("^http://"), "https://")
                if (!isFontUrl(url)) return@mapNotNull null
                GoogleFontEntry(family, url, row["subsets"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull })
            }.sortedBy { it.family.lowercase() }
            require(fonts.isNotEmpty()) { "Empty font catalog" }
            return fonts
        }

        internal fun validFontHeader(bytes: ByteArray): Boolean = bytes.size >= 4 &&
            (bytes.take(4) == listOf(0.toByte(), 1.toByte(), 0.toByte(), 0.toByte()) || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OTTO")
    }
}

internal data class ImportedInterfaceFont(val path: String, val name: String)

internal fun importInterfaceFont(context: Context, input: InputStream, name: String, target: String, license: String? = null): ImportedInterfaceFont {
    val directory = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val file = File(directory, "${target}_font.${UUID.randomUUID()}.ttf")
    try {
        input.use { source -> file.outputStream().use { output ->
            var total = 0L
            val buffer = ByteArray(8192)
            while (true) { val count = source.read(buffer); if (count < 0) break
                total += count; require(total <= GoogleFontsRepository.FONT_LIMIT); output.write(buffer, 0, count) }
        } }
        Typeface.createFromFile(file)
        license?.let { File(directory, "${file.name}.license").writeText(it) }
        return ImportedInterfaceFont("${FileFolders.FONTS}/${file.name}", name)
    } catch (error: Throwable) { file.delete(); throw error }
}
