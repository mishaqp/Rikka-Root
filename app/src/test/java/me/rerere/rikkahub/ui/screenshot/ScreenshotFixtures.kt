package me.rerere.rikkahub.ui.screenshot

import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.tools.LocalToolControls
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPageContent
import me.rerere.rikkahub.ui.pages.setting.SettingPageContent
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatus
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusChip
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusKey
import me.rerere.rikkahub.ui.pages.setting.SettingsDashboardStatusTone
import me.rerere.rikkahub.ui.theme.RikkahubThemeContent
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.utils.NightlyUpdateState
import kotlin.uuid.Uuid

internal enum class ScreenshotThemeMode(val fileName: String, val dark: Boolean, val amoled: Boolean = false) {
    Light("light", false),
    Dark("dark", true),
    Amoled("amoled", true, true),
    Dynamic("dynamic", false),
}

/** Stable data only: no database, network, root probes or permission hosts. */
internal object ScreenshotFixtures {
    val model = Model(
        id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
        modelId = "gpt-4.1",
        displayName = "GPT-4.1",
    )
    val assistant = Assistant(
        id = Uuid.parse("20000000-0000-0000-0000-000000000001"),
        name = "Помощник",
        chatModelId = model.id,
        localTools = listOf(
            LocalToolOption.TimeInfo, LocalToolOption.SubAgents, LocalToolOption.Workflows,
            LocalToolOption.Root, LocalToolOption.Termux, LocalToolOption.Ssh,
            LocalToolOption.Browser, LocalToolOption.Files, LocalToolOption.Battery,
            LocalToolOption.Clipboard, LocalToolOption.AskUser, LocalToolOption.Notification,
        ),
    )
    val settings = Settings(
        dynamicColor = false,
        chatModelId = model.id,
        fastModelId = model.id,
        imageGenerationModelId = model.id,
        translateModeId = model.id,
        ocrModelId = model.id,
        compressModelId = model.id,
        assistantId = assistant.id,
        providers = listOf(ProviderSetting.OpenAI(
            id = Uuid.parse("30000000-0000-0000-0000-000000000001"),
            name = "OpenAI",
            models = listOf(model),
        )),
        assistants = listOf(assistant),
    )
    val statuses: Map<SettingsDashboardStatusKey, SettingsDashboardStatus> =
        SettingsDashboardStatusKey.entries.associateWith { SettingsDashboardStatus.Ready() } + mapOf(
            SettingsDashboardStatusKey.Root to SettingsDashboardStatus.Ready(
                SettingsDashboardStatusChip("Root доступен", SettingsDashboardStatusTone.Success),
            ),
            SettingsDashboardStatusKey.Ssh to SettingsDashboardStatus.Ready(SettingsDashboardStatusChip("2 хостов")),
            SettingsDashboardStatusKey.Mcp to SettingsDashboardStatus.Loading,
            SettingsDashboardStatusKey.Workflows to SettingsDashboardStatus.Ready(SettingsDashboardStatusChip("3 активных")),
            SettingsDashboardStatusKey.Cron to SettingsDashboardStatus.Ready(SettingsDashboardStatusChip("2 в расписании")),
        )
    val update = NightlyUpdateState()
}

/** Uses the same theme renderer, typography, extended colors and AMOLED conversion as the app. */
@Composable
internal fun ScreenshotTheme(
    mode: ScreenshotThemeMode = ScreenshotThemeMode.Light,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scheme = remember(mode, context) {
        if (mode == ScreenshotThemeMode.Dynamic) {
            // FixedSeedDynamicColors supplies Android system roles for a frozen wallpaper seed.
            dynamicLightColorScheme(context)
        } else {
            findPresetTheme(ScreenshotFixtures.settings.themeId).getColorScheme(dark = mode.dark)
        }
    }
    val navigator = remember { Navigator(mutableListOf()) }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale),
        LocalSettings provides ScreenshotFixtures.settings,
        LocalNavController provides navigator,
    ) {
        RikkahubThemeContent(scheme, mode.dark, mode.amoled, content)
    }
}

@Composable
internal fun MainSettingsScreenshotFixture() {
    SettingPageContent(
        settings = ScreenshotFixtures.settings,
        statuses = ScreenshotFixtures.statuses,
        update = ScreenshotFixtures.update,
        query = "",
        onQueryChange = {},
        open = {},
        openScreen = {},
    )
}

@Composable
internal fun AssistantToolsScreenshotFixture() {
    val controls = remember {
        LocalToolControls(isBusy = { false }, enable = { _, _ -> }, apply = {}, permission = {})
    }
    AssistantLocalToolPageContent(
        assistant = ScreenshotFixtures.assistant,
        estimate = 6_400L,
        metadataError = false,
        query = "",
        category = null,
        controls = controls,
        definitions = emptyMap(),
        accessRevision = 0,
        onQueryChange = {},
        onCategorySelected = {},
        onPresetSelected = {},
        onToolSelected = {},
        onBackToTools = {},
    )
}
