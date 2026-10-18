// Adapted from ExTV/rikkahub-agent local/ExternalStorageTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.coroutines.coroutineContext

private fun storageParts(value: JsonObject) = listOf(UIMessagePart.Text(value.toString()))
private fun storageError(message: String) = storageParts(buildJsonObject { put("error", message) })

internal fun classifyAuthority(authority: String): String = when {
    authority == "com.android.externalstorage.documents" -> "volume_root"
    authority == "com.android.providers.downloads.documents" -> "downloads"
    authority.contains("docs.storage") || authority.contains("dropbox") || authority.contains("skydrive") || authority.contains("drive") -> "cloud"
    else -> "other"
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val INITIAL_FOLDER_ERROR = "initial_uri должен быть content:// URI папки или путём вроде /sdcard/Download."
private val removableVolumePath = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?$")
private fun encodeDocumentId(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
private fun externalDocumentUri(id: String) = "content://$EXTERNAL_STORAGE_AUTHORITY/document/${encodeDocumentId(id)}"

/** The picker opens in Download unless the model names another folder by URI or phone path. */
internal fun safInitialFolderUri(value: String?): String {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return externalDocumentUri("primary:Download")
    require(text.length <= 4096 && text.none(Char::isISOControl)) { INITIAL_FOLDER_ERROR }
    if (text.startsWith("/")) {
        val path = text.trimEnd('/')
        require(path.split('/').none { it == ".." || it == "." }) { INITIAL_FOLDER_ERROR }
        val primaryPrefix = listOf("/sdcard", "/mnt/sdcard", "/storage/self/primary", "/storage/emulated/0")
            .firstOrNull { path == it || path.startsWith("$it/") }
        if (primaryPrefix != null) return externalDocumentUri("primary:" + path.removePrefix(primaryPrefix).removePrefix("/"))
        val removable = requireNotNull(removableVolumePath.matchEntire(path)) { INITIAL_FOLDER_ERROR }
        return externalDocumentUri(removable.groupValues[1].uppercase() + ":" + removable.groupValues[2].removePrefix("/"))
    }
    val uri = try { java.net.URI(text) } catch (_: Exception) { throw IllegalArgumentException(INITIAL_FOLDER_ERROR) }
    require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.query == null && uri.fragment == null) { INITIAL_FOLDER_ERROR }
    val parts = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
    return when {
        parts.size == 2 && parts[0] == "document" -> text
        parts.size == 4 && parts[0] == "tree" && parts[2] == "document" -> text
        parts.size == 2 && parts[0] == "tree" -> "content://${uri.rawAuthority}/document/${parts[1]}"
        parts.size == 2 && parts[0] == "root" && uri.authority == EXTERNAL_STORAGE_AUTHORITY ->
            externalDocumentUri(java.net.URLDecoder.decode(parts[1], "UTF-8") + ":")
        else -> throw IllegalArgumentException(INITIAL_FOLDER_ERROR)
    }
}

/** A whole-volume grant reads fine, but Android commonly refuses to write through the storage root. */
internal fun safGrantWarning(treeUri: String): String? {
    val uri = runCatching { java.net.URI(treeUri) }.getOrNull() ?: return null
    if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val parts = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
    if (parts.size != 2 || parts[0] != "tree") return null
    val id = runCatching { java.net.URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: return null
    return if (id.endsWith(":")) "Выбран корень памяти. Android обычно не разрешает запись в корень — " +
        "для записи выдайте доступ заново и выберите саму папку, например Download." else null
}

fun listStorageVolumesTool(context: Context): Tool = Tool(
    name = "list_storage_volumes",
    description = "Показать до 64 физических накопителей и сохранённый доступ к их папкам. Для облачных папок используйте list_granted_directories; этот вызов не выдаёт новый доступ.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    needsApproval = { true },
    execute = {
        withContext(Dispatchers.IO) {
            try {
                val storage = context.getSystemService(StorageManager::class.java)
                    ?: return@withContext storageError("Android не предоставляет сведения о накопителях.")
                val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
                val active = coroutineContext
                val allVolumes = storage.storageVolumes
                val volumes = buildJsonArray {
                    for (volume in allVolumes.take(64)) {
                        active.ensureActive()
                        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null
                        val volumeId = if (volume.isPrimary) "primary" else volume.uuid
                        val grant = grants.firstOrNull { permission ->
                            permission.uri.authority == "com.android.externalstorage.documents" &&
                                runCatching { DocumentsContract.getTreeDocumentId(permission.uri).substringBefore(':') == volumeId }.getOrDefault(false)
                        }
                        addJsonObject {
                            put("id", volume.uuid ?: if (volume.isPrimary) "primary" else volume.toString())
                            put("label", volume.getDescription(context).take(4096))
                            put("type", when { volume.isPrimary -> "internal"; volume.isRemovable -> "sd"; else -> "external" })
                            put("primary", volume.isPrimary)
                            put("removable", volume.isRemovable)
                            put("mounted", volume.state == Environment.MEDIA_MOUNTED)
                            put("free_bytes", directory?.freeSpace ?: 0L)
                            put("total_bytes", directory?.totalSpace ?: 0L)
                            put("granted", grant != null)
                            grant?.let { put("content_uri", it.uri.toString()) }
                        }
                    }
                }
                storageParts(buildJsonObject {
                    put("volumes", volumes)
                    put("count", volumes.size)
                    put("total", allVolumes.size)
                    put("truncated", allVolumes.size > volumes.size)
                    if (allVolumes.size > volumes.size) put("message", "Показаны первые 64 накопителя из ${allVolumes.size}. Остальные не включены; постраничного чтения у инструмента нет.")
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageError("Не удалось получить сведения о накопителях Android.") }
        }
    },
)

/** Android's persisted permission list is the only grant store, including revocations. */
fun listGrantedDirectoriesTool(context: Context): Tool = Tool(
    name = "list_granted_directories",
    description = "Показать до 256 папок с постоянным доступом, выданным системным выбором папки, включая облачные. Отозванный в Android доступ исчезает из списка; для нового доступа нужен grant_directory_access.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    needsApproval = { true },
    execute = {
        withContext(Dispatchers.IO) {
            try {
                val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
                val active = coroutineContext
                val directories = buildJsonArray {
                    for (grant in grants.take(256)) {
                        active.ensureActive()
                        val uri = grant.uri
                        val authority = uri.authority.orEmpty()
                        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: uri.toString()
                        addJsonObject {
                            put("content_uri", uri.toString())
                            put("display_name", name.take(4096))
                            put("authority", authority)
                            put("kind", classifyAuthority(authority))
                        }
                    }
                }
                storageParts(buildJsonObject {
                    put("directories", directories)
                    put("count", directories.size)
                    put("total", grants.size)
                    put("truncated", grants.size > directories.size)
                    if (grants.size > directories.size) put("message", "Показаны первые 256 папок с доступом на чтение из ${grants.size}. Остальные не включены; постраничного чтения у инструмента нет.")
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageError("Не удалось прочитать сохранённые разрешения Android.") }
        }
    },
)

/** Releases only a grant Android actually holds for this app; the picker can grant it again later. */
fun revokeDirectoryAccessTool(context: Context): Tool = Tool(
    name = "revoke_directory_access",
    description = "Отозвать постоянный доступ к папке, выданный через grant_directory_access. content_uri берите из list_granted_directories.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("content_uri", buildJsonObject { put("type", "string"); put("description", "Точный content:// URI папки из list_granted_directories, доступ к которой нужно отозвать.") })
    }, required = listOf("content_uri")) },
    needsApproval = { true },
    execute = { input ->
        withContext(Dispatchers.IO) {
            try {
                val requested = input.jsonObject["content_uri"]?.jsonPrimitive?.contentOrNull?.trim()
                if (requested.isNullOrEmpty()) return@withContext storageError("Укажите content_uri папки из list_granted_directories.")
                val grant = context.contentResolver.persistedUriPermissions.firstOrNull {
                    it.uri.toString() == requested && DocumentsContract.isTreeUri(it.uri)
                } ?: return@withContext storageError("Доступа к такой папке нет. Посмотрите список через list_granted_directories.")
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                context.contentResolver.releasePersistableUriPermission(grant.uri, flags)
                storageParts(buildJsonObject { put("revoked", true); put("content_uri", requested) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageError("Android не отозвал доступ к папке. Повторите попытку.") }
        }
    },
)

fun grantDirectoryAccessTool(context: Context): Tool = Tool(
    name = "grant_directory_access",
    description = "Открыть системный выбор папки для постоянного доступа к SD, USB, загрузкам или облачному хранилищу. Требуется открытое приложение. Выбор открывается в Download; для записи выбирайте саму папку, а не корень памяти. initial_uri и request_label необязательны.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("initial_uri", buildJsonObject { put("type", "string"); put("description", "Необязательная начальная папка: content:// URI или путь вроде /sdcard/Documents. После обрезки пробелов — до 4096 символов без управляющих знаков. По умолчанию Download.") })
        put("request_label", buildJsonObject { put("type", "string"); put("description", "Необязательная подпись запроса доступа; используются первые 256 символов.") })
    }) },
    needsApproval = { true },
    execute = { input ->
        try {
            val obj = input.jsonObject
            val initial = try { safInitialFolderUri(obj["initial_uri"]?.jsonPrimitive?.contentOrNull) }
            catch (invalid: IllegalArgumentException) { return@Tool storageError(invalid.message ?: INITIAL_FOLDER_ERROR) }
            val label = obj["request_label"]?.jsonPrimitive?.contentOrNull?.take(256)
            when (val result = awaitSafToolUi(context, SafUiRequest(initial, label))) {
                is SafUiResult.Granted -> withContext(Dispatchers.IO) {
                    val uri = Uri.parse(result.contentUri)
                    val displayName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                        ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: result.contentUri
                    storageParts(buildJsonObject {
                        put("granted", true)
                        put("content_uri", result.contentUri)
                        put("display_name", displayName.take(4096))
                        put("authority", uri.authority.orEmpty())
                        safGrantWarning(result.contentUri)?.let { put("warning", it) }
                    })
                }
                SafUiResult.Cancelled -> storageParts(buildJsonObject { put("granted", false) })
                is SafUiResult.Error -> storageError(result.message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { storageError("Не удалось открыть системный выбор папки. Откройте Rikka-Root и повторите запрос.") }
    },
)
