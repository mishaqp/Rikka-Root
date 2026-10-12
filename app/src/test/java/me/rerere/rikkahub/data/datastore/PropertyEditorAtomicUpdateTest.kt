package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRequestContent
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual property controls, actual VM and actual Preferences transactions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "ru-rRU-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PropertyEditorAtomicUpdateTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: SettingsStore
    private lateinit var gate: GateStore
    private lateinit var vm: AssistantDetailVM
    private val assistant = Assistant()
    private val saves = mutableListOf<Job>()

    @Before fun setup() = runBlocking {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("no-backup")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
        }
        singletonField().set(null, null)
        val initial = Settings(assistants = listOf(assistant))
        SettingsStore.restoreBeforeInitialization(context, initial)
        gate = GateStore(persisted())
        singletonField().set(null, gate)
        store = SettingsStore(context, AppScope().apply { cancel() }).also { it.settingsFlow.value = initial }
        val memories = mockk<MemoryRepository>(relaxed = true)
        every { memories.getMemoriesOfAssistantFlow(any()) } returns flowOf(emptyList())
        every { memories.getGlobalMemoriesFlow() } returns flowOf(emptyList())
        val skills = mockk<SkillManager>(relaxed = true)
        every { skills.listSkills() } returns emptyList()
        val workspaces = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaces.listFlow() } returns flowOf(emptyList())
        Dispatchers.setMain(Dispatchers.Unconfined)
        vm = AssistantDetailVM(assistant.id.toString(), store, memories,
            FilesManager(context, mockk(relaxed = true), AppScope().apply { cancel() }), skills, workspaces)
    }

    @After fun cleanup() {
        gate.releaseAll()
        compose.waitUntil(5_000) { saves.all { it.isCompleted } }
        vm.viewModelScope.cancel()
        var delegate: Any = persisted()
        while (delegate.javaClass.declaredFields.none { it.name == "scope" }) {
            delegate = delegate.javaClass.getDeclaredField("delegate").also { it.isAccessible = true }.get(delegate) as Any
        }
        val scope = delegate.javaClass.getDeclaredField("scope").also { it.isAccessible = true }.get(delegate) as CoroutineScope
        runBlocking { withTimeout(5_000) { scope.coroutineContext[Job]?.cancelAndJoin() } }
        singletonField().set(null, null)
        Dispatchers.resetMain()
    }

    @Test fun `two added blank header rows remain editable and fast typing survives paused persistence`() {
        compose.setContent {
            val settings by store.settingsFlow.collectAsState()
            MaterialTheme {
                AssistantRequestContent(PaddingValues(), settings.assistants.first { it.id == assistant.id }) { saves += vm.update(it) }
            }
        }
        val add = compose.onNodeWithText("Добавить заголовок")
        add.performClick()
        compose.waitUntil(5_000) { displayed().customHeaders.size == 1 }
        add.performClick()
        compose.waitUntil(5_000) { displayed().customHeaders.size == 2 }
        compose.waitForIdle()
        gate.holdNext.set(true)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("a")
        compose.waitUntil(5_000) { gate.entered.isCompleted }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("b")
        assertEquals("", runBlocking { diskAssistant().customHeaders[0].name })
        gate.resume.complete(Unit)
        compose.waitUntil(5_000) { displayed().customHeaders[0].name == "ab" }
        val saved = runBlocking { diskAssistant() }
        assertEquals("ab", saved.customHeaders[0].name)
        assertEquals("", saved.customHeaders[1].name)
        assertEquals(2, saved.customHeaders.size)
    }

    @Test fun `queued edits of two visible rows preserve both row values`() {
        runBlocking { store.update { settings -> settings.copy(assistants = settings.assistants.map { row ->
            if (row.id == assistant.id) row.copy(customHeaders = listOf(me.rerere.ai.provider.CustomHeader("A", ""), me.rerere.ai.provider.CustomHeader("B", ""))) else row
        }) } }
        compose.setContent {
            val settings by store.settingsFlow.collectAsState()
            MaterialTheme {
                AssistantRequestContent(PaddingValues(), settings.assistants.first { it.id == assistant.id }) { saves += vm.update(it) }
            }
        }
        gate.holdNext.set(true)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("a")
        compose.waitUntil(5_000) { gate.entered.isCompleted }
        compose.onAllNodes(hasSetTextAction())[3].performTextInput("b")
        gate.resume.complete(Unit)
        compose.waitUntil(5_000) { saves.all { it.isCompleted } }
        val saved = runBlocking { diskAssistant() }
        assertEquals(listOf("a", "b"), saved.customHeaders.map { it.value })
    }

    @Test fun `queued edits across two blank rows preserve both names`() {
        runBlocking { store.update { settings -> settings.copy(assistants = settings.assistants.map { row ->
            if (row.id == assistant.id) row.copy(customHeaders = listOf(me.rerere.ai.provider.CustomHeader("", ""), me.rerere.ai.provider.CustomHeader("", ""))) else row
        }) } }
        compose.setContent {
            val settings by store.settingsFlow.collectAsState()
            MaterialTheme {
                AssistantRequestContent(PaddingValues(), settings.assistants.first { it.id == assistant.id }) { saves += vm.update(it) }
            }
        }
        gate.holdNext.set(true)
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("a")
        compose.waitUntil(5_000) { gate.entered.isCompleted }
        compose.onAllNodes(hasSetTextAction())[2].performTextInput("b")
        gate.resume.complete(Unit)
        compose.waitUntil(5_000) { saves.all { it.isCompleted } }
        assertEquals(listOf("a", "b"), runBlocking { diskAssistant().customHeaders.map { it.name } })
    }

    @Test fun `third keystroke retains pending text after only the first commit emits`() {
        runBlocking { store.update { settings -> settings.copy(assistants = settings.assistants.map { row ->
            if (row.id == assistant.id) row.copy(customHeaders = listOf(me.rerere.ai.provider.CustomHeader("A", ""))) else row
        }) } }
        compose.setContent {
            val settings by store.settingsFlow.collectAsState()
            MaterialTheme {
                AssistantRequestContent(PaddingValues(), settings.assistants.first { it.id == assistant.id }) { saves += vm.update(it) }
            }
        }
        val first = gate.pauseNext()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("a")
        compose.waitUntil(5_000) { first.first.isCompleted }
        val second = gate.pauseNext()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("b")
        first.second.complete(Unit)
        compose.waitUntil(5_000) { second.first.isCompleted && displayed().customHeaders[0].value == "a" }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("c")
        second.second.complete(Unit)
        compose.waitUntil(5_000) { saves.all { it.isCompleted } }
        assertEquals("abc", runBlocking { diskAssistant().customHeaders.single().value })
    }

    @Test fun `queued deletion of row B retains an unrelated edit of row A`() {
        runBlocking { store.update { settings -> settings.copy(assistants = settings.assistants.map { row ->
            if (row.id == assistant.id) row.copy(customHeaders = listOf(me.rerere.ai.provider.CustomHeader("A", ""), me.rerere.ai.provider.CustomHeader("B", ""))) else row
        }) } }
        compose.setContent {
            val settings by store.settingsFlow.collectAsState()
            MaterialTheme {
                AssistantRequestContent(PaddingValues(), settings.assistants.first { it.id == assistant.id }) { saves += vm.update(it) }
            }
        }
        gate.holdNext.set(true)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("fresh")
        compose.waitUntil(5_000) { gate.entered.isCompleted }
        compose.onAllNodesWithContentDescription("Удалить заголовок")[1].performClick()
        gate.resume.complete(Unit)
        compose.waitUntil(5_000) { saves.all { it.isCompleted } }
        assertEquals(listOf(me.rerere.ai.provider.CustomHeader("A", "fresh")), runBlocking { diskAssistant().customHeaders })
    }

    private fun displayed() = store.settingsFlow.value.assistants.first { it.id == assistant.id }
    private suspend fun diskAssistant() = JsonInstant.decodeFromString<List<Assistant>>(persisted().data.first()[SettingsStore.ASSISTANTS]!!).first { it.id == assistant.id }
    private fun singletonField() = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
        .getDeclaredField("settingsDataStore").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun persisted() = singletonField().get(null) as DataStore<Preferences>

    private class GateStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val holdNext = AtomicBoolean(false)
        @Volatile var entered = CompletableDeferred<Unit>()
        @Volatile var resume = CompletableDeferred<Unit>()
        private val releases = mutableListOf(resume)
        fun pauseNext(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
            entered = CompletableDeferred()
            resume = CompletableDeferred()
            releases += resume
            holdNext.set(true)
            return entered to resume
        }
        fun releaseAll() { releases.forEach { it.complete(Unit) } }
        override val data = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (holdNext.compareAndSet(true, false)) {
                val enteredNow = entered
                val resumeNow = resume
                enteredNow.complete(Unit)
                resumeNow.await()
            }
            return delegate.updateData(transform)
        }
    }
}
