package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.NightlyUpdateState
import me.rerere.rikkahub.utils.UpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsDashboardPanelPresentationTest {
    @Test
    fun `new update has a warning chip`() {
        assertEquals(
            chip("Есть обновление", SettingsDashboardStatusTone.Warning),
            SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState(update = update(isNewer = true))),
        )
    }

    @Test
    fun `saved newer update stays visible during a failed recheck`() {
        assertEquals(
            chip("Есть обновление", SettingsDashboardStatusTone.Warning),
            SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState(
                update = update(isNewer = true), error = "Private endpoint and credentials", checkedAt = 1,
            )),
        )
    }

    @Test
    fun `successful check with no newer update has a success chip`() {
        assertEquals(
            chip("Актуальная версия", SettingsDashboardStatusTone.Success),
            SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState(update = update(isNewer = false))),
        )
    }

    @Test
    fun `unchecked update has a concrete neutral chip`() {
        assertEquals(
            chip("Не проверялось"),
            SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState()),
        )
    }

    @Test
    fun `attempt timestamp without update result is not a successful check`() {
        assertEquals(
            chip("Не проверялось"),
            SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState(checkedAt = 1)),
        )
    }

    @Test
    fun `failed check hides error details and does not claim current version`() {
        listOf(null, update(isNewer = false)).forEach { update ->
            assertEquals(
                chip("Не проверялось"),
                SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState(
                    update = update, error = "Private endpoint and credentials", checkedAt = 1,
                )),
            )
        }
    }

    @Test
    fun `checking update has a concrete neutral chip`() {
        assertEquals(
            chip("Проверяю…"),
            SettingsDashboardPanelPresentation.updateChip(NightlyUpdateState(
                isChecking = true, update = update(isNewer = false), checkedAt = 1,
            )),
        )
    }

    @Test
    fun `initial settings hide values until settings are loaded`() {
        val model = Model(modelId = "global-model")
        val assistant = Assistant(localTools = listOf(LocalToolOption.Root))
        val settings = Settings(
            init = true,
            chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistantId = assistant.id,
            assistants = listOf(assistant),
        )
        assertEquals(
            mapOf("providers" to null, "model" to null, "tools" to null, "root" to null),
            SettingsDashboardPanelPresentation.quickTileValues(settings, SettingsDashboardStatus.Loading),
        )
        assertNull(SettingsDashboardPanelPresentation.modelName(settings))
    }

    @Test
    fun `root result remains visible while settings are initializing`() {
        assertEquals(
            "Root готов",
            SettingsDashboardPanelPresentation.quickTileValues(
                Settings(init = true), SettingsDashboardStatus.Ready(chip("Root готов")),
            )["root"],
        )
    }

    @Test
    fun `empty providers have no value`() {
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI()))
        assertNull(SettingsDashboardPanelPresentation.quickTileValues(settings, null)["providers"])
    }

    @Test
    fun `providers with models count without an API key and disabled providers do not count`() {
        val model = Model(modelId = "local-model")
        val settings = Settings(providers = listOf(
            ProviderSetting.OpenAI(apiKey = "", baseUrl = "http://localhost:1234/v1", models = listOf(model)),
            ProviderSetting.Google(models = listOf(model)),
            ProviderSetting.OpenAI(enabled = false, models = listOf(model)),
            ProviderSetting.OpenAI(apiKey = "configured-but-no-models"),
        ))
        assertEquals("2 подключено", SettingsDashboardPanelPresentation.quickTileValues(settings, null)["providers"])
    }

    @Test
    fun `only disabled providers have no value`() {
        val settings = Settings(providers = listOf(
            ProviderSetting.OpenAI(enabled = false, models = listOf(Model(modelId = "disabled-model"))),
        ))
        assertNull(SettingsDashboardPanelPresentation.quickTileValues(settings, null)["providers"])
    }

    @Test
    fun `model value uses global model display name`() {
        val global = Model(modelId = "global-model", displayName = "Модель по умолчанию")
        val assistantModel = Model(modelId = "assistant-model", displayName = "Модель ассистента")
        val assistant = Assistant(chatModelId = assistantModel.id)
        val settings = Settings(
            chatModelId = global.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(global, assistantModel))),
            assistantId = assistant.id,
            assistants = listOf(assistant),
        )
        assertEquals("Модель по умолчанию", SettingsDashboardPanelPresentation.modelName(settings))
        assertEquals("Модель по умолчанию", SettingsDashboardPanelPresentation.quickTileValues(settings, null)["model"])
    }

    @Test
    fun `blank display name falls back to model ID`() {
        val model = Model(modelId = "global-model", displayName = "   ")
        val settings = Settings(chatModelId = model.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        assertEquals("global-model", SettingsDashboardPanelPresentation.modelName(settings))
    }

    @Test
    fun `missing global model is explicitly unselected`() {
        assertEquals("Не выбрана", SettingsDashboardPanelPresentation.modelName(Settings(providers = emptyList())))
    }

    @Test
    fun `model without a name or ID is explicitly unselected`() {
        val model = Model(modelId = "", displayName = "")
        val settings = Settings(chatModelId = model.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
        assertEquals("Не выбрана", SettingsDashboardPanelPresentation.modelName(settings))
    }

    @Test
    fun `tools count belongs to selected assistant`() {
        val other = Assistant(localTools = listOf(LocalToolOption.TimeInfo))
        val selected = Assistant(localTools = listOf(LocalToolOption.Root, LocalToolOption.Browser))
        val settings = Settings(assistantId = selected.id, assistants = listOf(other, selected))
        assertEquals("Включено 2", SettingsDashboardPanelPresentation.quickTileValues(settings, null)["tools"])
    }

    @Test
    fun `selected assistant without tools has no value`() {
        val assistant = Assistant(localTools = emptyList())
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        assertNull(SettingsDashboardPanelPresentation.quickTileValues(settings, null)["tools"])
    }

    @Test
    fun `missing selected assistant has no tool value`() {
        val settings = Settings(assistantId = Uuid.random(), assistants = listOf(Assistant()))
        assertNull(SettingsDashboardPanelPresentation.quickTileValues(settings, null)["tools"])
    }

    @Test
    fun `root ready status uses its chip text`() {
        assertEquals(
            "Root готов",
            SettingsDashboardPanelPresentation.quickTileValues(
                Settings(), SettingsDashboardStatus.Ready(chip("Root готов", SettingsDashboardStatusTone.Success)),
            )["root"],
        )
    }

    @Test
    fun `root loading absent or empty ready status has no value`() {
        listOf(null, SettingsDashboardStatus.Loading, SettingsDashboardStatus.Ready()).forEach { root ->
            assertNull(SettingsDashboardPanelPresentation.quickTileValues(Settings(), root)["root"])
        }
    }

    @Test
    fun `root error has a useful value`() {
        assertEquals(
            "Недоступно",
            SettingsDashboardPanelPresentation.quickTileValues(Settings(), SettingsDashboardStatus.Error)["root"],
        )
    }

    private fun chip(text: String, tone: SettingsDashboardStatusTone = SettingsDashboardStatusTone.Neutral) =
        SettingsDashboardStatusChip(text, tone)

    private fun update(isNewer: Boolean) = UpdateInfo(
        version = "nightly", publishedAt = "", changelog = "", downloads = emptyList(), isNewer = isNewer,
    )
}
