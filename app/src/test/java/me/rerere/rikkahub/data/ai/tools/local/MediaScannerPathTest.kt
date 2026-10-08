package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaScannerPathTest {
    private fun withVolumes(test: (File, File) -> Unit) {
        val area = Files.createTempDirectory("scanner-volumes").toFile()
        val primary = File(area, "primary").also { it.mkdir() }
        val card = File(area, "card").also { it.mkdir() }
        try { test(primary, card) } finally { area.deleteRecursively() }
    }

    private fun rejected(documentId: String, roots: Map<String, File>) {
        try { externalStorageMediaScanPath(documentId, roots); fail("Accepted unsafe or unavailable document: $documentId") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun primaryAndRemovableDocumentsStayUnderTheirOwnVolumes() = withVolumes { primary, card ->
        val roots = mapOf("primary" to primary, "ABCD-1234" to card)
        assertEquals(File(primary, "Pictures/photo.jpg").canonicalFile, externalStorageMediaScanPath("primary:Pictures/photo.jpg", roots))
        assertEquals(File(card, "Music/song.mp3").canonicalFile, externalStorageMediaScanPath("abcd-1234:Music/song.mp3", roots))
        assertEquals(File(card, "Music/song.mp3").canonicalFile, externalStorageMediaScanPath("ABCD-1234:Music/song.mp3", roots))
    }

    @Test fun uriGrantDoesNotRequireTheMappedPathToPassLinuxFileReadChecks() = withVolumes { primary, _ ->
        val missing = File(primary, "Pictures/not-visible-to-app.jpg")
        assertFalse(missing.exists())
        assertEquals(missing.canonicalFile, externalStorageMediaScanPath("primary:Pictures/not-visible-to-app.jpg", mapOf("primary" to primary)))
    }

    @Test fun traversalAndAmbiguousComponentsCannotReachSiblingVolumes() = withVolumes { primary, _ ->
        val roots = mapOf("primary" to primary)
        listOf("primary:../card/photo.jpg", "primary:Pictures/../../card/photo.jpg", "primary:/photo.jpg",
            "primary:Pictures//photo.jpg", "primary:Pictures/./photo.jpg", "primary:Pictures/photo.jpg/",
            "primary:Pictures\\..\\photo.jpg", "primary:Pictures/\u0000photo.jpg", "primary:Pictures/\nphoto.jpg").forEach { rejected(it, roots) }
    }

    @Test fun unknownAndMalformedVolumesAreRejected() = withVolumes { primary, _ ->
        val roots = mapOf("primary" to primary)
        listOf("4321-ABCD:Pictures/photo.jpg", "raw:/storage/emulated/0/photo.jpg", "../primary:photo.jpg",
            "primary/path:photo.jpg", "primary\\path:photo.jpg", "primary\n:photo.jpg", "primary", "primary:", ":photo.jpg").forEach { rejected(it, roots) }
    }

    @Test fun symlinkedChildCannotEscapeEvenToAVolumeWithASimilarPrefix() = withVolumes { primary, _ ->
        val sibling = File(primary.parentFile, "primary-other").also { it.mkdir() }
        Files.createSymbolicLink(File(primary, "Pictures").toPath(), sibling.toPath())
        rejected("primary:Pictures/photo.jpg", mapOf("primary" to primary))
    }

    @Test fun trustedRootAliasesMapToTheSameCanonicalTarget() = withVolumes { primary, _ ->
        val alias = File(primary.parentFile, "sdcard")
        Files.createSymbolicLink(alias.toPath(), primary.toPath())
        val physical = externalStorageMediaScanPath("primary:Pictures/photo.jpg", mapOf("primary" to primary))
        val aliased = externalStorageMediaScanPath("primary:Pictures/photo.jpg", mapOf("primary" to alias))
        assertEquals(physical, aliased)
    }

    @Test fun decodedDocumentIdsAreNotDecodedAgain() = withVolumes { primary, _ ->
        assertEquals(File(primary, "%2e%2e/photo.jpg").canonicalFile,
            externalStorageMediaScanPath("primary:%2e%2e/photo.jpg", mapOf("primary" to primary)))
    }

    @Test fun aFullUuidMustStillMatchATrustedVolume() = withVolumes { _, card ->
        val uuid = "01234567-89ab-cdef-0123-456789abcdef"
        assertEquals(File(card, "photo.jpg").canonicalFile, externalStorageMediaScanPath("$uuid:photo.jpg", mapOf(uuid.uppercase() to card)))
        rejected("$uuid:photo.jpg", emptyMap())
    }
}
