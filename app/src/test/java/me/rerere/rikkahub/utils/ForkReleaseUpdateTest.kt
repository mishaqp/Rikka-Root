package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForkReleaseUpdateTest {
    private val apk = """{"name":"Rikka-Root-arm64.apk","size":1048576,"browser_download_url":"https://github.com/mishaqp/Rikka-Root/releases/download/v2.5.6-root.2/Rikka-Root-arm64.apk"}"""

    private fun release(
        tag: String = "v2.5.6-root.2",
        date: String = "\"2026-10-07T00:00:00Z\"",
        body: String = "\"Changes\"",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = apk,
    ) = """{"tag_name":"$tag","published_at":$date,"body":$body,"draft":$draft,"prerelease":$prerelease,"assets":[$assets]}"""

    @Test fun `own release maps version notes and installable APK`() {
        val update = decodeForkReleaseUpdate(release())!!
        assertEquals("2.5.6-root.2", update.version)
        assertEquals("Changes", update.changelog)
        assertEquals("2026-10-07T00:00:00Z", update.publishedAt)
        assertEquals(listOf("Rikka-Root-arm64.apk"), update.downloads.map { it.name })
        assertEquals("1.0 MB", update.downloads.single().size)
    }

    @Test fun `nullable release notes do not prevent an update`() {
        assertEquals("", decodeForkReleaseUpdate(release(body = "null"))!!.changelog)
    }

    @Test fun `CI version hash arm64 asset is accepted for normal releases but not nightly`() {
        val name = "Rikka-Root-2.5.6-root.2-a1234567-arm64-v8a.apk"
        val asset = apk.replace("Rikka-Root-arm64.apk", name)
        assertEquals(name, decodeForkReleaseUpdate(release(assets = asset))!!.downloads.single().name)
        assertNull(decodeForkReleaseUpdate(release(tag = "nightly", prerelease = true,
            assets = asset.replace("download/v2.5.6-root.2", "download/nightly"))))
    }

    @Test fun `draft prerelease and unrelated version tags are ignored`() {
        assertNull(decodeForkReleaseUpdate(release(draft = true)))
        assertNull(decodeForkReleaseUpdate(release(prerelease = true)))
        assertNull(decodeForkReleaseUpdate(release(tag = "nightly")))
        assertNull(decodeForkReleaseUpdate(release(tag = "v2.5.6")))
        assertNull(decodeForkReleaseUpdate(release(tag = "v2.5.6-agent.5")))
    }

    @Test fun `missing or invalid publication dates are ignored`() {
        assertNull(decodeForkReleaseUpdate(release(date = "null")))
        assertNull(decodeForkReleaseUpdate(release(date = "\"yesterday\"")))
    }

    @Test fun `release without installable assets does not announce an update`() {
        assertNull(decodeForkReleaseUpdate(release(assets = "")))
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("arm64.apk", "arm64.zip"))))
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("arm64.apk", "debug.apk"))))
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("arm64.apk", "unsigned.apk"))))
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("1048576", "0"))))
    }

    @Test fun `foreign repository and mismatched release assets are ignored`() {
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("mishaqp/Rikka-Root", "rikkahub/rikkahub"))))
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("https://github.com", "https://github.com.evil.test"))))
        assertNull(decodeForkReleaseUpdate(release(assets = apk.replace("download/v2.5.6-root.2", "download/v2.5.6-root.1"))))
    }

    @Test fun `non APK assets are omitted from otherwise valid releases`() {
        val update = decodeForkReleaseUpdate(release(assets = apk + "," + apk.replace("arm64.apk", "sources.zip")))!!
        assertEquals(1, update.downloads.size)
    }
}
