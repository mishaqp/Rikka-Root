package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test

class SafInitialFolderTest {
    private val download = "content://com.android.externalstorage.documents/document/primary%3ADownload"

    @Test fun pickerOpensInDownloadByDefault() {
        assertEquals(download, safInitialFolderUri(null))
        assertEquals(download, safInitialFolderUri("  "))
    }

    @Test fun acceptsTreeDocumentRootUrisAndPhonePaths() {
        assertEquals(download, safInitialFolderUri("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        assertEquals(download, safInitialFolderUri(download))
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3ADCIM",
            safInitialFolderUri("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3ADCIM"))
        assertEquals("content://com.android.externalstorage.documents/document/primary%3A",
            safInitialFolderUri("content://com.android.externalstorage.documents/root/primary"))
        assertEquals(download, safInitialFolderUri("/sdcard/Download"))
        assertEquals(download, safInitialFolderUri("/storage/emulated/0/Download/"))
        assertEquals("content://com.android.externalstorage.documents/document/primary%3ADocuments%2F%D0%9E%D1%82%D1%87%D1%91%D1%82%D1%8B",
            safInitialFolderUri("/sdcard/Documents/Отчёты"))
        assertEquals("content://com.android.externalstorage.documents/document/1A2B-3C4D%3AMusic",
            safInitialFolderUri("/storage/1A2B-3C4D/Music"))
        assertEquals("content://com.android.providers.downloads.documents/document/downloads",
            safInitialFolderUri("content://com.android.providers.downloads.documents/tree/downloads"))
    }

    @Test fun rejectsAddressesThatAreNotFolders() {
        for (bad in listOf("https://example.com", "/data/data/x", "/sdcard/../data", "content://", "content://a/b?x=1", "Download")) {
            val error = assertThrows(bad, IllegalArgumentException::class.java) { safInitialFolderUri(bad) }
            assertTrue(error.message!!.contains("/sdcard/Download"))
        }
    }

    @Test fun storageRootGrantWarnsThatWritesNeedASubfolder() {
        val warning = safGrantWarning("content://com.android.externalstorage.documents/tree/primary%3A")
        assertNotNull(warning)
        assertTrue(warning!!.contains("Download"))
        assertNotNull(safGrantWarning("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"))
        assertNull(safGrantWarning("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        assertNull(safGrantWarning("content://com.android.providers.downloads.documents/tree/downloads"))
    }
}
