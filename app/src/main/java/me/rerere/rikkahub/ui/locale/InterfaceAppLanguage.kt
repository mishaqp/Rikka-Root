package me.rerere.rikkahub.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

private const val LANGUAGE_PREFERENCES = "interface_app_language"
private const val LANGUAGE_TAG = "language_tag"

/** The settings field is authoritative; this small cache is only read before legacy UI starts. */
private fun cachedLanguageTag(context: Context): String =
    context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(LANGUAGE_TAG, "").orEmpty()

/** Keeps LocalContext as the Activity: only its base resource configuration changes. */
fun attachInterfaceLanguageContext(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val languageTag = cachedLanguageTag(base)
    if (languageTag.isBlank()) return base
    val locales = LocaleList.forLanguageTags(languageTag)
    if (locales.isEmpty) return base
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocales(locales)
    return base.createConfigurationContext(configuration)
}

/** Empty means the system locale; null settings inherit this existing platform choice. */
fun currentInterfaceAppLanguage(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
    } else {
        cachedLanguageTag(context)
    }

/**
 * null preserves old backups and choices made in Android's app-language settings.
 * Explicit empty tags clear the override. Android 13 owns recreation; older Android
 * reads the synchronously committed mirror in attachBaseContext after recreation.
 */
fun syncInterfaceAppLanguage(activity: Activity, languageTag: String?): Boolean {
    if (languageTag == null) return false
    val desired = LocaleList.forLanguageTags(languageTag.trim())
    val desiredTag = desired.toLanguageTags()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = activity.getSystemService(LocaleManager::class.java) ?: return false
        if (manager.applicationLocales == desired) return false
        manager.applicationLocales = desired
        return true
    }
    if (cachedLanguageTag(activity) == desiredTag) return false
    // This must finish before recreate() can attach the new activity's base context.
    val saved = activity.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
        .edit().putString(LANGUAGE_TAG, desiredTag).commit()
    if (!saved) return false
    activity.recreate()
    return true
}
