package me.rerere.rikkahub.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import me.rerere.rikkahub.skills.js.JsSkillRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/** Root's required isolation adapter for the Agent skill viewer; same origin as JsSkillRunner. */
class BrowserSkillScope private constructor(
    private val root: File,
    private val entry: File,
    private val wrapper: Boolean,
    query: String?,
    fragment: String?,
) {
    val originHost: String = JsSkillRunner.skillOriginHost(root.name)
    val initialUrl: String = Uri.parse(if (wrapper) "https://$originHost/viewer/${Uri.encode(entry.name)}"
        else requireNotNull(skillUrl(root, entry))).buildUpon()
        .encodedQuery(query).encodedFragment(fragment).build().toString()

    fun assetLoader(context: Context): WebViewAssetLoader {
        val storageHandler = WebViewAssetLoader.InternalStoragePathHandler(context, root)
        return WebViewAssetLoader.Builder()
            .setDomain(originHost)
            .addPathHandler("/skill/", WebViewAssetLoader.PathHandler { path ->
                // Check canonical paths before opening a stream, including symlink traversal.
                val target = runCatching { File(root, path).canonicalFile }.getOrNull()
                if (target == null || !inside(root, target)) return@PathHandler deniedResponse()
                val response = storageHandler.handle(path) ?: return@PathHandler null
                if (target == entry && response.mimeType != "text/html") {
                    WebResourceResponse("text/html", response.encoding, response.statusCode,
                        response.reasonPhrase, response.responseHeaders, response.data)
                } else response
            })
            .addPathHandler("/viewer/", WebViewAssetLoader.PathHandler { path ->
                if (!wrapper || path != entry.name || !entry.isFile) return@PathHandler deniedResponse()
                val input = runCatching { FileInputStream(entry) }.getOrNull()
                    ?: return@PathHandler deniedResponse()
                WebResourceResponse("text/html", "UTF-8", input)
            })
            .build()
    }

    fun isForeignSkillOrigin(uri: Uri): Boolean = uri.host?.let {
        it.endsWith(".appassets.androidplatform.net", ignoreCase = true) &&
            !it.equals(originHost, ignoreCase = true)
    } == true

    companion object {
        data class ViewerTarget(val scope: BrowserSkillScope?, val initialUrl: String)

        /** Agent supports direct http(s) viewers as well as files and cached iframe wrappers. */
        fun resolveViewer(context: Context, root: File, url: String): ViewerTarget? =
            when (Uri.parse(url).scheme?.lowercase()) {
                "file" -> create(context, root, url)?.let { ViewerTarget(it, it.initialUrl) }
                "http", "https", "data" -> ViewerTarget(null, url)
                else -> null
            }

        internal fun inside(root: File, file: File): Boolean =
            file.path.startsWith(root.path + File.separator)

        fun skillUrl(root: File, file: File): String? = runCatching {
            val canonicalRoot = root.canonicalFile
            val canonicalFile = file.canonicalFile
            if (!inside(canonicalRoot, canonicalFile)) return@runCatching null
            val relative = canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
            "https://${JsSkillRunner.skillOriginHost(canonicalRoot.name)}/skill/" +
                relative.split('/').joinToString("/") { Uri.encode(it) }
        }.getOrNull()

        fun create(context: Context, root: File, url: String): BrowserSkillScope? = runCatching {
            val canonicalRoot = root.canonicalFile
            val uri = Uri.parse(url)
            if (!uri.scheme.equals("file", true)) return@runCatching null
            val entry = uri.path?.let { File(it).canonicalFile } ?: return@runCatching null
            if (!canonicalRoot.isDirectory || !entry.isFile) return@runCatching null
            val inSkill = inside(canonicalRoot, entry)
            val wrapperRoot = File(context.cacheDir, "skill-webview").canonicalFile
            val isWrapper = inside(wrapperRoot, entry) && entry.parentFile == wrapperRoot
            if (!inSkill && !isWrapper) return@runCatching null
            BrowserSkillScope(canonicalRoot, entry, wrapper = !inSkill,
                query = uri.encodedQuery, fragment = uri.encodedFragment)
        }.getOrNull()

        fun deniedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
            emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
