package me.rerere.rikkahub.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import me.rerere.rikkahub.skills.js.JsSkillRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BrowserSkillScopeTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var entry: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.filesDir, "skills/browser-scope-${System.nanoTime()}").apply { mkdirs() }
        entry = File(root, "ui.html").apply { writeText("<html>own-skill-page</html>") }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `entry and sibling resources retain skill origin and real contents`() {
        val scope = requireNotNull(BrowserSkillScope.create(context, root, entry.toURI().toString()))
        assertEquals(JsSkillRunner.skillOriginHost(root.name), Uri.parse(scope.initialUrl).host)
        assertEquals(BrowserSkillScope.skillUrl(root, entry), scope.initialUrl)
        val assets = File(root, "assets").apply { mkdirs() }
        File(assets, "sound.txt").writeText("own-audio-resource")
        val loader = scope.assetLoader(context)
        val html = requireNotNull(loader.shouldInterceptRequest(Uri.parse(scope.initialUrl)))
        assertEquals("text/html", html.mimeType)
        assertEquals("<html>own-skill-page</html>", html.data.bufferedReader().use { it.readText() })
        val asset = requireNotNull(loader.shouldInterceptRequest(Uri.parse("https://${scope.originHost}/skill/assets/sound.txt")))
        assertEquals("own-audio-resource", asset.data.bufferedReader().use { it.readText() })
    }

    @Test fun `traversal and symlink cannot serve another skill`() {
        val sibling = File(root.parentFile, "other-skill-${System.nanoTime()}").apply { mkdirs() }
        try {
            val secret = File(sibling, "token.txt").apply { writeText("other-skill-private-value") }
            val scope = requireNotNull(BrowserSkillScope.create(context, root, entry.toURI().toString()))
            val loader = scope.assetLoader(context)
            for (path in listOf("../${sibling.name}/token.txt", "%2e%2e/${sibling.name}/token.txt")) {
                val denied = requireNotNull(loader.shouldInterceptRequest(Uri.parse("https://${scope.originHost}/skill/$path")))
                assertEquals(path, 403, denied.statusCode)
                assertEquals("", denied.data.bufferedReader().use { it.readText() })
            }
            Files.createSymbolicLink(File(root, "linked-token.txt").toPath(), secret.toPath())
            val denied = requireNotNull(loader.shouldInterceptRequest(Uri.parse("https://${scope.originHost}/skill/linked-token.txt")))
            assertEquals(403, denied.statusCode)
        } finally { sibling.deleteRecursively() }
    }

    @Test fun `app private file and another skill cannot become viewer entry`() {
        val privateFile = File(context.filesDir, "browser-private-${System.nanoTime()}.txt").apply { writeText("private-value") }
        try {
            assertNull(BrowserSkillScope.create(context, root, privateFile.toURI().toString()))
            assertNull(BrowserSkillScope.skillUrl(root, privateFile))
            assertNull(BrowserSkillScope.create(context, root, "content://example/private"))
        } finally { privateFile.delete() }
    }

    @Test fun `local viewer retains its own query and anchor after scoped URL conversion`() {
        val scope = requireNotNull(BrowserSkillScope.create(context, root,
            entry.toURI().toString() + "?mode=own%20value#section%202"))
        val uri = Uri.parse(scope.initialUrl)
        assertEquals("mode=own%20value", uri.encodedQuery)
        assertEquals("section%202", uri.encodedFragment)
        assertEquals(JsSkillRunner.skillOriginHost(root.name), uri.host)
    }

    @Test fun `source direct remote viewer remains available while private schemes stay blocked`() {
        val url = "https://example.com/direct-viewer?mode=source#entry"
        val remote = requireNotNull(BrowserSkillScope.resolveViewer(context, root, url))
        assertEquals(url, remote.initialUrl)
        assertNull(remote.scope)
        val dataUrl = "data:text/html,%3Cp%3Esource%20viewer%3C/p%3E"
        val data = requireNotNull(BrowserSkillScope.resolveViewer(context, root, dataUrl))
        assertEquals(dataUrl, data.initialUrl)
        assertNull(data.scope)
        val local = requireNotNull(BrowserSkillScope.resolveViewer(context, root, entry.toURI().toString()))
        assertNotNull(local.scope)
        assertEquals(JsSkillRunner.skillOriginHost(root.name), Uri.parse(local.initialUrl).host)
        for (forbidden in listOf("content://example/private", "javascript:alert(1)", "intent://private")) {
            assertNull(forbidden, BrowserSkillScope.resolveViewer(context, root, forbidden))
        }
    }

    @Test fun `only current cached iframe wrapper is exposed`() {
        val wrapperRoot = File(context.cacheDir, "skill-webview").apply { mkdirs() }
        val wrapper = File(wrapperRoot, "browser-wrapper-${System.nanoTime()}.html").apply { writeText("own-wrapper") }
        val other = File(wrapperRoot, "browser-other-${System.nanoTime()}.html").apply { writeText("other-wrapper") }
        try {
            val scope = requireNotNull(BrowserSkillScope.create(context, root, wrapper.toURI().toString()))
            val loader = scope.assetLoader(context)
            val own = requireNotNull(loader.shouldInterceptRequest(Uri.parse(scope.initialUrl)))
            assertEquals("own-wrapper", own.data.bufferedReader().use { it.readText() })
            val denied = requireNotNull(loader.shouldInterceptRequest(Uri.parse("https://${scope.originHost}/viewer/${other.name}")))
            assertEquals(403, denied.statusCode)
            assertTrue(scope.isForeignSkillOrigin(Uri.parse("https://${JsSkillRunner.skillOriginHost("another-skill")}/skill/ui.html")))
            assertFalse(scope.isForeignSkillOrigin(Uri.parse(scope.initialUrl)))
            assertFalse(scope.isForeignSkillOrigin(Uri.parse("https://example.com/embedded-viewer")))
        } finally { wrapper.delete(); other.delete() }
    }
}
