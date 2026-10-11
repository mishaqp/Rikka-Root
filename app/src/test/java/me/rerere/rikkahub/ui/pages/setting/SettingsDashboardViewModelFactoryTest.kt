package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.di.viewModelModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDashboardViewModelFactoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `production settings factory exposes settings updates without resolving MCP runtime`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val appScope = AppScope().apply { cancel() }
        val directory = temporary.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
        }
        val settings = SettingsStore(context, appScope)
        val initial = Settings(launchCount = 10, providers = emptyList())
        settings.settingsFlow.value = initial
        var mcpResolutions = 0
        val application = koinApplication {
            modules(viewModelModule, module {
                single { settings }
                single<McpManager> {
                    mcpResolutions++
                    error("The settings screen must not initialize the MCP runtime")
                }
            })
        }
        var vm: SettingVM? = null
        try {
            val resolvedVm = application.koin.get<SettingVM>()
            vm = resolvedVm
            assertTrue(resolvedVm.settings.value.init)
            var displayed: Settings? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                resolvedVm.settings.collect { displayed = it }
            }
            runCurrent()
            assertEquals(initial, displayed)

            val updated = initial.copy(launchCount = 11)
            settings.settingsFlow.value = updated
            runCurrent()
            assertEquals(updated, displayed)
            assertEquals(0, mcpResolutions)
        } finally {
            vm?.viewModelScope?.cancel()
            application.close()
            appScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
