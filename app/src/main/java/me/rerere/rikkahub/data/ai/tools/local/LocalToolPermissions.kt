package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Dangerous permissions only; normal permissions are declared for their specific API. */
internal fun localToolRuntimePermissions(option: LocalToolOption, sdkInt: Int): List<String> = when (option) {
    LocalToolOption.TelephonyInfo -> listOf(Manifest.permission.READ_PHONE_STATE)
    // Android 12+ requires requesting coarse and fine together, even for precise-only use.
    LocalToolOption.WifiInfo -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    LocalToolOption.Sensors -> if (sdkInt >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
    LocalToolOption.Notification -> if (sdkInt >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    else -> emptyList()
}

internal fun missingLocalToolPermissions(context: Context, option: LocalToolOption): List<String> =
    localToolRuntimePermissions(option, Build.VERSION.SDK_INT).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/** No shell input from the model: only a validated app ID, own user and this feature's allowlist. */
internal fun localPermissionGrantCommand(packageName: String, option: LocalToolOption, sdkInt: Int, userId: Int): String? {
    if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) || userId < 0) return null
    val permissions = localToolRuntimePermissions(option, sdkInt)
    if (permissions.isEmpty()) return null
    return permissions.joinToString(" && ") { "pm grant --user $userId $packageName $it" }
}
