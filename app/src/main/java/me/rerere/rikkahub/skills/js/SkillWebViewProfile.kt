package me.rerere.rikkahub.skills.js

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.security.MessageDigest

/**
 * A skill must never share cookies or DOM storage with the signed-in browser or another
 * skill. Bind its persistent profile before touching WebView settings or navigating.
 * Older WebViews fail closed rather than falling back to the browser's default profile.
 */
object SkillWebViewProfile {
    fun configure(webView: WebView, skillRoot: File): Boolean = configureIsolatedSkillProfile(
        skillRoot = skillRoot,
        isSupported = { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) },
        setProfile = { name -> WebViewCompat.setProfile(webView, name) },
        disableThirdPartyCookies = {
            WebViewCompat.getProfile(webView).cookieManager.setAcceptThirdPartyCookies(webView, false)
        },
    )

    /** Canonical physical roots distinguish identically named skills in different stores. */
    fun profileName(skillRoot: File): String = "rikka_skill_" + MessageDigest.getInstance("SHA-256")
        .digest(skillRoot.canonicalPath.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** The production boundary is injectable so unsupported/configuration failures are tested. */
internal fun configureIsolatedSkillProfile(
    skillRoot: File,
    isSupported: () -> Boolean,
    setProfile: (String) -> Unit,
    disableThirdPartyCookies: () -> Unit,
): Boolean = runCatching {
    if (!isSupported()) return@runCatching false
    setProfile(SkillWebViewProfile.profileName(skillRoot))
    disableThirdPartyCookies()
    true
}.getOrDefault(false)
