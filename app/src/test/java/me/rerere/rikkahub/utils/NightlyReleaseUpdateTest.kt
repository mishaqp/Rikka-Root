package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightlyReleaseUpdateTest {
    private val hash = "a1234567"
    private val name = "Rikka-Root-2.5.6-root.2-$hash-arm64-v8a.apk"
    private val apk = asset(name)

    private fun asset(fileName: String, updatedAt: String = "2026-10-11T10:00:00Z") =
        """{"name":"$fileName","size":1048576,"updated_at":"$updatedAt","browser_download_url":"https://github.com/mishaqp/Rikka-Root/releases/download/nightly/$fileName"}"""

    private fun release(assets: String = apk, tag: String = "nightly", draft: Boolean = false) =
        """{"tag_name":"$tag","published_at":"2026-09-01T00:00:00Z","prerelease":true,"draft":$draft,"assets":[$assets]}"""

    @Test fun `nightly prerelease extracts hash from signed arm64 filename`() {
        val update = decodeNightlyReleaseUpdate(release())!!
        assertEquals(hash, update.commitHash)
        assertEquals(hash, update.version)
        assertEquals(name, update.downloads.single().name)
        assertEquals("1.0 MB", update.downloads.single().size)
        assertEquals("2026-10-11T10:00:00Z", update.publishedAt)
        assertFalse(update.isNewer)
    }

    @Test fun `newest uploaded APK wins while previous nightly assets remain`() {
        val previous = asset(name.replace(hash, "b1234567"), "2026-10-10T10:00:00Z")
        assertEquals(hash, decodeNightlyReleaseUpdate(release(assets = "$previous,$apk"))!!.commitHash)
    }

    @Test fun `incomplete uploading asset does not replace an installable APK`() {
        val uploading = asset(name.replace(hash, "b1234567"), "2026-10-12T10:00:00Z")
            .dropLast(1) + ",\"state\":\"starter\"}"
        assertEquals(hash, decodeNightlyReleaseUpdate(release(assets = "$apk,$uploading"))!!.commitHash)
    }

    @Test fun `nightly ignores release tag target while APK identifies commit`() {
        val body = release().dropLast(1) + ",\"target_commitish\":\"${"f".repeat(40)}\"}"
        assertEquals(hash, decodeNightlyReleaseUpdate(body)!!.commitHash)
    }

    @Test fun `draft and other release tags are rejected`() {
        assertNull(decodeNightlyReleaseUpdate(release(draft = true)))
        assertNull(decodeNightlyReleaseUpdate(release(tag = "v2.5.6-root.2")))
    }

    @Test fun `nightly requires arm64 signed APK and a valid commit hash`() {
        listOf(
            name.replace("arm64-v8a", "x86_64"),
            name.replace(hash, "nightly"),
            name.replace(hash, "a123"),
            name.replace(".apk", ".zip"),
            name.replace("Rikka-Root", "Rikka-Root-debug"),
            name.replace("Rikka-Root", "Rikka-Root-unsigned"),
        ).forEach { invalid -> assertNull(invalid, decodeNightlyReleaseUpdate(release(asset(invalid)))) }
    }

    @Test fun `nightly rejects foreign download host repository or tag`() {
        listOf(
            apk.replace("https://github.com", "https://github.com.evil.test"),
            apk.replace("mishaqp/Rikka-Root", "rikkahub/rikkahub"),
            apk.replace("download/nightly", "download/v2.5.6-root.2"),
            apk.replace("1048576", "0"),
        ).forEach { invalid -> assertNull(decodeNightlyReleaseUpdate(release(invalid))) }
    }

    @Test fun `short and full commit hashes compare equally in either direction`() {
        val full = hash + "0".repeat(32)
        assertTrue(sameGitCommit(hash, full))
        assertTrue(sameGitCommit(full, hash.uppercase()))
        assertFalse(sameGitCommit(hash, "b1234567"))
        assertFalse(sameGitCommit("unknown", "unknown"))
        assertFalse(sameGitCommit("a123", full))
        assertFalse(sameGitCommit(full, hash + "f".repeat(32)))
    }

    @Test fun `compare status determines ahead behind and identical without version numbers`() {
        assertEquals(NightlyCommitRelation.AHEAD, decodeNightlyComparison("""{"status":"ahead","commits":[]}""").relation)
        assertEquals(NightlyCommitRelation.BEHIND, decodeNightlyComparison("""{"status":"behind","commits":[]}""").relation)
        assertEquals(NightlyCommitRelation.IDENTICAL, decodeNightlyComparison("""{"status":"identical","commits":[]}""").relation)
        assertEquals(NightlyCommitRelation.DIVERGED, decodeNightlyComparison("""{"status":"diverged","commits":[]}""").relation)
    }

    @Test fun `compare changelog contains at most thirty first line commit titles`() {
        val commits = (1..35).joinToString(",") { """{"commit":{"message":"Change $it\n\nDetails $it"}}""" }
        val comparison = decodeNightlyComparison("""{"status":"ahead","commits":[$commits]}""")
        assertEquals(30, comparison.commitTitles.size)
        assertEquals("Change 1", comparison.commitTitles.first())
        assertEquals("Change 30", comparison.commitTitles.last())
    }
}
