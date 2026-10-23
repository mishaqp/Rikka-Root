package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.assistant.detail.LocalToolDetailSettings
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class RootBackgroundPreparationRuntimeTest {
    @get:Rule val compose = createComposeRule()
    @After fun cleanup() { stopKoin() }

    @Test fun screenAutomationWithoutRootExecCanPrepareFromRootSettings() = checkPreparation(rootMode = false, detail = false)
    @Test fun rootModeWithoutExplicitRootExecCanPrepareFromRootSettings() = checkPreparation(rootMode = true, detail = false)
    @Test fun screenAutomationWithoutRootExecCanPrepareFromToolDetail() = checkPreparation(rootMode = false, detail = true)
    @Test fun rootModeWithoutExplicitRootExecCanPrepareFromToolDetail() = checkPreparation(rootMode = true, detail = true)

    private fun checkPreparation(rootMode: Boolean, detail: Boolean) {
        val assistant = Assistant(rootMode = rootMode, localTools = buildList {
            add(LocalToolOption.Workflows)
            if (!rootMode) add(LocalToolOption.ScreenAutomation)
        })
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        val root = mockk<RootShellManager> {
            every { status } returns MutableStateFlow(RootStatus.UNCHECKED)
            every { isHeadlessReady() } returns true
            coEvery { verifyRoot(any(), any()) } returns RootStatus.READY
        }
        startKoin { modules(module { single { root } }) }
        compose.setContent {
            ScreenshotTheme {
                CompositionLocalProvider(LocalSettings provides settings,
                    LocalNavController provides Navigator(mutableListOf()),
                    LocalToaster provides rememberToasterState()) {
                    Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                        if (detail) LocalToolDetailSettings(LocalToolOption.Root, assistant, onUpdate = {})
                        else SettingRootPage()
                    }
                }
            }
        }
        coVerify(exactly = 0) { root.verifyRoot(any(), any()) }
        if (!detail) compose.onNode(hasScrollAction()).performScrollToNode(hasText("Открыть root-сессию"))
        compose.onNodeWithText("Открыть root-сессию").assertIsDisplayed().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { root.verifyRoot(force = true, prepareHeadless = true) }
    }
}
