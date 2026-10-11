package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Resolves against the real merged app manifest; no activities are inserted by a shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class RouteActivityShareManifestTest {
    @Test
    fun `single image MIME shares resolve to RouteActivity`() {
        assertShareResolves(Intent.ACTION_SEND, "image/png")
    }

    @Test
    fun `single document MIME shares resolve to RouteActivity`() {
        listOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown").forEach { mime -> assertShareResolves(Intent.ACTION_SEND, mime) }
    }

    @Test
    fun `single audio and video MIME shares resolve to RouteActivity`() {
        listOf("audio/wav", "video/mp4").forEach { mime -> assertShareResolves(Intent.ACTION_SEND, mime) }
    }

    @Test
    fun `legacy plain text MIME shares still resolve to RouteActivity`() {
        assertShareResolves(Intent.ACTION_SEND, "text/plain")
    }

    @Test
    fun `multiple media document and mixed MIME shares resolve to RouteActivity`() {
        listOf("image/png", "application/pdf", "audio/wav", "video/mp4", "text/plain", "*/*")
            .forEach { mime -> assertShareResolves(Intent.ACTION_SEND_MULTIPLE, mime) }
    }

    private fun assertShareResolves(action: String, mime: String) {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(action).setType(mime).setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("Merged manifest must offer RouteActivity for $action $mime",
            matches.any { it.activityInfo.name == RouteActivity::class.java.name })
    }
}
