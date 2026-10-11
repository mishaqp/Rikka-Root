package me.rerere.rikkahub.ui.components.ai.tools

import kotlin.reflect.KClass
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolUiRegistryTest {
    @Test
    fun `every sealed option has exactly one UI entry and category`() {
        // Discover the model independently: adding a future option must fail until it is mapped.
        val discovered = discoverOptions(LocalToolOption::class)
        val registered = LocalToolUiRegistry.entries.map { it.option }
        val categorized = LocalToolUiRegistry.categories.flatMap { it.options }

        assertEquals(55, discovered.size)
        assertEquals(discovered, registered.toSet())
        assertEquals(registered.size, registered.toSet().size)
        assertEquals(discovered, categorized.toSet())
        assertEquals(categorized.size, categorized.toSet().size)
        assertEquals(registered, categorized)
        discovered.forEach { option ->
            assertEquals(option, LocalToolUiRegistry.entry(option).option)
            assertEquals(1, LocalToolUiRegistry.category(option).options.count { it == option })
        }
    }

    @Test
    fun `categories and options follow the requested order`() {
        val expected = linkedMapOf(
            "agent_automation" to listOf(LocalToolOption.SubAgents, LocalToolOption.CronJobs,
                LocalToolOption.Workflows, LocalToolOption.SkillImport, LocalToolOption.JsSkills,
                LocalToolOption.JavascriptEngine, LocalToolOption.AskUser, LocalToolOption.CostGuards,
                LocalToolOption.Reliability),
            "root_terminal" to listOf(LocalToolOption.Root, LocalToolOption.Termux,
                LocalToolOption.Ssh, LocalToolOption.ScreenAutomation),
            "internet" to listOf(LocalToolOption.Browser, LocalToolOption.McpControl,
                LocalToolOption.ExternalAutomation, LocalToolOption.Download),
            "files_media" to listOf(LocalToolOption.Files, LocalToolOption.ExternalStorage,
                LocalToolOption.Archive, LocalToolOption.MediaPlayer, LocalToolOption.MediaScanner),
            "device" to listOf(LocalToolOption.Battery, LocalToolOption.AudioInfo,
                LocalToolOption.TelephonyInfo, LocalToolOption.WifiInfo, LocalToolOption.Sensors,
                LocalToolOption.StorageInfo, LocalToolOption.TimeInfo, LocalToolOption.ScreenTime),
            "phone" to listOf(LocalToolOption.Torch, LocalToolOption.Vibrate,
                LocalToolOption.Brightness, LocalToolOption.Volume, LocalToolOption.Wallpaper,
                LocalToolOption.Nfc, LocalToolOption.SystemIntents, LocalToolOption.AppLauncher,
                LocalToolOption.Clipboard),
            "personal" to listOf(LocalToolOption.Location, LocalToolOption.Contacts,
                LocalToolOption.CallLog, LocalToolOption.SmsInbox, LocalToolOption.SmsSend,
                LocalToolOption.Calendar, LocalToolOption.Keystore, LocalToolOption.Fingerprint),
            "camera_audio" to listOf(LocalToolOption.CameraPhoto, LocalToolOption.MicRecorder,
                LocalToolOption.SpeechToText, LocalToolOption.Tts),
            "output" to listOf(LocalToolOption.Toast, LocalToolOption.Notification,
                LocalToolOption.Share, LocalToolOption.ChartDisplay),
        )

        assertEquals(expected.keys.toList(), LocalToolUiRegistry.categories.map { it.id })
        LocalToolUiRegistry.categories.forEach { category ->
            assertEquals(expected.getValue(category.id), category.options)
            assertTrue(category.title.isNotBlank())
        }
    }

    @Test
    fun `minimum preset has exactly three safe tools`() {
        assertEquals(minimum, LocalToolUiRegistry.preset(LocalToolPreset.Minimum))
    }

    @Test
    fun `helper preset includes minimum files notification calendar browser and device tools`() {
        assertEquals(helper, LocalToolUiRegistry.preset(LocalToolPreset.Helper))
    }

    @Test
    fun `agent preset adds precisely the requested automation tools`() {
        val expected = helper + setOf(LocalToolOption.Root, LocalToolOption.Termux,
            LocalToolOption.ScreenAutomation, LocalToolOption.SubAgents, LocalToolOption.CronJobs,
            LocalToolOption.Workflows, LocalToolOption.SkillImport, LocalToolOption.JsSkills)
        assertEquals(expected, LocalToolUiRegistry.preset(LocalToolPreset.Agent))
    }

    @Test
    fun `all preset discovers every registered option and none is empty`() {
        assertEquals(discoverOptions(LocalToolOption::class), LocalToolUiRegistry.preset(LocalToolPreset.All))
        assertEquals(emptySet<LocalToolOption>(), LocalToolUiRegistry.preset(LocalToolPreset.None))
    }

    private fun discoverOptions(type: KClass<out LocalToolOption>): Set<LocalToolOption> =
        if (type.isSealed) type.sealedSubclasses.flatMap { discoverOptions(it) }.toSet()
        else setOf(requireNotNull(type.objectInstance) { "LocalToolOption must be a singleton: $type" })

    private val minimum = setOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard, LocalToolOption.AskUser)
    private val helper = minimum + setOf(LocalToolOption.Files, LocalToolOption.Notification,
        LocalToolOption.Calendar, LocalToolOption.Browser, LocalToolOption.Battery,
        LocalToolOption.AudioInfo, LocalToolOption.TelephonyInfo, LocalToolOption.WifiInfo,
        LocalToolOption.Sensors, LocalToolOption.StorageInfo, LocalToolOption.ScreenTime)
}
