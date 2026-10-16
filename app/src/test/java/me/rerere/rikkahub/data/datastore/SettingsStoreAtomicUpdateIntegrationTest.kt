package me.rerere.rikkahub.data.datastore

import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.flowOf
import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.ui.pages.setting.components.convertTo
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesVM
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class SettingsStoreAtomicUpdateIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val main = StandardTestDispatcher()

    @Before fun setup() {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("no-backup")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
        }
        singletonField().set(null, null)
        Dispatchers.setMain(main)
    }

    @After fun cleanup() = runBlocking {
        val store = persisted()
        if (store != null) {
            var delegate: Any = store
            while (delegate.javaClass.declaredFields.none { it.name == "scope" }) {
                delegate = delegate.javaClass.getDeclaredField("delegate").also { it.isAccessible = true }.get(delegate) as Any
            }
            val scope = delegate.javaClass.getDeclaredField("scope").also { it.isAccessible = true }.get(delegate) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        singletonField().set(null, null)
        Dispatchers.resetMain()
    }

    @Test fun `direct assistant selection survives ordinary functional save`() = runBlocking {
        val a = Assistant(name = "A")
        val b = Assistant(name = "B")
        val seed = Settings(assistantId = a.id, assistants = listOf(a, b))
        val store = seed(seed)
        store.updateAssistant(b.id)
        store.update { it.copy(developerMode = true) }
        assertEquals(b.id.toString(), persisted()!!.data.first()[SettingsStore.SELECT_ASSISTANT])
        assertEquals(true, persisted()!!.data.first()[SettingsStore.DEVELOPER_MODE])
    }

    @Test fun `all storage switch is disabled for old backups and survives save restore and reload`() = runBlocking {
        assertFalse(JsonInstant.decodeFromString<Settings>("{}").fileToolsAllStorage)
        val backup = JsonInstant.encodeToString(Settings(fileToolsAllStorage = true))
        assertEquals(true, kotlinx.serialization.json.Json.parseToJsonElement(backup)
            .let { it as kotlinx.serialization.json.JsonObject }["fileToolsAllStorage"]
            .let { it as JsonPrimitive }.content.toBooleanStrict())
        val restored = JsonInstant.decodeFromString<Settings>(backup)
        assertTrue(restored.fileToolsAllStorage)
        val store = seed(restored)
        store.update { it.copy(developerMode = true) }
        assertEquals(true, persisted()!!.data.first()[SettingsStore.FILE_TOOLS_ALL_STORAGE])
        val loaded = SettingsStore(context, AppScope().apply { cancel() }).update { it }
        assertTrue(loaded.fileToolsAllStorage)
        store.update { it.copy(fileToolsAllStorage = false) }
        val disabled = SettingsStore(context, AppScope().apply { cancel() }).update { it }
        assertFalse(disabled.fileToolsAllStorage)
        // Importing an old backup must not inherit a previous opt-in from the device.
        SettingsStore.restoreBeforeInitialization(context, JsonInstant.decodeFromString("{}"))
        assertEquals(false, persisted()!!.data.first()[SettingsStore.FILE_TOOLS_ALL_STORAGE])
    }

    @Test fun `direct launch increment survives ordinary functional save`() = runBlocking {
        val store = seed(Settings(launchCount = 7))
        assertEquals(8, store.incrementLaunchCount())
        store.update { it.copy(developerMode = true) }
        assertEquals(8, persisted()!!.data.first()[SettingsStore.LAUNCH_COUNT])
    }

    @Test fun `two production store instances preserve unrelated writes`() = runBlocking {
        val initial = Settings(dynamicColor = false, developerMode = false)
        val first = seed(initial)
        val second = frozen(initial)
        first.update { it.copy(dynamicColor = true) }
        second.update { it.copy(developerMode = true) }
        assertEquals(true, persisted()!!.data.first()[SettingsStore.DYNAMIC_COLOR])
        assertEquals(true, persisted()!!.data.first()[SettingsStore.DEVELOPER_MODE])
    }

    @Test fun `persisted settings can be edited before presentation cache initializes`() = runBlocking {
        seed(Settings(launchCount = 7))
        val store = SettingsStore(context, AppScope().apply { cancel() })
        assertTrue(store.settingsFlow.value.init)
        store.update { it.copy(developerMode = true) }
        assertEquals(true, persisted()!!.data.first()[SettingsStore.DEVELOPER_MODE])
        assertEquals(7, persisted()!!.data.first()[SettingsStore.LAUNCH_COUNT])
    }

    @Test fun `real quick messages VM retains two additions without a read model emission`() = runTest(main) {
        val store = seed(Settings())
        val vm = QuickMessagesVM(store)
        vm.addQuickMessage("a", "first")
        vm.addQuickMessage("b", "second")
        val saves = vm.viewModelScope.coroutineContext[Job]!!.children.toList().takeLast(2)
        saves.joinAll()
        val messages = JsonInstant.decodeFromString<List<QuickMessage>>(persisted()!!.data.first()[SettingsStore.QUICK_MESSAGES]!!)
        assertEquals(setOf("a", "b"), messages.map { it.title }.toSet())
    }

    @Test fun `real settings VM display edits preserve concurrent unrelated display fields`() = runTest(main) {
        val initial = Settings()
        val store = seed(initial)
        val vm = SettingVM(store)
        store.update { it.copy(displaySetting = it.displaySetting.copy(userNickname = "fresh")) }
        vm.updateDisplaySetting { it.copy(showTokenUsage = true) }.join()
        val display = JsonInstant.decodeFromString<DisplaySetting>(persisted()!!.data.first()[SettingsStore.DISPLAY_SETTING]!!)
        assertEquals("fresh", display.userNickname)
        assertTrue(display.showTokenUsage)
    }

    @Test fun `real settings VM provider draft preserves models learned while draft was open`() = runTest(main) {
        val provider = ProviderSetting.OpenAI(name = "before")
        val initial = Settings(providers = listOf(provider))
        val store = seed(initial)
        val vm = SettingVM(store)
        val learned = Model(modelId = "learned")
        store.update { it.copy(providers = it.providers.map { row -> if (row.id == provider.id) row.copyProvider(models = listOf(learned)) else row }) }
        vm.updateProviderDraft(provider, provider.copy(name = "draft")).join()
        val saved = JsonInstant.decodeFromString<List<ProviderSetting>>(persisted()!!.data.first()[SettingsStore.PROVIDERS]!!).first { it.id == provider.id }
        assertEquals("draft", saved.name)
        assertEquals(listOf(learned.id), saved.models.map { it.id })
    }

    @Test fun `direct keys saved while ordinary I O is paused survive that ordinary save`() = runBlocking {
        val a = Assistant(name = "A")
        val b = Assistant(name = "B")
        val initial = Settings(assistantId = a.id, assistants = listOf(a, b), launchCount = 7)
        seed(initial)
        val barrier = FirstWriteBarrierStore(persisted()!!)
        singletonField().set(null, barrier)
        val store = frozen(initial)
        val update = async(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(developerMode = true) } }
        try {
            withTimeout(5_000) { barrier.firstWriteEntered.await() }
            store.updateAssistant(b.id)
            store.incrementLaunchCount()
            barrier.resumeFirstWrite.complete(Unit)
            withTimeout(5_000) { update.await() }
            val disk = barrier.data.first()
            assertEquals(b.id.toString(), disk[SettingsStore.SELECT_ASSISTANT])
            assertEquals(8, disk[SettingsStore.LAUNCH_COUNT])
            assertEquals(true, disk[SettingsStore.DEVELOPER_MODE])
        } finally {
            barrier.resumeFirstWrite.complete(Unit)
            update.cancelAndJoin()
        }
    }

    @Test fun `rejected transform leaves persisted and published settings untouched`() = runBlocking {
        val store = seed(Settings(launchCount = 7))
        val before = persisted()!!.data.first()
        val published = store.settingsFlow.value
        assertNotNull(runCatching { store.update { error("reject") } }.exceptionOrNull())
        assertEquals(before, persisted()!!.data.first())
        assertEquals(published, store.settingsFlow.value)
    }

    @Test fun `MCP configuration draft retains discovered metadata OAuth and concurrent tool flags`() = runBlocking {
        val tool = McpTool(name = "tool", enable = false, description = "opening")
        val before = McpServerConfig.StreamableHTTPServer(url = "https://example.org/old", commonOptions = McpCommonOptions(name = "opening", tools = listOf(tool)))
        val store = seed(Settings(mcpServers = listOf(before)))
        val oauth = McpOAuthState(enabled = true, clientId = "public-id")
        store.update { it.copy(mcpServers = it.mcpServers.map { row -> row.clone(commonOptions = row.commonOptions.copy(
            oauth = oauth, tools = listOf(tool.copy(description = "discovered", needsApproval = true), McpTool(name = "new-tool")),
        )) }) }
        store.update { it.copy(mcpServers = it.mcpServers.map { row -> applyMcpDraft(row, before, before.copy(
            url = "https://example.org/draft", commonOptions = before.commonOptions.copy(name = "draft", tools = listOf(tool.copy(enable = true))),
        )) }) }
        val saved = JsonInstant.decodeFromString<List<McpServerConfig>>(persisted()!!.data.first()[SettingsStore.MCP_SERVERS]!!).single()
        assertEquals("draft", saved.commonOptions.name)
        assertEquals(oauth, saved.commonOptions.oauth)
        assertEquals(setOf("tool", "new-tool"), saved.commonOptions.tools.map { it.name }.toSet())
        val changed = saved.commonOptions.tools.first { it.name == "tool" }
        assertTrue(changed.enable)
        assertTrue(changed.needsApproval)
        assertEquals("discovered", changed.description)
    }

    @Test fun `model draft changes only dirty fields on current model`() = runBlocking {
        val before = Model(modelId = "m", displayName = "opening")
        val provider = ProviderSetting.OpenAI(models = listOf(before))
        val store = seed(Settings(providers = listOf(provider)))
        val header = CustomHeader("X-Learned", "current")
        store.update { it.copy(providers = it.providers.map { row ->
            if (row.id == provider.id) row.copyProvider(models = listOf(before.copy(customHeaders = listOf(header)))) else row
        }) }
        store.update { it.copy(providers = it.providers.map { row ->
            if (row.id == provider.id) row.copyProvider(models = row.models.map { applyModelDraft(it, before, before.copy(displayName = "draft")) }) else row
        }) }
        val saved = providers().first { it.id == provider.id }.models.single()
        assertEquals("draft", saved.displayName)
        assertEquals(listOf(header), saved.customHeaders)
    }

    @Test fun `identity reorder preserves concurrent row content additions and deletions`() = runBlocking {
        val a = Assistant(name = "A")
        val b = Assistant(name = "B")
        val c = Assistant(name = "C")
        val added = Assistant(name = "added")
        val store = seed(Settings(assistants = listOf(a, b, c)))
        store.update { it.copy(assistants = it.assistants.filterNot { row -> row.id == c.id }.map { row -> if (row.id == b.id) row.copy(name = "fresh B") else row } + added) }
        store.update { it.copy(assistants = moveSettingsItem(it.assistants, a.id, b.id, true) { row -> row.id }) }
        val saved = assistants()
        assertTrue(saved.indexOfFirst { it.id == b.id } < saved.indexOfFirst { it.id == a.id })
        assertEquals("fresh B", saved.first { it.id == b.id }.name)
        assertTrue(saved.any { it.id == added.id })
        assertFalse(saved.any { it.id == c.id })
        store.update { it.copy(assistants = moveSettingsItem(it.assistants, a.id, c.id, false) { row -> row.id }) }
        assertEquals(saved, assistants())
    }

    @Test fun `anonymous header and body leaf operations preserve other fields and guard structural shifts`() = runBlocking {
        val headers = listOf(CustomHeader("A", "old"), CustomHeader("B", "other"))
        val bodies = listOf(CustomBody("a", JsonPrimitive("old")), CustomBody("b", JsonPrimitive("other")))
        val a = Assistant(customHeaders = headers, customBodies = bodies)
        val store = seed(Settings(assistants = listOf(a)))
        fun edit(transform: (Assistant) -> Assistant): (Settings) -> Settings = { settings ->
            settings.copy(assistants = settings.assistants.map { if (it.id == a.id) transform(it) else it })
        }
        store.update(edit { it.copy(customHeaders = updateHeaderValue(it.customHeaders, headers, 0, "fresh"), customBodies = updateBodyValue(it.customBodies, bodies, 0, JsonPrimitive("fresh"))) })
        val pendingHeaders = listOf(headers[0].copy(value = "fresh"), headers[1])
        val pendingBodies = listOf(bodies[0].copy(value = JsonPrimitive("fresh")), bodies[1])
        store.update(edit { it.copy(customHeaders = updateHeaderName(it.customHeaders, pendingHeaders, 0, "renamed"), customBodies = updateBodyKey(it.customBodies, pendingBodies, 0, "renamed")) })
        var saved = assistants().first { it.id == a.id }
        assertEquals(CustomHeader("renamed", "fresh"), saved.customHeaders.first())
        assertEquals(CustomBody("renamed", JsonPrimitive("fresh")), saved.customBodies.first())
        assertEquals(headers[1], saved.customHeaders[1])
        store.update(edit { it.copy(customHeaders = listOf(CustomHeader("inserted", "row")) + it.customHeaders, customBodies = it.customBodies.drop(1)) })
        val shifted = assistants().first { it.id == a.id }
        store.update(edit { it.copy(customHeaders = updateHeaderValue(it.customHeaders, headers, 1, "wrong"), customBodies = updateBodyValue(it.customBodies, bodies, 1, JsonPrimitive("wrong"))) })
        saved = assistants().first { it.id == a.id }
        assertEquals(shifted.copy(customHeaders = shifted.customHeaders.map { if (it.name == "B") it.copy(value = "wrong") else it }, customBodies = shifted.customBodies.map { if (it.key == "b") it.copy(value = JsonPrimitive("wrong")) else it }), saved)
    }

    @Test fun `real backup VM reminder intent preserves background recorded time`() = runTest(main) {
        val store = seed(Settings(backupReminderConfig = BackupReminderConfig(lastBackupTime = 1)))
        val vm = BackupVM(store, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val reminder = vm.updateBackupReminder { it.copy(enabled = true, intervalDays = 14) }
        vm.backupToS3()
        val recorded = JsonInstant.decodeFromString<BackupReminderConfig>(persisted()!!.data.first()[SettingsStore.BACKUP_REMINDER_CONFIG]!!).lastBackupTime
        assertTrue(recorded > 1L)
        reminder.join()
        val saved = JsonInstant.decodeFromString<BackupReminderConfig>(persisted()!!.data.first()[SettingsStore.BACKUP_REMINDER_CONFIG]!!)
        assertEquals(recorded, saved.lastBackupTime)
        assertTrue(saved.enabled)
        assertEquals(14, saved.intervalDays)
        vm.viewModelScope.cancel()
    }

    @Test fun `real assistant VM leaf update retains background skills and unrelated assistants`() = runTest(main) {
        val a = Assistant(name = "opening")
        val b = Assistant(name = "untouched")
        val store = seed(Settings(assistants = listOf(a, b)))
        val vm = assistantVM(a, store)
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) row.copy(enabledSkills = setOf("background-skill")) else row }) }
        vm.update { it.copy(name = "edited") }.join()
        val saved = assistants().first { it.id == a.id }
        assertEquals("edited", saved.name)
        assertEquals(setOf("background-skill"), saved.enabledSkills)
        assertEquals(b, assistants().first { it.id == b.id })
        vm.viewModelScope.cancel()
    }

    @Test fun `assistant file cleanup runs only after a successful real commit`() = runBlocking {
        val avatar = context.filesDir.resolve("old-avatar").apply { writeText("avatar") }
        val background = context.filesDir.resolve("old-background").apply { writeText("background") }
        val a = Assistant(avatar = Avatar.Image(avatar.toUri().toString()), background = background.toUri().toString())
        val store = seed(Settings(assistants = listOf(a)))
        val vm = assistantVM(a, store)
        vm.persistAssistantUpdate { it.copy(avatar = Avatar.Dummy, background = null) }
        assertFalse(avatar.exists())
        assertFalse(background.exists())
        vm.viewModelScope.cancel()

        avatar.writeText("avatar")
        background.writeText("background")
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) a else row }) }
        val actualStore = persisted()!!
        singletonField().set(null, object : DataStore<Preferences> {
            private val delegate = actualStore
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("rejected persistence")
        })
        val rejected = assistantVM(a, frozen(store.settingsFlow.value))
        assertNotNull(runCatching { rejected.persistAssistantUpdate { it.copy(avatar = Avatar.Dummy, background = null) } }.exceptionOrNull())
        assertTrue(avatar.exists())
        assertTrue(background.exists())
        assertEquals(a.avatar, assistants().first { it.id == a.id }.avatar)
        rejected.viewModelScope.cancel()
    }

    @Test fun `transaction decoder retains normalization ordinary secrets and unknown keys`() = runBlocking {
        val model = Model(modelId = "m")
        val provider = ProviderSetting.OpenAI(apiKey = "ordinary-key", models = listOf(model, model), customHeaders = listOf(CustomHeader("Authorization", "ordinary-header")))
        val message = QuickMessage(title = "valid")
        val a = Assistant(quickMessageIds = setOf(message.id, Uuid.random()), mcpServers = setOf(Uuid.random()))
        val asr = ASRProviderSetting.OpenAIRealtime(apiKey = "ordinary-asr")
        val store = seed(Settings(providers = listOf(provider, provider), assistants = listOf(a, a), quickMessages = listOf(message, message),
            asrProviders = listOf(asr, asr), selectedASRProviderId = Uuid.random(), defaultTTSPlaybackSpeed = 3f,
            searchServiceSelected = Int.MAX_VALUE, webServerAccessPassword = "ordinary-password"))
        val unknown = stringPreferencesKey("future_unknown")
        persisted()!!.edit { it[unknown] = "keep" }
        val saved = store.update { it.copy(developerMode = true) }
        assertEquals(1, saved.providers.count { it.id == provider.id })
        assertEquals(1, saved.providers.first { it.id == provider.id }.models.size)
        DEFAULT_PROVIDERS.forEach { builtin -> assertTrue(saved.providers.any { it.id == builtin.id && it.builtIn == builtin.builtIn }) }
        assertEquals(setOf(message.id), saved.assistants.first { it.id == a.id }.quickMessageIds)
        assertTrue(saved.assistants.first { it.id == a.id }.mcpServers.isEmpty())
        assertEquals(asr.id, saved.selectedASRProviderId)
        val disk = persisted()!!.data.first()
        assertEquals(2f, disk[SettingsStore.DEFAULT_TTS_PLAYBACK_SPEED])
        assertTrue(disk[SettingsStore.SEARCH_SELECTED]!! < saved.searchServices.size)
        assertEquals("keep", disk[unknown])
        assertEquals("ordinary-key", (providers().first { it.id == provider.id } as ProviderSetting.OpenAI).apiKey)
        assertEquals("ordinary-password", disk[SettingsStore.WEB_SERVER_ACCESS_PASSWORD])
        assertEquals(listOf(CustomHeader("Authorization", "ordinary-header")), providers().first { it.id == provider.id }.customHeaders)
    }

    @Test fun `real quick message VM draft and deletion retain unrelated current message edits`() = runTest(main) {
        val a = QuickMessage(title = "A", content = "opening")
        val b = QuickMessage(title = "B")
        val assistant = Assistant(quickMessageIds = setOf(a.id, b.id))
        val store = seed(Settings(quickMessages = listOf(a, b), assistants = listOf(assistant)))
        val vm = QuickMessagesVM(store)
        store.update { it.copy(quickMessages = it.quickMessages.map { row -> if (row.id == a.id) row.copy(content = "background") else row }) }
        vm.updateQuickMessage(a, a.copy(title = "draft"))
        vm.deleteQuickMessage(b.id)
        vm.viewModelScope.coroutineContext[Job]!!.children.toList().takeLast(2).joinAll()
        vm.updateQuickMessage(b, b.copy(title = "deleted draft"))
        vm.viewModelScope.coroutineContext[Job]!!.children.toList().takeLast(1).joinAll()
        val saved = JsonInstant.decodeFromString<List<QuickMessage>>(persisted()!!.data.first()[SettingsStore.QUICK_MESSAGES]!!)
        assertEquals(listOf(a.id), saved.map { it.id })
        assertEquals("draft", saved.single().title)
        assertEquals("background", saved.single().content)
        assertEquals(setOf(a.id), assistants().first { it.id == assistant.id }.quickMessageIds)
        vm.viewModelScope.cancel()
    }

    @Test fun `real assistant membership intents retain additions and concurrent removal`() = runTest(main) {
        val one = McpServerConfig.StreamableHTTPServer()
        val two = McpServerConfig.StreamableHTTPServer()
        val removed = McpServerConfig.StreamableHTTPServer()
        val assistant = Assistant(mcpServers = setOf(removed.id))
        val store = seed(Settings(mcpServers = listOf(one, two, removed), assistants = listOf(assistant)))
        val vm = assistantVM(assistant, store)
        val first = vm.update { it.copy(mcpServers = it.mcpServers + one.id) }
        val second = vm.update { it.copy(mcpServers = it.mcpServers + two.id) }
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == assistant.id) row.copy(mcpServers = row.mcpServers - removed.id) else row }) }
        joinAll(first, second)
        assertEquals(setOf(one.id, two.id), assistants().first { it.id == assistant.id }.mcpServers)
        vm.viewModelScope.cancel()
    }

    @Test fun `anonymous row edit and delete preserve an unrelated preceding row edit`() = runBlocking {
        val opening = listOf(CustomHeader("A", "old"), CustomHeader("B", "old"))
        val a = Assistant(customHeaders = opening)
        val store = seed(Settings(assistants = listOf(a)))
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) row.copy(customHeaders = updateHeaderValue(row.customHeaders, opening, 0, "fresh A")) else row }) }
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) row.copy(customHeaders = updateHeaderValue(row.customHeaders, opening, 1, "fresh B")) else row }) }
        assertEquals(listOf("fresh A", "fresh B"), assistants().first { it.id == a.id }.customHeaders.map { it.value })
        val observedForDelete = listOf(opening[0], opening[1].copy(value = "fresh B"))
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) row.copy(customHeaders = deleteHeaderRow(row.customHeaders, observedForDelete, 1)) else row }) }
        assertEquals(listOf(CustomHeader("A", "fresh A")), assistants().first { it.id == a.id }.customHeaders)
    }

    @Test fun `provider type conversion preserves concurrently edited shared fields`() = runBlocking {
        val before = ProviderSetting.OpenAI(name = "opening", apiKey = "opening-key")
        val current = before.copy(name = "fresh", enabled = false, apiKey = "fresh-key", balanceOption = before.balanceOption.copy(apiPath = "/fresh"))
        val requested = before.convertTo(ProviderSetting.Google::class) as ProviderSetting.Google
        val store = seed(Settings(providers = listOf(current)))
        store.update { it.copy(providers = it.providers.map { row -> if (row.id == before.id) applyProviderDraft(row, before, requested) else row }) }
        val saved = providers().first { it.id == before.id } as ProviderSetting.Google
        assertEquals(listOf("fresh", false, "fresh-key", "/fresh"), listOf(saved.name, saved.enabled, saved.apiKey, saved.balanceOption.apiPath))
        assertEquals(requested.baseUrl, saved.baseUrl)
    }

    @Test fun `MCP transport-only draft preserves current unchanged URL`() = runBlocking {
        val before = McpServerConfig.SseTransportServer(url = "https://example.org/opening")
        val current = before.copy(url = "https://example.org/fresh")
        val requested = McpServerConfig.StreamableHTTPServer(id = before.id, url = before.url, commonOptions = before.commonOptions)
        val store = seed(Settings(mcpServers = listOf(current)))
        store.update { it.copy(mcpServers = it.mcpServers.map { row -> applyMcpDraft(row, before, requested) }) }
        val saved = JsonInstant.decodeFromString<List<McpServerConfig>>(persisted()!!.data.first()[SettingsStore.MCP_SERVERS]!!).single() as McpServerConfig.StreamableHTTPServer
        assertEquals(current.url, saved.url)
    }

    @Test fun `anonymous name edit preserves current counterpart value`() = runBlocking {
        val opening = listOf(CustomHeader("A", "old"), CustomHeader("B", "old"))
        val bodies = listOf(CustomBody("a", JsonPrimitive("old")), CustomBody("b", JsonPrimitive("old")))
        val a = Assistant(customHeaders = opening, customBodies = bodies)
        val store = seed(Settings(assistants = listOf(a)))
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) row.copy(
            customHeaders = row.customHeaders.map { header -> if (header.name == "B") header.copy(value = "fresh value") else header },
            customBodies = row.customBodies.map { body -> if (body.key == "b") body.copy(value = JsonPrimitive("fresh value")) else body },
        ) else row }) }
        store.update { it.copy(assistants = it.assistants.map { row -> if (row.id == a.id) row.copy(
            customHeaders = updateHeaderName(row.customHeaders, opening, 1, "renamed"),
            customBodies = updateBodyKey(row.customBodies, bodies, 1, "renamed"),
        ) else row }) }
        val saved = assistants().first { it.id == a.id }
        assertEquals(CustomHeader("renamed", "fresh value"), saved.customHeaders[1])
        assertEquals(CustomBody("renamed", JsonPrimitive("fresh value")), saved.customBodies[1])
    }

    @Test fun `deleted anonymous target never matches another row that reuses its value`() = runBlocking {
        val headers = listOf(CustomHeader("A", "1"), CustomHeader("B", "2"))
        val bodies = listOf(CustomBody("a", JsonPrimitive("1")), CustomBody("b", JsonPrimitive("2")))
        val a = Assistant(customHeaders = listOf(headers[0].copy(value = "2")), customBodies = listOf(bodies[0].copy(value = JsonPrimitive("2"))))
        val store = seed(Settings(assistants = listOf(a)))
        store.update { settings -> settings.copy(assistants = settings.assistants.map { row -> if (row.id == a.id) row.copy(
            customHeaders = updateHeaderName(row.customHeaders, headers, 1, "wrong"),
            customBodies = deleteBodyRow(row.customBodies, bodies, 1),
        ) else row }) }
        assertEquals(a, assistants().first { it.id == a.id })
    }

    @Test fun `real proxy draft VM saves only dirty fields and explicit reset clears current credentials`() = runTest(main) {
        val before = NetworkSetting(proxyUrl = "http://old:80", proxyUsername = "old-user", proxyPassword = "old-password")
        val store = seed(Settings(networkSetting = before))
        val vm = SettingVM(store)
        store.update { it.copy(networkSetting = it.networkSetting.copy(proxyUsername = "fresh-user", proxyPassword = "fresh-password", userAgent = "fresh-agent")) }
        vm.updateProxyDraft(before, before.copy(proxyUrl = "http://new:80")).join()
        val saved = JsonInstant.decodeFromString<NetworkSetting>(persisted()!!.data.first()[SettingsStore.NETWORK_SETTING]!!)
        assertEquals(NetworkSetting(proxyUrl = "http://new:80", proxyUsername = "fresh-user", proxyPassword = "fresh-password", userAgent = "fresh-agent"), saved)
        vm.updateProxyDraft(before, before.copy(proxyUrl = "", proxyUsername = "", proxyPassword = ""), reset = true).join()
        val reset = JsonInstant.decodeFromString<NetworkSetting>(persisted()!!.data.first()[SettingsStore.NETWORK_SETTING]!!)
        assertEquals(listOf("", "", "", "fresh-agent"), listOf(reset.proxyUrl, reset.proxyUsername, reset.proxyPassword, reset.userAgent))
        vm.viewModelScope.cancel()
    }

    @Test fun `queued preset additions derive the automatic role from current assistant messages`() = runTest(main) {
        val a = Assistant()
        val store = seed(Settings(assistants = listOf(a)))
        val vm = assistantVM(a, store)
        val first = me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.USER, parts = emptyList())
        val second = me.rerere.ai.ui.UIMessage(role = me.rerere.ai.core.MessageRole.USER, parts = emptyList())
        val one = vm.update { appendAssistantPresetMessage(it, first) }
        val two = vm.update { appendAssistantPresetMessage(it, second) }
        joinAll(one, two)
        val saved = assistants().first { it.id == a.id }.presetMessages
        assertEquals(listOf(first.id, second.id), saved.map { it.id })
        assertEquals(listOf(me.rerere.ai.core.MessageRole.USER, me.rerere.ai.core.MessageRole.ASSISTANT), saved.map { it.role })
        vm.viewModelScope.cancel()
    }

    @Test fun `serialization failure and dummy result leave persisted and published settings unchanged`() = runBlocking {
        val store = seed(Settings(developerMode = false))
        val disk = persisted()!!.data.first()
        val displayed = store.settingsFlow.value
        val serialization = runCatching { store.update { it.copy(displaySetting = it.displaySetting.copy(fontSizeRatio = Float.NaN)) } }
        assertTrue(serialization.isFailure)
        assertEquals(disk, persisted()!!.data.first())
        assertEquals(displayed, store.settingsFlow.value)
        val dummy = runCatching { store.update { Settings.dummy() } }
        assertTrue(dummy.isFailure)
        assertEquals(disk, persisted()!!.data.first())
        assertEquals(displayed, store.settingsFlow.value)
    }

    private fun assistantVM(a: Assistant, store: SettingsStore): AssistantDetailVM {
        val memories = mockk<MemoryRepository>(relaxed = true)
        every { memories.getMemoriesOfAssistantFlow(any()) } returns flowOf(emptyList())
        every { memories.getGlobalMemoriesFlow() } returns flowOf(emptyList())
        val skills = mockk<SkillManager>(relaxed = true)
        every { skills.listSkills() } returns emptyList()
        val workspaces = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaces.listFlow() } returns flowOf(emptyList())
        val files = FilesManager(context, mockk(relaxed = true), AppScope().apply { cancel() })
        return AssistantDetailVM(a.id.toString(), store, memories, files, skills, workspaces)
    }
    private suspend fun assistants() = JsonInstant.decodeFromString<List<Assistant>>(persisted()!!.data.first()[SettingsStore.ASSISTANTS]!!)
    private suspend fun providers() = JsonInstant.decodeFromString<List<ProviderSetting>>(persisted()!!.data.first()[SettingsStore.PROVIDERS]!!)

    private class FirstWriteBarrierStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val firstWriteEntered = CompletableDeferred<Unit>()
        val resumeFirstWrite = CompletableDeferred<Unit>()
        private val calls = java.util.concurrent.atomic.AtomicInteger()
        override val data = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (calls.incrementAndGet() == 1) {
                firstWriteEntered.complete(Unit)
                resumeFirstWrite.await()
            }
            return delegate.updateData(transform)
        }
    }

    private suspend fun seed(settings: Settings): SettingsStore {
        SettingsStore.restoreBeforeInitialization(context, settings)
        return frozen(settings)
    }
    private fun frozen(settings: Settings) = SettingsStore(context, AppScope().apply { cancel() }).also {
        it.settingsFlow.value = settings
    }
    private fun singletonField() = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
        .getDeclaredField("settingsDataStore").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun persisted() = singletonField().get(null) as? DataStore<Preferences>
}
