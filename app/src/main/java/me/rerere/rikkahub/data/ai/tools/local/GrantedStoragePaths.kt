// Absolute-path routing for the existing scoped file/SAF tools (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class SafStoragePathPlan(
    val treeUri: String,
    val documentId: String?,
    val relativeNames: List<String>,
)

internal data class StorageAddress(val volumeId: String, val root: File, val file: File)
internal fun storageAddress(value: String, volumeRoots: Map<String, File>, primaryAliases: List<File>): StorageAddress? {
    if (value.isBlank() || value.length > 8192 || value.any { it == '\\' || it.isISOControl() } ||
        value.split('/').any { it == ".." } || !File(value).isAbsolute) return null
    val requested = File(value).absoluteFile.toPath().normalize()
    for ((id, suppliedRoot) in volumeRoots) {
        val root = suppliedRoot.canonicalFile
        if (root.toPath().parent == null) continue
        val aliases = if (id.equals("primary", true)) primaryAliases.filter {
            runCatching { it.canonicalFile == root }.getOrDefault(false)
        } else emptyList()
        val candidates = (listOf(suppliedRoot.absoluteFile, root) + aliases).distinct()
        val prefix = candidates.map { it.toPath().normalize() }.firstOrNull(requested::startsWith) ?: continue
        val relative = prefix.relativize(requested)
        return StorageAddress(id.lowercase(Locale.ROOT), root, root.toPath().resolve(relative).normalize().toFile())
    }
    return null
}

/** Cheap pathname gate only; known-volume and held-tree checks still decide access. */
internal fun sharedStoragePathCandidate(path: String, primaryRoot: File?, primaryAliases: List<File>): Boolean {
    if (!File(path).isAbsolute) return false
    val requested = File(path).absoluteFile.toPath().normalize()
    val prefixes = listOf(File("/storage"), File("/mnt/media_rw")) + primaryAliases +
        listOfNotNull(primaryRoot?.absoluteFile, primaryRoot?.let { runCatching { it.canonicalFile }.getOrNull() })
    return prefixes.any { root -> root.toPath().parent != null && requested.startsWith(root.toPath().normalize()) }
}

internal fun knownSharedStorageAddress(path: String, volumeRoots: Map<String, File>, primaryAliases: List<File>): Boolean =
    runCatching { storageAddress(path, volumeRoots, primaryAliases) != null }.getOrDefault(false)

/** Only known Android storage providers give a physical meaning to a held tree ID. */
internal fun grantedStoragePathPlan(
    path: String,
    treeUri: String,
    volumeRoots: Map<String, File>,
    primaryAliases: List<File> = emptyList(),
): SafStoragePathPlan? {
    return try {
        val requested = storageAddress(path, volumeRoots, primaryAliases) ?: return null
        val uri = URI(treeUri)
        if (uri.scheme != "content" || uri.query != null || uri.fragment != null) return null
        val parts = uri.rawPath?.split('/')?.filter(String::isNotEmpty)?.map {
            URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
        } ?: return null
        if (parts.size != 2 || parts[0] != "tree") return null
        val treeId = parts[1]
        if (treeId.length > 8192 || treeId.any(Char::isISOControl)) return null
        val rootAddress: StorageAddress
        val nativePathIds: Boolean
        when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val separator = treeId.indexOf(':')
                if (separator <= 0) return null
                val volume = treeId.substring(0, separator).lowercase(Locale.ROOT)
                if (volume != requested.volumeId) return null
                val relative = treeId.substring(separator + 1)
                if (relative.isNotEmpty() && relative.split('/').any { it.isEmpty() || it in listOf(".", "..") || it.length > 255 } || '\\' in relative) return null
                rootAddress = requested.copy(file = File(requested.root, relative))
                nativePathIds = true
            }
            "com.android.providers.downloads.documents" -> {
                rootAddress = when {
                    treeId == "downloads" && requested.volumeId == "primary" -> requested.copy(file = File(requested.root, "Download"))
                    treeId.startsWith("raw:") -> storageAddress(treeId.removePrefix("raw:"), volumeRoots, primaryAliases) ?: return null
                    else -> return null // Opaque folder IDs cannot be inferred from their display name.
                }
                if (rootAddress.volumeId != requested.volumeId || rootAddress.root != requested.root) return null
                nativePathIds = treeId.startsWith("raw:")
            }
            else -> return null // Cloud and custom providers have no known local path mapping.
        }
        val grantRoot = rootAddress.file.toPath().normalize()
        if (!requested.file.toPath().startsWith(grantRoot)) return null
        // NIO represents an empty relative path as one empty name; the granted root
        // has no child segments and must not be rejected as a blank child name.
        val relativePath = grantRoot.relativize(requested.file.toPath())
        val relative = if (relativePath.toString().isEmpty()) emptyList() else relativePath.map { it.toString() }
        if (relative.size > LocalFileAccess.MAX_DEPTH || relative.any { it.isBlank() || it.length > 255 }) return null
        // Existing media-path guard permits trusted volume aliases but rejects every descendant symlink.
        val withinVolume = requested.root.toPath().relativize(requested.file.toPath()).toString().replace(File.separatorChar, '/')
        val verified = if (withinVolume.isEmpty()) requested.root else externalStorageMediaScanPath(
            "${requested.volumeId}:$withinVolume", volumeRoots)
        if (verified.toPath() != requested.file.toPath() || !verified.toPath().startsWith(grantRoot)) return null
        val documentId = if (!nativePathIds && relative.isNotEmpty()) null else when {
            relative.isEmpty() -> treeId
            treeId.endsWith(':') -> treeId + relative.joinToString("/")
            else -> "$treeId/${relative.joinToString("/")}"
        }
        SafStoragePathPlan(treeUri, documentId, relative)
    } catch (_: Exception) { null }
}
