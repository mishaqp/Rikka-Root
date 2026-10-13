package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toFile
import androidx.core.net.toUri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SettingsBackupFilePathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `backup moves global wallpaper to another files directory and keeps relative fonts`() = runBlocking {
        val original = context(temporary.newFolder("original"))
        val wallpaper = write(original.filesDir, "upload/global image.png", "wallpaper")
        val chatFont = write(original.filesDir, "fonts/chat.ttf", "chat font")
        val appFont = write(original.filesDir, "fonts/app.ttf", "app font")
        val codeFont = write(original.filesDir, "fonts/code.ttf", "code font")
        write(original.filesDir, "fonts/app.ttf.license", "font license")
        val seed = Settings(assistants = listOf(Assistant(background = wallpaper.toUri().toString())),
            displaySetting = DisplaySetting(chatBackgroundEnabled = false, chatBackgroundImagePath = wallpaper.toUri().toString(),
                userAvatar = Avatar.Image(wallpaper.toUri().toString()),
                chatCustomFontPath = "fonts/${chatFont.name}", appCustomFontPath = "fonts/${appFont.name}",
                codeCustomFontPath = "fonts/${codeFont.name}"))
        val archive = manager(original, seed).createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("upload/global image.png"))
            assertNotNull(zip.getEntry("fonts/app.ttf.license"))
        }
        val destination = context(temporary.newFolder("restored"))
        manager(destination, seed).stageRestore(archive, includeDatabase = false, includeFiles = true)
        var restored: Settings? = null
        pending(destination).apply { restored = JsonInstant.decodeFromString(it) }

        val display = restored!!.displaySetting
        val restoredWallpaper = File(destination.filesDir, "upload/global image.png")
        assertEquals(restoredWallpaper.toUri().toString(), display.chatBackgroundImagePath)
        assertEquals("wallpaper", display.chatBackgroundImagePath.toUri().toFile().readText())
        assertFalse(display.chatBackgroundEnabled)
        assertEquals(Avatar.Image(display.chatBackgroundImagePath), display.userAvatar)
        assertEquals(display.chatBackgroundImagePath, restored!!.assistants.single().background)
        assertEquals("fonts/chat.ttf", display.chatCustomFontPath)
        assertEquals("fonts/app.ttf", display.appCustomFontPath)
        assertEquals("fonts/code.ttf", display.codeCustomFontPath)
        assertEquals("chat font", File(destination.filesDir, display.chatCustomFontPath).readText())
        assertEquals("app font", File(destination.filesDir, display.appCustomFontPath).readText())
        assertEquals("code font", File(destination.filesDir, display.codeCustomFontPath).readText())
        assertEquals("font license", File(destination.filesDir, "fonts/app.ttf.license").readText())
    }

    @Test fun `settings only restore leaves image references and current local bytes untouched`() = runBlocking {
        val original = context(temporary.newFolder("original"))
        val image = write(original.filesDir, "upload/global.png", "archived")
        val seed = Settings(assistants = emptyList(), displaySetting = DisplaySetting(chatBackgroundImagePath = image.toUri().toString()))
        val archive = manager(original, seed).createBackup(includeDatabase = false, includeFiles = true)
        val destination = context(temporary.newFolder("restored"))
        val existing = write(destination.filesDir, "upload/global.png", "current")
        manager(destination, seed).stageRestore(archive, includeDatabase = false, includeFiles = false)
        var restored: Settings? = null
        pending(destination).apply { restored = JsonInstant.decodeFromString(it) }
        assertEquals(seed.displaySetting.chatBackgroundImagePath, restored!!.displaySetting.chatBackgroundImagePath)
        assertEquals("current", existing.readText())
    }

    @Test fun `restore only rewrites app upload images that actually exist in selected archive files`() = runBlocking {
        val context = context(temporary.newFolder("restored"))
        val missing = "file:///old/files/upload/missing.png"
        val saf = "content://provider/background"
        val network = "https://example.org/background.png"
        val seed = Settings(assistants = listOf(Assistant(background = saf), Assistant(background = network),
            Assistant(background = "file:///old/files/other/present.png")), displaySetting = DisplaySetting(
                chatBackgroundImagePath = missing, userAvatar = Avatar.Image("file:///old/files/upload/present.png")))
        val archive = temporary.newFile("partial.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            add(zip, "settings.json", JsonInstant.encodeToString(seed))
            add(zip, "upload/present.png", "present")
        }
        manager(context, seed).stageRestore(archive, includeDatabase = false, includeFiles = true)
        var restored: Settings? = null
        pending(context).apply { restored = JsonInstant.decodeFromString(it) }
        assertEquals(missing, restored!!.displaySetting.chatBackgroundImagePath)
        assertEquals(Avatar.Image(File(context.filesDir, "upload/present.png").toUri().toString()), restored!!.displaySetting.userAvatar)
        assertEquals(seed.assistants.map { it.background }, restored!!.assistants.map { it.background })
    }

    @Test fun `old settings without customization fields restore with compatible defaults`() = runBlocking {
        val context = context(temporary.newFolder("restored"))
        val archive = temporary.newFile("old.zip")
        ZipOutputStream(archive.outputStream()).use { add(it, "settings.json", "{\"assistants\":[],\"displaySetting\":{\"chatCustomFontPath\":\"fonts/old.ttf\"}}") }
        manager(context, Settings()).stageRestore(archive, includeDatabase = false, includeFiles = true)
        var restored: Settings? = null
        pending(context).apply { restored = JsonInstant.decodeFromString(it) }
        assertFalse(restored!!.displaySetting.chatBackgroundEnabled)
        assertEquals("", restored!!.displaySetting.chatBackgroundImagePath)
        assertEquals("fonts/old.ttf", restored!!.displaySetting.chatCustomFontPath)
        assertEquals("", restored!!.displaySetting.appCustomFontPath)
        assertEquals("", restored!!.displaySetting.codeCustomFontPath)
    }

    private fun manager(context: Context, settings: Settings): BackupManager {
        val store = mockk<SettingsStore>()
        every { store.settingsFlowRaw } returns flowOf(settings)
        return BackupManager(context, mockk(), store, JsonInstant, mockk())
    }

    private fun context(root: File): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, "databases/$name")
    }

    private fun pending(context: Context) = PendingRestore(File(context.noBackupFilesDir, "backup-restore"),
        context.getDatabasePath("rikka_hub"), context.filesDir)
    private fun write(root: File, path: String, bytes: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(bytes) }
    private fun add(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
    }
}
