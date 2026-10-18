package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.net.Uri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class StorageListTruncationPart2Test {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun body(parts: List<UIMessagePart>): JsonObject {
        val raw = (parts.single() as UIMessagePart.Text).text
        assertFalse(raw, raw.contains('\n'))
        return Json.parseToJsonElement(raw).jsonObject
    }
    private fun assertSummary(result: JsonObject, key: String, returned: Int, total: Int) {
        assertEquals(result.toString(), returned, result.getValue(key).jsonArray.size)
        assertEquals(result.toString(), JsonPrimitive(returned), result["count"])
        assertEquals(result.toString(), JsonPrimitive(total), result["total"])
        assertEquals(result.toString(), JsonPrimitive(total > returned), result["truncated"])
        if (total > returned) assertTrue(result.toString(), result["message"]?.jsonPrimitive?.content.orEmpty().contains(Regex("[А-Яа-яЁё]")))
    }

    @Test fun moreThanSixtyFourAndroidVolumesExposeActualTotalAndTruncation() = runBlocking {
        val manager = context.getSystemService(StorageManager::class.java)
        repeat(65) { index ->
            shadowOf(manager).addStorageVolume(StorageVolumeBuilder("contract-$index", temporary.newFolder(),
                "USER volume $index", Process.myUserHandle(), Environment.MEDIA_MOUNTED).build())
        }
        val actualTotal = manager.storageVolumes.size
        assertTrue(actualTotal >= 65)
        val result = body(listStorageVolumesTool(context).execute(buildJsonObject {}))
        assertSummary(result, "volumes", 64, actualTotal)
        assertEquals(actualTotal, manager.storageVolumes.size)
    }

    @Test fun grantedDirectoryTotalCountsOnlyReadableTreesAndReportsActualCap() = runBlocking {
        repeat(257) { index ->
            context.contentResolver.takePersistableUriPermission(
                DocumentsContract.buildTreeDocumentUri("contracts.documents", "directory-$index"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.contentResolver.takePersistableUriPermission(
            DocumentsContract.buildTreeDocumentUri("contracts.documents", "write-only"), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(Uri.parse("content://contracts.documents/document/not-a-tree"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(259, context.contentResolver.persistedUriPermissions.size)
        val result = body(listGrantedDirectoriesTool(context).execute(buildJsonObject {}))
        assertSummary(result, "directories", 256, 257)
        assertTrue(result.getValue("directories").jsonArray.none { it.jsonObject.getValue("content_uri").jsonPrimitive.content.contains("write-only") })
        assertEquals(259, context.contentResolver.persistedUriPermissions.size)
    }

    @Test fun completeStorageListsDeclareFalseWithoutInventingMissingEntries() = runBlocking {
        val actualVolumes = context.getSystemService(StorageManager::class.java).storageVolumes.size
        assertTrue(actualVolumes <= 64)
        assertSummary(body(listStorageVolumesTool(context).execute(buildJsonObject {})), "volumes", actualVolumes, actualVolumes)
        context.contentResolver.takePersistableUriPermission(
            DocumentsContract.buildTreeDocumentUri("contracts.documents", "only-tree"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertSummary(body(listGrantedDirectoriesTool(context).execute(buildJsonObject {})), "directories", 1, 1)
    }
}
