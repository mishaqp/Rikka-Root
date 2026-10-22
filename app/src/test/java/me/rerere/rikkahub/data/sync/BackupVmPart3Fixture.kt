package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BackupVmPart3Fixture {
    @get:Rule val temporary = TemporaryFolder()
    protected lateinit var context: Context
    protected lateinit var store: SettingsStore
    protected lateinit var owner: Assistant
    protected val webdav = mockk<WebDavSync>(relaxed = true)
    protected val s3 = mockk<S3Sync>(relaxed = true)
    private val models = mutableListOf<BackupVM>()
    @Before fun setupBackupVm() = runBlocking<Unit> {
        val root = temporary.newFolder("application")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        }
        singleton().set(null, null)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        owner = Assistant(name = "Original owner")
        store = SettingsStore(context, AppScope().apply { cancel() })
        store.update { Settings(assistants = listOf(owner), assistantId = owner.id, backupReminderConfig = BackupReminderConfig(lastBackupTime = 1)) }
    }
    protected fun vm(repository: ConversationRepository = mockk(relaxed = true), files: FilesManager = mockk(relaxed = true)) =
        BackupVM(store, webdav, s3, repository, files).also { models += it }
    protected fun preferences(): DataStore<Preferences> = singleton().get(null) as DataStore<Preferences>
    private fun singleton() = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt").getDeclaredField("settingsDataStore").also { it.isAccessible = true }
    protected fun zip(name: String, entries: Map<String, ByteArray>): File = temporary.newFile(name).also { f ->
        ZipOutputStream(f.outputStream()).use { out -> entries.forEach { (path, bytes) -> out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry() } }
    }
    protected fun cherry(key: String = "DUMMY_API_KEY"): File {
        val llm = buildJsonObject { put("providers", buildJsonArray { add(buildJsonObject {
            put("type", "openai"); put("name", "Imported"); put("apiHost", "https://example.invalid"); put("apiKey", key); put("models", buildJsonArray {})
        }) }) }
        val persisted = buildJsonObject { put("llm", llm.toString()) }
        val data = buildJsonObject { put("localStorage", buildJsonObject { put("persist:cherry-studio", persisted.toString()) }) }
        return zip("cherry-${System.nanoTime()}.zip", mapOf("data.json" to data.toString().encodeToByteArray()))
    }
    @After fun cleanupBackupVm() = runBlocking {
        models.forEach { it.viewModelScope.cancel() }
        singleton().get(null)?.let { current ->
            var delegate: Any = current
            while (delegate.javaClass.declaredFields.none { it.name == "scope" }) delegate = delegate.javaClass.getDeclaredField("delegate").also { it.isAccessible = true }.get(delegate) as Any
            val scope = delegate.javaClass.getDeclaredField("scope").also { it.isAccessible = true }.get(delegate) as CoroutineScope
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        singleton().set(null, null); Dispatchers.resetMain()
    }
}
