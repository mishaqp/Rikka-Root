package me.rerere.rikkahub.miniapps

import android.Manifest
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MiniAppNativeHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun coldAskWaitsForPersistedSettingsThenUsesCurrentAssistantWithoutHistoryOrTools() = runBlocking {
        val root = Files.createTempDirectory("native-host-ask").toFile()
        try {
            val state = MutableStateFlow(Settings.dummy())
            val settings = mockk<SettingsStore>()
            every { settings.settingsFlow } returns state
            val provider = RecordingProvider()
            val providers = mockk<ProviderManager>()
            every { providers.getProviderByType(any<ProviderSetting>()) } returns provider
            val store = MiniAppStore(root)
            val host = MiniAppNativeHost(context, settings, providers, MiniAppAndroidReminders(context, store), MiniAppFetch())
                .forApp(MiniApp("water", "Water", root)) { error("AI must not request Android permissions") }
            val answer = async { host.ask!!.invoke("User question", "Only this system prompt") }
            withTimeout(5_000) { state.subscriptionCount.first { it > 0 } }
            assertFalse(answer.isCompleted)
            assertEquals(0, provider.calls.get())
            val default = Model(modelId = "default")
            val selected = Model(modelId = "assistant")
            val assistant = Assistant(chatModelId = selected.id)
            val setting = ProviderSetting.OpenAI(models = listOf(default, selected))
            state.value = Settings(init = false, chatModelId = default.id, assistantId = assistant.id,
                assistants = listOf(assistant), providers = listOf(setting))
            assertEquals("Plain provider answer", withTimeout(5_000) { answer.await() })
            assertEquals(1, provider.calls.get())
            assertEquals(setting, provider.setting)
            assertEquals(selected, provider.params!!.model)
            assertTrue(provider.params!!.tools.isEmpty())
            assertEquals(listOf("SYSTEM", "USER"), provider.messages.map { it.role.name })
            assertEquals(listOf("Only this system prompt", "User question"), provider.messages.map { it.toText() })

            state.value = state.value.copy(assistants = listOf(assistant.copy(chatModelId = null)))
            assertEquals("Plain provider answer", withTimeout(5_000) { host.ask!!.invoke("Second question", null) })
            assertEquals(default, provider.params!!.model)
            assertEquals(listOf("USER"), provider.messages.map { it.role.name })
            assertEquals(listOf("Second question"), provider.messages.map { it.toText() })
            assertEquals(2, provider.calls.get())
        } finally { root.deleteRecursively() }
    }

    @Test fun deniedFirstUseCalendarAndNotificationPermissionsHaveNoNativeEffects() = runBlocking {
        val root = Files.createTempDirectory("native-host-denial").toFile()
        try {
            val calendar = RecordingCalendar()
            calendar.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, calendar)
            val requested = mutableListOf<List<String>>()
            val store = MiniAppStore(root)
            val source = root.resolve(".source").apply { mkdirs() }
            source.resolve("moru-app.json").writeText("""{"id":"water","name":"Water"}""")
            source.resolve("index.html").writeText("<p>Water</p>")
            store.install(source)
            val host = MiniAppNativeHost(context, mockk(), mockk(), MiniAppAndroidReminders(context, store), MiniAppFetch())
                .forApp(MiniApp("water", "Water", root)) { requested.add(it); false }
            suspend fun denied(block: suspend () -> Unit) {
                try { block(); fail("permission denial was ignored") }
                catch (e: MiniAppException) { assertEquals("permission_denied", e.code) }
            }
            denied { host.calendar!!.invoke("createCalendarEvent", JsonObject(emptyMap())) }
            denied { host.notify!!.invoke("Water", "Drink water") }
            denied { host.beforeReminderSet!!.invoke() }
            assertEquals(listOf(
                listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                listOf(Manifest.permission.POST_NOTIFICATIONS), listOf(Manifest.permission.POST_NOTIFICATIONS)
            ), requested)
            assertEquals(0, calendar.reads)
            assertEquals(0, calendar.writes)
            assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
            assertTrue(store.readReminders("water").isEmpty())
        } finally { root.deleteRecursively() }
    }

    private class RecordingProvider : Provider<ProviderSetting> {
        val calls = AtomicInteger()
        var setting: ProviderSetting? = null
        var messages = emptyList<UIMessage>()
        var params: TextGenerationParams? = null
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unexpected listModels")
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("unexpected stream")
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            calls.incrementAndGet()
            setting = providerSetting; this.messages = messages; this.params = params
            return TextGenerationResult("answer", params.model.modelId, UIMessage.assistant("Plain provider answer"))
        }
    }

    private class RecordingCalendar : ContentProvider() {
        var reads = 0
        var writes = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
            reads++
            return MatrixCursor(arrayOf("_id"))
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri { writes++; return ContentUris.withAppendedId(uri, 1) }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes++; return 1 }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes++; return 1 }
        override fun getType(uri: Uri): String? = null
    }
}
