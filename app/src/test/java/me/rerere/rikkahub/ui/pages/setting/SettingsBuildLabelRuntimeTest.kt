package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.screenshot.ScreenshotFixtures
import me.rerere.rikkahub.ui.screenshot.ScreenshotTheme
import me.rerere.rikkahub.utils.NightlyUpdateState
import me.rerere.rikkahub.utils.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w411dp-h891dp-mdpi")
class SettingsBuildLabelRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun injectedBuildLabelReachesPanelAndExistingNavigationAndSearchCallbacksWork() {
        val query = mutableStateOf("")
        val update = mutableStateOf(NightlyUpdateState())
        val destinations = mutableListOf<Screen>()
        compose.setContent {
            ScreenshotTheme {
                Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                    SettingPageContent(ScreenshotFixtures.settings, ScreenshotFixtures.statuses, update.value,
                        query.value, { query.value = it }, open = {}, openScreen = { destinations += it },
                        buildLabel = "2.5.6-root.2 · fixture-build")
                }
            }
        }
        compose.onNodeWithText("2.5.6-root.2 · fixture-build").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Root. Root доступен")[0].performClick()
        compose.onNodeWithContentDescription("Модель по умолчанию.", substring = true).performClick()
        compose.onNodeWithContentDescription("Обновления. Не проверялось").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Screen.SettingRoot, Screen.SettingModels, Screen.SettingAbout), destinations)
            update.value = NightlyUpdateState(update = UpdateInfo("next", "", "", emptyList(), isNewer = true))
        }
        compose.onNodeWithContentDescription("Обновления. Есть обновление").performClick()
        compose.runOnIdle { assertEquals(Screen.SettingUpdate, destinations.last()) }
        compose.onNodeWithText("Поиск по настройкам").performTextReplacement("обои")
        compose.runOnIdle { assertEquals("обои", query.value) }
    }

    @Test fun bothProductionEntryPointsDefaultToActualBuildConfig() {
        val directPanel = mutableStateOf(false)
        compose.setContent {
            ScreenshotTheme {
                Surface(Modifier.requiredSize(411.dp, 891.dp)) {
                    if (directPanel.value) {
                        SettingsStatePanel(ScreenshotFixtures.settings,
                            ScreenshotFixtures.statuses[SettingsDashboardStatusKey.Root],
                            NightlyUpdateState(), open = {})
                    } else {
                        SettingPageContent(ScreenshotFixtures.settings, ScreenshotFixtures.statuses,
                            NightlyUpdateState(), "", {}, open = {}, openScreen = {})
                    }
                }
            }
        }
        val actual = "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT_SHORT}"
        compose.onNodeWithText(actual).assertIsDisplayed()
        compose.runOnIdle { directPanel.value = true }
        compose.onNodeWithText(actual).assertIsDisplayed()
    }
}
