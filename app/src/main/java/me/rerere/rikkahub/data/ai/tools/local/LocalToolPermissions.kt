package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Dangerous permissions only; normal permissions are declared for their specific API. */
internal fun localToolRuntimePermissions(option: LocalToolOption, sdkInt: Int): List<String> = when (option) {
    LocalToolOption.Termux -> listOf("com.termux.permission.RUN_COMMAND")
    LocalToolOption.Download -> if (sdkInt <= 28) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()
    LocalToolOption.Location -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    LocalToolOption.Contacts -> listOf(Manifest.permission.READ_CONTACTS)
    LocalToolOption.CallLog -> listOf(Manifest.permission.READ_CALL_LOG)
    LocalToolOption.SmsInbox -> listOf(Manifest.permission.READ_SMS)
    LocalToolOption.SmsSend -> listOf(Manifest.permission.SEND_SMS)
    LocalToolOption.CameraPhoto -> listOf(Manifest.permission.CAMERA)
    LocalToolOption.MicRecorder, LocalToolOption.SpeechToText -> listOf(Manifest.permission.RECORD_AUDIO)
    LocalToolOption.Torch -> listOf(Manifest.permission.CAMERA)
    LocalToolOption.TelephonyInfo -> listOf(Manifest.permission.READ_PHONE_STATE)
    // Android 12+ requires requesting coarse and fine together, even for precise-only use.
    LocalToolOption.WifiInfo -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    LocalToolOption.Sensors -> if (sdkInt >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
    LocalToolOption.Notification -> if (sdkInt >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    else -> emptyList()
}

internal fun missingRuntimePermissions(option: LocalToolOption, sdkInt: Int, granted: (String) -> Boolean): List<String> {
    if (option == LocalToolOption.Location && (granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION))) return emptyList()
    return localToolRuntimePermissions(option, sdkInt).filterNot(granted)
}

internal fun missingLocalToolPermissions(context: Context, option: LocalToolOption): List<String> =
    missingRuntimePermissions(option, Build.VERSION.SDK_INT) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** No shell input from the model: only a validated app ID, own user and this feature's allowlist. */
internal fun localPermissionGrantCommand(packageName: String, option: LocalToolOption, sdkInt: Int, userId: Int): String? {
    if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) || userId < 0) return null
    if (option == LocalToolOption.Brightness) return "appops set --user $userId $packageName WRITE_SETTINGS allow"
    val permissions = localToolRuntimePermissions(option, sdkInt)
    if (permissions.isEmpty()) return null
    return permissions.joinToString(" && ") { "pm grant --user $userId $packageName $it" }
}
