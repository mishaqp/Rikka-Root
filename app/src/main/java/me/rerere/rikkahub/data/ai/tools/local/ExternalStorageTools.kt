// Adapted from ExTV/rikkahub-agent local/ExternalStorageTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
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

fun listStorageVolumesTool(context: Context): Tool = Tool(
    name = "list_storage_volumes",
    description = "Показать физические накопители и сведения о сохранённом доступе к их папкам. Для облачных папок используйте list_granted_directories.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    needsApproval = { true },
    execute = {
        withContext(Dispatchers.IO) {
            try {
                val storage = context.getSystemService(StorageManager::class.java)
                    ?: return@withContext storageError("Android не предоставляет сведения о накопителях.")
                val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
                val active = coroutineContext
                val volumes = buildJsonArray {
                    for (volume in storage.storageVolumes.take(64)) {
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
                storageParts(buildJsonObject { put("volumes", volumes) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageError("Не удалось получить сведения о накопителях Android.") }
        }
    },
)

/** Android's persisted permission list is the only grant store, including revocations. */
fun listGrantedDirectoriesTool(context: Context): Tool = Tool(
    name = "list_granted_directories",
    description = "Показать папки с постоянным доступом, выданным через системный выбор папки. Отозванный в Android доступ автоматически исчезает из списка.",
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
                storageParts(buildJsonObject { put("directories", directories) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageError("Не удалось прочитать сохранённые разрешения Android.") }
        }
    },
)

fun grantDirectoryAccessTool(context: Context): Tool = Tool(
    name = "grant_directory_access",
    description = "Открыть системный выбор папки для постоянного доступа к SD, USB, загрузкам или облачному хранилищу. Требуется открытое приложение. initial_uri и request_label необязательны.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("initial_uri", buildJsonObject { put("type", "string"); put("description", "Начальный content:// URI папки для системного выбора") })
        put("request_label", buildJsonObject { put("type", "string"); put("description", "Подпись запроса доступа") })
    }) },
    needsApproval = { true },
    execute = { input ->
        try {
            val obj = input.jsonObject
            val initial = obj["initial_uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (initial != null && (initial.length > 4096 || Uri.parse(initial).scheme != "content" || !DocumentsContract.isTreeUri(Uri.parse(initial)))) {
                return@Tool storageError("initial_uri должен быть content:// URI папки Android.")
            }
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
                    })
                }
                SafUiResult.Cancelled -> storageParts(buildJsonObject { put("granted", false) })
                is SafUiResult.Error -> storageError(result.message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { storageError("Не удалось открыть системный выбор папки. Откройте Rikka-Root и повторите запрос.") }
    },
)
