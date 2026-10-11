package me.rerere.rikkahub.ui.components.ai.tools

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.automation.ExternalAutomationConfig
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LocalToolMetadataProviderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualFactoryCatalogDoesNotExecuteToolsOrCreateRuntimeDirectories() = withMetadata { metadata, context, database ->
        val before = context.filesDir.walkTopDown().map { it.relativeTo(context.filesDir).path }.toSet()
        val definitions = metadata.toolsForAssistant(Assistant(localTools = emptyList()), Model())

        assertEquals(LocalToolUiRegistry.entries.map { it.option }.toSet(), definitions.keys)
        assertEquals(55, definitions.size)
        assertTrue(definitions.values.all { it.isNotEmpty() })
        assertEquals(listOf("subagent_dispatch", "subagent_list", "subagent_get", "subagent_cancel"),
            definitions.getValue(LocalToolOption.SubAgents).map { it.name })
        assertEquals(7, definitions.getValue(LocalToolOption.CronJobs).size)
        assertTrue(estimateToolTokens(definitions.values.flatten()) > 0L)
        definitions.values.flatten().forEach { tool ->
            val result = runCatching { runBlocking { tool.execute(Json.parseToJsonElement("{}")) } }
            assertEquals(tool.name, "UI metadata cannot execute tools", result.exceptionOrNull()?.message)
        }

        assertFalse("Metadata must not open Room", database.isOpen)
        assertFalse(File(context.filesDir, "tool_scratch").exists())
        assertFalse(File(context.filesDir, "skills").exists())
        assertEquals(before, context.filesDir.walkTopDown().map { it.relativeTo(context.filesDir).path }.toSet())
    }

    @Test fun browserCostUsesOnlyTheActuallyEnabledFunctions() = withMetadata { metadata, _, _ ->
        val assistant = Assistant(localTools = listOf(LocalToolOption.Browser))
        val all = metadata.toolsForAssistant(assistant).getValue(LocalToolOption.Browser)
        val selected = metadata.toolsForAssistant(assistant,
            activeBrowserPreferences = mapOf(BrowserToolDefaults.CURRENT_URL to true))
            .getValue(LocalToolOption.Browser)

        assertEquals(BrowserToolDefaults.ALL_TOOLS, all.map { it.name })
        assertEquals(listOf(BrowserToolDefaults.CURRENT_URL), selected.map { it.name })
        assertTrue(estimateToolTokens(selected) < estimateToolTokens(all))
    }

    private fun withMetadata(block: (LocalToolMetadata, Context, AppDatabase) -> Unit) {
        val root = temp.newFolder("app-files")
        val noBackup = File(root, "no-backup").apply { mkdir() }
        val cache = File(root, "cache").apply { mkdir() }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = root
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getCacheDir(): File = cache
            override fun getApplicationContext(): Context = this
        }
        val scope = AppScope().apply { cancel() }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = SettingsStore(context, scope)
        val files = FilesManager(context, FilesRepository(rejectCalls<ManagedFileDAO>()), scope)
        val skillManager = SkillManager(context, settings)
        val ssh = SshHostRepository(rejectCalls<SshHostDao>(), SshCredentialStore(
            File(noBackup, "ssh.enc"), Json) { error("Metadata must not read SSH secrets") })
        val secretStore = McpControlSecretStore(File(noBackup, "mcp")) {
            error("Metadata must not read MCP secrets")
        }
        val jsSecrets = SkillSecretsStore(context.getSharedPreferences("metadata-test", Context.MODE_PRIVATE)) {
            error("Metadata must not read JS secrets")
        }
        val workflows = WorkflowRepository(rejectCalls<WorkflowDao>(), rejectCalls<WorkflowRunDao>()) { it }
        val cipher = object : CronPayloadCipher {
            override fun seal(jobId: String, plaintext: String): String = error("Metadata must not write cron payloads")
            override fun open(jobId: String, encrypted: String): String = error("Metadata must not read cron payloads")
        }
        val jobs = ScheduledJobRepository(database, cipher)
        val runs = ScheduledJobRunRepository(database)
        startKoin {
            modules(module {
                single { settings }
                single { files }
                single { skillManager }
                single { ssh }
                single { secretStore }
                single { jsSecrets }
                single { workflows }
                single { jobs }
                single { runs }
                single { CronJobScheduler(context, jobs, runs, settings) }
                single { ExternalAutomationConfig(context) }
                single { TTSManager(context) }
            })
        }
        try {
            block(LocalToolMetadata(context), context, database)
        } finally {
            stopKoin()
            database.close()
            scope.cancel()
        }
    }

    private inline fun <reified T> rejectCalls(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Metadata must not access ${T::class.java.simpleName}.${method.name}") } as T
}
