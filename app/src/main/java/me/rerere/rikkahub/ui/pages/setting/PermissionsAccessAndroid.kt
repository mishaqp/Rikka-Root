package me.rerere.rikkahub.ui.pages.setting

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.SafUiRequest
import me.rerere.rikkahub.data.ai.tools.local.SafUiResult
import me.rerere.rikkahub.data.ai.tools.local.awaitSafToolUi
import me.rerere.rikkahub.data.ai.tools.local.revokeDirectoryAccessTool
import me.rerere.rikkahub.data.ai.tools.local.safInitialFolderUri
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.service.scheduling.ExactAlarmAccess
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class PermissionAccessStatus(val text: String) {
    Granted("Выдано"), Denied("Не выдано"), Unavailable("Недоступно на этой версии Android")
}

internal data class PermissionAccessRow(
    val entry: PermissionAccessEntry,
    val status: PermissionAccessStatus,
    val detail: String? = null,
)

internal data class SafAccessFolder(val uri: String, val path: String, val writable: Boolean)

internal data class PermissionsAccessSnapshot(
    val rows: List<PermissionAccessRow> = emptyList(),
    val folders: List<SafAccessFolder> = emptyList(),
    val runtimeRequest: List<String> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** Binder and document-provider reads stay off the UI thread; no root operation is made here. */
internal suspend fun loadPermissionsAccess(context: Context, rootStatus: RootStatus): PermissionsAccessSnapshot = withContext(Dispatchers.IO) {
    val sdk = Build.VERSION.SDK_INT
    val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions.orEmpty().toSet()
    val supported = declared.filterTo(mutableSetOf()) { name ->
        try { context.packageManager.getPermissionInfo(name, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }
    val granted = supported.filterTo(mutableSetOf()) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val rows = PermissionsAccessCatalog.entries.map { entry ->
        val unavailable = !entry.availableOn(sdk) || when (entry.kind) {
            // SCHEDULE_EXACT_ALARM exists on Android 12; USE_EXACT_ALARM starts
            // on Android 13. Either existing declaration makes the special access available.
            PermissionAccessKind.Special -> entry.permissions.isNotEmpty() && entry.permissions.none { it in declared && it in supported }
            PermissionAccessKind.Root -> false
            else -> entry.permissions.any { it !in declared || it !in supported }
        }
        if (unavailable) {
            PermissionAccessRow(entry, PermissionAccessStatus.Unavailable,
                if (entry.id == "termux") "Установите Termux из доверенного источника, затем откройте этот экран снова." else null)
        } else {
            val allowed = when (entry.id) {
                "root" -> rootStatus == RootStatus.READY
                "all_files" -> Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
                "exact_alarms" -> ExactAlarmAccess.canSchedule(context)
                "write_settings" -> Settings.System.canWrite(context)
                "usage" -> context.getSystemService(AppOpsManager::class.java)?.let { ops ->
                    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), context.packageName) else
                        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
                    mode == AppOpsManager.MODE_ALLOWED
                } == true
                "dnd" -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
                "battery" -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
                "promoted_notifications" -> Build.VERSION.SDK_INT >= 36 && context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
                "notifications", "legacy_notifications" -> entry.permissions.all { it in granted } && NotificationManagerCompat.from(context).areNotificationsEnabled()
                else -> entry.permissions.all { it in granted }
            }
            val detail = if (entry.id == "root") when (rootStatus) {
                RootStatus.UNCHECKED -> "Ещё не проверено. Проверка может открыть запрос root-менеджера."
                RootStatus.CHECKING -> "Идёт проверка root-доступа…"
                RootStatus.UNAVAILABLE, RootStatus.NOT_ROOT -> "На устройстве не найден подтверждённый root-доступ."
                RootStatus.DENIED_OR_FAILED -> "Разрешите Rikka-Root в менеджере root и повторите проверку."
                RootStatus.TIMED_OUT -> "Root-менеджер не ответил. Откройте его и повторите проверку."
                RootStatus.READY -> "Подтверждено при последней проверке. Root-менеджер может отозвать доступ."
            } else null
            PermissionAccessRow(entry, if (allowed) PermissionAccessStatus.Granted else PermissionAccessStatus.Denied, detail)
        }
    }
    val folders = context.contentResolver.persistedUriPermissions.asSequence()
        .filter { (it.isReadPermission || it.isWritePermission) && DocumentsContract.isTreeUri(it.uri) }
        .map { permission ->
            val id = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
            val name = runCatching { DocumentFile.fromTreeUri(context, permission.uri)?.name }.getOrNull()
                ?: permission.uri.authority.orEmpty()
            val path = if (permission.uri.authority == "com.android.externalstorage.documents")
                safFolderDisplayPath(id, name) else name.ifBlank { "Папка облачного хранилища" }
            SafAccessFolder(permission.uri.toString(), path, permission.isWritePermission)
        }.sortedBy { it.path }.toList()
    PermissionsAccessSnapshot(rows, folders,
        PermissionsAccessCatalog.runtimeRequest(sdk, declared, supported, granted), loading = false)
}

internal fun appPermissionSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

internal fun permissionSettingsIntents(entry: PermissionAccessEntry, packageName: String): List<Intent> {
    val packageUri = Uri.parse("package:$packageName")
    val primary = when (entry.id) {
        "all_files" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        "exact_alarms" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
        "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri)
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri)
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "promoted_notifications" -> Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        "notifications", "legacy_notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        else -> appPermissionSettingsIntent(packageName)
    }
    val fallback = when (entry.id) {
        "all_files" -> Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        else -> appPermissionSettingsIntent(packageName)
    }
    return listOf(primary, fallback, appPermissionSettingsIntent(packageName))
}

/** Reuses the chat's picker and persistent grant validation, rather than keeping another grant store. */
internal suspend fun addPermissionAccessFolder(context: Context): String? = when (val result = awaitSafToolUi(context,
    SafUiRequest(safInitialFolderUri(null), "Создайте или выберите подпапку, например Download/Rikka."))) {
    is SafUiResult.Granted, SafUiResult.Cancelled -> null
    is SafUiResult.Error -> result.message
}

/** The same implementation as revoke_directory_access, started by an explicit screen button. */
internal suspend fun revokePermissionAccessFolder(context: Context, uri: String): String? {
    val parts = revokeDirectoryAccessTool(context).execute(buildJsonObject { put("content_uri", uri) })
    val text = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return "Android не подтвердил отзыв доступа. Обновите список папок."
    return Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
}
