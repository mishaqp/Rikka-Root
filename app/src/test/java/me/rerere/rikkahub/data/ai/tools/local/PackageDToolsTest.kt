package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ContextWrapper
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageDToolsTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun allAgentNamesAreUniqueRegisteredAndRequireApproval() {
        val files=temp.newFolder()
        val context=object:ContextWrapper(null) {
            override fun getFilesDir():File=files
            override fun getApplicationContext():Context=this
        }
        val access=LocalFileAccess(context)
        val tools=fileManagerTools(access,context)+listOf(
            listStorageVolumesTool(context),listGrantedDirectoriesTool(context),grantDirectoryAccessTool(context),
            zipFilesTool(context,access),unzipFileTool(context,access),listZipContentsTool(context,access),
            playMediaTool(context,access),stopMediaTool(context),pauseMediaTool(context),resumeMediaTool(context,access),seekMediaTool(context),getMediaStatusTool(),
            mediaScannerTool(context,access),downloadTool(context,access),writeTextFileTool(access),
        )+systemIntentTools(context)+appLauncherTools(context)
        assertEquals(40,tools.size); assertEquals(40,tools.map { it.name }.toSet().size)
        val input=Json.parseToJsonElement("{}")
        tools.forEach { tool ->
            assertTrue(tool.name,tool.name in ToolPermissionPolicy.registry)
            assertTrue(tool.name,ToolPermissionPolicy.apply(tool).needsApproval(input))
        }
    }
    @Test fun backupsUseExactAgentNamesAndAllEightFeaturesStayDisabledByDefault() {
        val defaults=Assistant().localTools
        val restored=Json.decodeFromString<Assistant>("{}").localTools
        assertEquals(listOf(LocalToolOption.TimeInfo),defaults); assertEquals(defaults,restored)
        for(name in listOf("files","external_storage","archive","media_player","media_scanner","download","system_intents","app_launcher")) {
            val encoded="{\"type\":\"$name\"}"
            val option=Json.decodeFromString<LocalToolOption>(encoded)
            assertEquals(encoded,Json.encodeToString<LocalToolOption>(option)); assertFalse(option in restored)
        }
    }
    @Test fun onlyLegacyPublicDownloadsRequireStoragePermission() {
        assertEquals(listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),localToolRuntimePermissions(LocalToolOption.Download,26))
        assertEquals(listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),localToolRuntimePermissions(LocalToolOption.Download,28))
        assertTrue(localToolRuntimePermissions(LocalToolOption.Download,29).isEmpty())
        listOf(LocalToolOption.Files,LocalToolOption.ExternalStorage,LocalToolOption.Archive,LocalToolOption.MediaPlayer,LocalToolOption.MediaScanner,LocalToolOption.SystemIntents,LocalToolOption.AppLauncher).forEach { assertTrue(localToolRuntimePermissions(it,26).isEmpty()) }
        assertEquals("pm grant --user 0 me.app android.permission.WRITE_EXTERNAL_STORAGE",localPermissionGrantCommand("me.app",LocalToolOption.Download,28,0))
        assertNull(localPermissionGrantCommand("me.app",LocalToolOption.Download,29,0))
    }
}
