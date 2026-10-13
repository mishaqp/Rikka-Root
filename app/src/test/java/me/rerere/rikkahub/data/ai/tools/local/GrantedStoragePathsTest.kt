package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GrantedStoragePathsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test fun trustedSdcardAliasAndCanonicalPathPointToTheSameHeldTree() {
        val primary = temporary.newFolder("primary")
        File(primary, "Download").mkdirs()
        val sdcard = File(temporary.root, "sdcard")
        Files.createSymbolicLink(sdcard.toPath(), primary.toPath())
        val roots = mapOf("primary" to primary)
        val canonical = grantedStoragePathPlan(File(primary, "Download/Отчёт + %2e.txt").path, tree, roots, listOf(sdcard))
        val alias = grantedStoragePathPlan(File(sdcard, "Download/Отчёт + %2e.txt").path, tree, roots, listOf(sdcard))
        assertNotNull(canonical)
        assertEquals(canonical, alias)
        assertEquals("primary:Download/Отчёт + %2e.txt", canonical!!.documentId)
        assertEquals(listOf("Отчёт + %2e.txt"), canonical.relativeNames)
        assertNull(grantedStoragePathPlan(File(sdcard, "Download/file").path, tree, roots))
    }

    @Test fun traversalPrefixCollisionAndDescendantSymlinksDoNotExpandTheGrant() {
        val primary = temporary.newFolder("primary")
        val download = File(primary, "Download").apply { mkdirs() }
        val outside = temporary.newFolder("outside")
        val roots = mapOf("primary" to primary)
        Files.createSymbolicLink(File(download, "escape").toPath(), outside.toPath())
        Files.createSymbolicLink(File(download, "alias").toPath(), download.toPath())
        listOf(File(primary, "Download2/file").path, File(primary, "Pictures/file").path,
            File(download, "../Pictures/file").path, File(download, "escape/file").path,
            File(download, "alias/file").path, "/system/build.prop").forEach {
            assertNull(it, grantedStoragePathPlan(it, tree, roots))
        }
    }

    @Test fun downloadsOpaqueRootUsesProviderNamesRatherThanFabricatedRawDocumentIds() {
        val primary = temporary.newFolder("primary")
        File(primary, "Download").mkdirs()
        val roots = mapOf("primary" to primary)
        val downloads = "content://com.android.providers.downloads.documents/tree/downloads"
        val root = grantedStoragePathPlan(File(primary, "Download").path, downloads, roots)
        assertEquals("downloads", root!!.documentId)
        assertEquals(emptyList<String>(), root.relativeNames)
        val child = grantedStoragePathPlan(File(primary, "Download/folder/file.txt").path, downloads, roots)
        assertNull(child!!.documentId)
        assertEquals(listOf("folder", "file.txt"), child.relativeNames)
        assertNull(grantedStoragePathPlan(File(primary, "Documents/file").path, downloads, roots))
        assertNull(grantedStoragePathPlan(File(primary, "Download/file").path,
            "content://com.android.providers.downloads.documents/tree/msd%3A123", roots))
    }

    @Test fun unknownAuthoritiesMalformedTreesAndOtherVolumesCannotBorrowAPrimaryGrant() {
        val primary = temporary.newFolder("primary")
        val card = temporary.newFolder("card")
        val roots = mapOf("primary" to primary, "ABCD-1234" to card)
        val path = File(primary, "Download/file").path
        listOf(tree.replace("com.android.externalstorage.documents", "cloud"), "$tree?query=1", "$tree#fragment",
            tree.replace("primary%3ADownload", "primary%3ADownload%2F..%2FPictures"),
            tree.replace("primary%3ADownload", "primary%3ADownload2")).forEach {
            assertNull(it, grantedStoragePathPlan(path, it, roots))
        }
        assertNull(grantedStoragePathPlan(File(card, "Download/file").path, tree, roots))
        val cardTree = tree.replace("primary%3A", "ABCD-1234%3A")
        assertEquals("ABCD-1234:Download/file", grantedStoragePathPlan(File(card, "Download/file").path, cardTree, roots)!!.documentId)
    }

    @Test fun rawDownloadsTreesRetainNativeIdsAndDoNotAllowUnknownStorageAliases() {
        val primary = temporary.newFolder("primary")
        val download = File(primary, "Download").apply { mkdirs() }
        val other = temporary.newFolder("other")
        val misleading = File(temporary.root, "sdcard")
        Files.createSymbolicLink(misleading.toPath(), other.toPath())
        val roots = mapOf("primary" to primary)
        val rawTree = "content://com.android.providers.downloads.documents/tree/" +
            java.net.URLEncoder.encode("raw:${download.path}", "UTF-8")
        assertEquals("raw:${download.path}/file + %2e.txt", grantedStoragePathPlan(
            File(download, "file + %2e.txt").path, rawTree, roots)!!.documentId)
        assertNull(grantedStoragePathPlan(File(other, "Download/file").path, rawTree, roots, listOf(misleading)))
        assertNull(grantedStoragePathPlan(File(misleading, "Download/file").path, tree, roots, listOf(misleading)))
        val wholeVolume = tree.replace("primary%3ADownload", "primary%3A")
        assertEquals("primary:Download/file", grantedStoragePathPlan(File(download, "file").path, wholeVolume, roots)!!.documentId)
        val volumeRoot = grantedStoragePathPlan(primary.path, wholeVolume, roots)!!
        assertEquals("primary:", volumeRoot.documentId)
        assertEquals(emptyList<String>(), volumeRoot.relativeNames)
        assertNull(grantedStoragePathPlan(primary.path, tree, roots))
    }
}
