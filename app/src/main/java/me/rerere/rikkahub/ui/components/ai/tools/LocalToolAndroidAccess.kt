package me.rerere.rikkahub.ui.components.ai.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.ai.tools.local.localToolRuntimePermissions
import me.rerere.rikkahub.service.scheduling.ExactAlarmAccess
import me.rerere.rikkahub.utils.hasUsageStatsPermission

/** Read-only Android access status; tool approval remains a separate setting. */
data class LocalToolAndroidAccess(val label: String, val granted: Boolean, val required: Boolean)

fun androidAccessForTool(context: Context, option: LocalToolOption): List<LocalToolAndroidAccess> {
    fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val runtime = localToolAndroidRuntimeAccess(option, Build.VERSION.SDK_INT, ::granted)
    val special = when (option) {
        LocalToolOption.Brightness -> listOf(LocalToolAndroidAccess("Изменение системных настроек", Settings.System.canWrite(context), true))
        LocalToolOption.Volume -> listOf(LocalToolAndroidAccess("Доступ к «Не беспокоить»", context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true, false))
        LocalToolOption.ScreenTime -> listOf(LocalToolAndroidAccess("Доступ к статистике использования", context.hasUsageStatsPermission(), true))
        LocalToolOption.CronJobs, LocalToolOption.Workflows -> listOf(LocalToolAndroidAccess("Точные будильники и напоминания", ExactAlarmAccess.canSchedule(context), false))
        LocalToolOption.Termux -> listOf(LocalToolAndroidAccess("Termux установлен", TermuxIntegration.state(context) != TermuxIntegration.State.NOT_INSTALLED, true))
        else -> emptyList()
    }
    // Bluetooth remains optional and is requested solely by the existing Bluetooth button.
    val bluetooth = if (option == LocalToolOption.Workflows && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(LocalToolAndroidAccess("Bluetooth-триггеры", granted(Manifest.permission.BLUETOOTH_CONNECT), false)) else emptyList()
    val hardware = when (option) {
        LocalToolOption.Torch -> listOf(LocalToolAndroidAccess("Камера со вспышкой", context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH), true))
        LocalToolOption.Nfc -> listOf(LocalToolAndroidAccess("Модуль NFC", context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC), true))
        else -> emptyList()
    }
    return runtime + special + bluetooth + hardware
}

/** The general grant button follows the controller; optional Bluetooth has its own explicit button. */
fun canRequestAndroidAccess(context: Context, option: LocalToolOption): Boolean =
    localToolSupported(context, option) && androidAccessRequestNeeded(option, androidAccessForTool(context, option))

/** Uses the ordered status rows returned above, without inferring capabilities from translated labels. */
internal fun androidAccessRequestNeeded(option: LocalToolOption, access: List<LocalToolAndroidAccess>): Boolean = when (option) {
    // No runtime rows precede the exact-alarm status. Bluetooth remains a dedicated request.
    LocalToolOption.Workflows -> access.firstOrNull()?.granted == false
    LocalToolOption.Volume, LocalToolOption.CronJobs -> access.any { !it.granted }
    // The final row reports installation/hardware, which Android cannot grant as a permission.
    LocalToolOption.Termux, LocalToolOption.Torch -> access.lastOrNull()?.granted == true && access.dropLast(1).any { it.required && !it.granted }
    LocalToolOption.Nfc -> false
    else -> access.any { it.required && !it.granted }
}

internal fun localToolAndroidRuntimeAccess(
    option: LocalToolOption,
    sdkInt: Int,
    granted: (String) -> Boolean,
): List<LocalToolAndroidAccess> {
    val permissions = if (option == LocalToolOption.Calendar)
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        else localToolRuntimePermissions(option, sdkInt)
    val supportsApproximateLocation = option == LocalToolOption.Location || option == LocalToolOption.WifiInfo
    return permissions.map { permission ->
        val approximateLocation = supportsApproximateLocation && permission == Manifest.permission.ACCESS_COARSE_LOCATION
        val allowed = if (approximateLocation)
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
            else granted(permission)
        val required = !(supportsApproximateLocation && permission == Manifest.permission.ACCESS_FINE_LOCATION)
        LocalToolAndroidAccess(permissionLabel(permission, approximateLocation), allowed, required)
    }
}

private fun permissionLabel(permission: String, approximateLocation: Boolean): String = when (permission) {
    Manifest.permission.READ_CALENDAR -> "Чтение календаря"
    Manifest.permission.WRITE_CALENDAR -> "Изменение календаря"
    Manifest.permission.ACCESS_COARSE_LOCATION -> if (approximateLocation) "Местоположение (достаточно примерного)" else "Примерное местоположение"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Точное местоположение"
    Manifest.permission.READ_CONTACTS -> "Чтение контактов"
    Manifest.permission.READ_CALL_LOG -> "Журнал звонков"
    Manifest.permission.READ_SMS -> "Чтение SMS"
    Manifest.permission.SEND_SMS -> "Отправка SMS"
    Manifest.permission.CAMERA -> "Камера"
    Manifest.permission.RECORD_AUDIO -> "Микрофон"
    Manifest.permission.READ_PHONE_STATE -> "Состояние телефона"
    Manifest.permission.ACTIVITY_RECOGNITION -> "Распознавание физической активности"
    Manifest.permission.POST_NOTIFICATIONS -> "Показ уведомлений"
    Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Запись в общее хранилище"
    "com.termux.permission.RUN_COMMAND" -> "Выполнение команд Termux (RUN_COMMAND)"
    "android.permission.ACCESS_LOCAL_NETWORK" -> "Доступ к локальной сети"
    else -> permission.substringAfterLast('.')
}
