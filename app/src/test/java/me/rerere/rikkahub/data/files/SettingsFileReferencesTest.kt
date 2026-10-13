package me.rerere.rikkahub.data.files

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SettingsFileReferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `disabled wallpaper and unselected custom fonts remain live file references`() {
        val filesDir = temporary.newFolder("files")
        val wallpaper = File(filesDir, "upload/wallpaper.png").toUri().toString()
        val settings = Settings(assistants = emptyList(), displaySetting = DisplaySetting(
            chatBackgroundEnabled = false, chatBackgroundImagePath = wallpaper,
            chatCustomFontPath = "fonts/chat.ttf", appCustomFontPath = "fonts/app.ttf",
            codeCustomFontPath = "fonts/code.ttf",
        ))

        assertEquals(setOf(wallpaper) + listOf("chat", "app", "code").flatMap { name ->
            listOf(File(filesDir, "fonts/$name.ttf").toUri().toString(),
                File(filesDir, "fonts/$name.ttf.license").toUri().toString())
        }, settings.localFileUrls(filesDir))
    }

    @Test fun `local scanner excludes network SAF and paths outside app files`() {
        val filesDir = temporary.newFolder("files")
        val settings = Settings(assistants = listOf(Assistant(background = "https://example.org/background.png")),
            displaySetting = DisplaySetting(userAvatar = Avatar.Image("content://provider/avatar"),
                appCustomFontPath = "../outside.ttf", codeCustomFontPath = ""))
        assertTrue(settings.localFileUrls(filesDir).isEmpty())
    }

    @Test fun `deleting assistant preserves its shared disabled global wallpaper`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val filesDir = temporary.newFolder("files")
        val wallpaper = write(filesDir, "upload/global.png")
        val uniqueAvatar = write(filesDir, "upload/avatar.png")
        val assistant = Assistant(background = wallpaper.toUri().toString(), avatar = Avatar.Image(uniqueAvatar.toUri().toString()))
        val state = MutableStateFlow(Settings(assistants = listOf(assistant), displaySetting = DisplaySetting(
            chatBackgroundEnabled = false, chatBackgroundImagePath = wallpaper.toUri().toString())))
        val store = settingsStore(state)
        val scope = AppScope().apply { cancel() }
        val files = FilesManager(context(filesDir), mockk(relaxed = true), scope)
        val vm = AssistantVM(store, mockk(relaxed = true), mockk(relaxed = true), files)
        try {
            vm.removeAssistant(assistant)
            advanceUntilIdle()
            assertTrue(state.value.assistants.isEmpty())
            assertTrue("Global wallpaper must survive assistant deletion while its path is selected", wallpaper.exists())
            assertFalse("An unshared assistant avatar should still be removed", uniqueAvatar.exists())
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `cleanup retains wallpaper until the configured path is removed`() = runBlocking {
        val filesDir = temporary.newFolder("files")
        val wallpaper = write(filesDir, "upload/global.png")
        val unreferenced = write(filesDir, "upload/unused.png")
        val state = MutableStateFlow(Settings(assistants = emptyList(), displaySetting = DisplaySetting(
            chatBackgroundEnabled = false, chatBackgroundImagePath = wallpaper.toUri().toString())))
        val repository = mockk<FilesRepository>(relaxed = true)
        every { repository.listByFolder(FileFolders.UPLOAD) } returns flowOf(listOf(entity(wallpaper, 1), entity(unreferenced, 2)))
        coEvery { repository.deleteById(any()) } returns 1
        val files = FilesManager(context(filesDir), repository, AppScope().apply { cancel() }, settingsStore(state))

        files.deleteChatFiles(listOf(wallpaper.toUri()))
        assertTrue(wallpaper.exists())
        assertTrue(files.deleteOlderThan(cutoffMillis = 10))
        assertTrue(wallpaper.exists())
        assertFalse(unreferenced.exists())
        unreferenced.writeText("unused")
        assertTrue(files.deleteAll())
        assertTrue(wallpaper.exists())
        assertFalse(unreferenced.exists())

        state.value = state.value.copy(displaySetting = state.value.displaySetting.copy(chatBackgroundImagePath = ""))
        files.deleteChatFiles(listOf(wallpaper.toUri()))
        assertFalse("Clearing the path releases the selected image", wallpaper.exists())
    }

    @Test fun `clearing assistant avatar and background retains a shared global wallpaper`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val filesDir = temporary.newFolder("files")
        val wallpaper = write(filesDir, "upload/global.png")
        val url = wallpaper.toUri().toString()
        val assistant = Assistant(background = url, avatar = Avatar.Image(url))
        val state = MutableStateFlow(Settings(assistants = listOf(assistant), displaySetting = DisplaySetting(
            chatBackgroundEnabled = false, chatBackgroundImagePath = url)))
        val memories = mockk<MemoryRepository>(relaxed = true)
        every { memories.getMemoriesOfAssistantFlow(any()) } returns flowOf(emptyList())
        every { memories.getGlobalMemoriesFlow() } returns flowOf(emptyList())
        val skills = mockk<SkillManager>(relaxed = true)
        every { skills.listSkills() } returns emptyList()
        val workspaces = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaces.listFlow() } returns flowOf(emptyList())
        val files = FilesManager(context(filesDir), mockk(relaxed = true), AppScope().apply { cancel() })
        val vm = AssistantDetailVM(assistant.id.toString(), settingsStore(state), memories, files, skills, workspaces)
        try {
            vm.persistAssistantUpdate { it.copy(avatar = Avatar.Dummy, background = null) }
            assertEquals(Avatar.Dummy, state.value.assistants.single().avatar)
            assertNull(state.value.assistants.single().background)
            assertTrue("Removing either assistant reference must retain the configured global image", wallpaper.exists())
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun settingsStore(state: MutableStateFlow<Settings>): SettingsStore = mockk<SettingsStore>(relaxed = true).also { store ->
        every { store.settingsFlow } returns state
        coEvery { store.update(any()) } coAnswers {
            firstArg<(Settings) -> Settings>().invoke(state.value).also { state.value = it }
        }
    }

    private fun context(filesDir: File): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = filesDir
    }

    private fun write(filesDir: File, path: String) = File(filesDir, path).apply { parentFile!!.mkdirs(); writeText(path) }

    private fun entity(file: File, id: Long) = ManagedFileEntity(id, FileFolders.UPLOAD, "upload/${file.name}", file.name,
        "image/png", file.length(), createdAt = 1, updatedAt = 1)
}
