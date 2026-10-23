package me.rerere.rikkahub.data.kimi

import android.content.Context
import android.os.Build
import android.provider.Settings
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import java.util.UUID

/** Preserve proxy/TLS settings while excluding interceptors that can log OAuth credentials. */
internal fun kimiHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
}.build()

/** Lazily called by KimiApi from an IO dispatcher, never during Compose or Koin construction. */
internal fun kimiAndroidIdentity(context: Context): Map<String, String> {
    fun ascii(value: String) = value.filter { it.code in 32..126 }.trim().ifEmpty { "unknown" }
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    val id = if (androidId.isNullOrBlank()) UUID.randomUUID() else
        UUID.nameUUIDFromBytes("${context.packageName}:kimi:$androidId".encodeToByteArray())
    return mapOf(
        "User-Agent" to "Rikka-Root/${ascii(BuildConfig.VERSION_NAME)} (Android)",
        "X-Msh-Platform" to "rikka_root_android",
        "X-Msh-Version" to ascii(BuildConfig.VERSION_NAME),
        "X-Msh-Device-Name" to ascii("${Build.MANUFACTURER} ${Build.MODEL}"),
        "X-Msh-Device-Model" to ascii(Build.MODEL),
        "X-Msh-Os-Version" to ascii("Android ${Build.VERSION.RELEASE}"),
        "X-Msh-Device-Id" to id.toString(),
    )
}
