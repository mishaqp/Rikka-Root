package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.automation.ExternalAutomationConfig
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

class LocalToolsRegistrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(files: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = File(files, "no-backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(files, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    private val packageFTools = linkedMapOf(
        LocalToolOption.Termux to listOf("termux_run_command", "termux_session_start", "termux_session_send",
            "termux_session_read", "termux_session_kill", "termux_session_list"),
        LocalToolOption.Ssh to listOf("ssh_exec", "save_ssh_host", "list_ssh_hosts", "delete_ssh_host",
            "ssh_forget_host_key", "ssh_exec_saved", "ssh_upload", "ssh_download"),
        LocalToolOption.McpControl to listOf("mcp_list", "mcp_get", "mcp_add", "mcp_update", "mcp_delete",
            "mcp_set_enabled", "mcp_test", "mcp_list_tools", "mcp_set_tool_approval"),
        LocalToolOption.ExternalAutomation to listOf("external_automation_status", "external_automation_set_enabled",
            "external_automation_add_trusted_package", "external_automation_remove_trusted_package"),
    )

    @Test fun eachPackageFFunctionRegistersExactlyItsAgentTools() = withLocalTools { localTools ->
        packageFTools.forEach { (option, expected) ->
            assertEquals(option.toString(), expected, localTools.getTools(listOf(option)).map { it.name })
        }
        val names = localTools.getTools(packageFTools.keys.toList()).map { it.name }
        assertEquals(27, names.size)
        assertEquals(27, names.toSet().size)
        assertEquals(packageFTools.values.flatten().toSet(), names.toSet())
    }

    @Test fun disabledAndDefaultAssistantsExposeNoPackageFTools() = withLocalTools { localTools ->
        assertTrue(localTools.getTools(emptyList()).isEmpty())
        for (assistant in listOf(Assistant(), Json.decodeFromString<Assistant>("{}"))) {
            assertEquals(listOf("get_time_info"), localTools.getTools(assistant.localTools).map { it.name })
        }
    }

    @Test fun existingToolsKeepTheirNamesWhenPackageFIsEnabled() = withLocalTools { localTools ->
        val options = listOf(LocalToolOption.Root, LocalToolOption.JavascriptEngine, LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard, LocalToolOption.Tts, LocalToolOption.AskUser, LocalToolOption.ScreenTime,
            LocalToolOption.Calendar, LocalToolOption.ChartDisplay)
        val expected = setOf("root_exec", "eval_javascript", "get_time_info", "clipboard_tool", "text_to_speech",
            "ask_user", "get_screen_time", "calendar_query", "calendar_create", "chart_display")
        val original = localTools.getTools(options).map { it.name }
        assertEquals(expected, original.toSet())
        assertEquals(expected.size, original.size)
        val combined = localTools.getTools(options + packageFTools.keys).map { it.name }
        assertEquals(expected + packageFTools.values.flatten(), combined.toSet())
        assertEquals(expected.size + 27, combined.size)
    }

    @Test fun allPackageFToolsUseOurDefaultApprovalGateIncludingReads() = withLocalTools { localTools ->
        val empty = Json.parseToJsonElement("{}")
        for (tool in localTools.getTools(packageFTools.keys.toList())) {
            assertTrue(tool.name, tool.name in ToolPermissionPolicy.registry)
            assertTrue(tool.name, ToolApprovalDefaults.requiresApproval(tool.name))
            assertTrue(tool.name, ToolPermissionPolicy.apply(tool).needsApproval(empty))
        }
    }

    /** Real registration dependencies; neither execution, network nor the application startup runs. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun withLocalTools(check: (LocalTools) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = AppScope().apply { cancel() }
        var koinStarted = false
        try {
            val appContext = context(temporary.newFolder("registration"))
            val settings = SettingsStore(appContext, scope)
            val files = FilesManager(appContext, FilesRepository(NoManagedFilesDao()), scope)
            val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val secrets = McpControlSecretStore(File(appContext.noBackupFilesDir, "mcp")) { key }
            val manager = McpManager(settings, scope, files, secrets)
            val ssh = SshHostRepository(NoSshHostsDao(),
                SshCredentialStore(File(appContext.noBackupFilesDir, "ssh.enc"), Json) { key })
            startKoin {
                modules(module {
                    single { manager }
                    single { secrets }
                    single { McpToolSecretSanitizer(secrets) }
                    single { ExternalAutomationConfig(appContext) }
                })
            }
            koinStarted = true
            check(LocalTools(appContext, AppEventBus(), TTSManager(appContext), settings, RootShellManager(),
                RootAccessStore(appContext.noBackupFilesDir), files, TermuxPreferences(appContext), ssh))
        } finally {
            try {
                if (koinStarted) stopKoin()
            } finally {
                scope.cancel()
                runBlocking { scope.coroutineContext[Job]?.join() }
                Dispatchers.resetMain()
            }
        }
    }

    private class NoManagedFilesDao : ManagedFileDAO {
        private fun unexpected(): Nothing = error("Registration must not read or write managed files")
        override suspend fun insert(file: ManagedFileEntity): Long = unexpected()
        override suspend fun update(file: ManagedFileEntity): Unit = unexpected()
        override suspend fun getById(id: Long): ManagedFileEntity? = unexpected()
        override suspend fun getByPath(relativePath: String): ManagedFileEntity? = unexpected()
        override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = unexpected()
        override suspend fun deleteById(id: Long): Int = unexpected()
        override suspend fun deleteByPath(relativePath: String): Int = unexpected()
        override suspend fun deleteByFolder(folder: String): Int = unexpected()
    }

    private class NoSshHostsDao : SshHostDao {
        private fun unexpected(): Nothing = error("Registration must not read or write SSH hosts")
        override suspend fun getAll(): List<SshHostEntity> = unexpected()
        override suspend fun getByName(name: String): SshHostEntity? = unexpected()
        override suspend fun upsert(host: SshHostEntity): Unit = unexpected()
        override suspend fun delete(host: SshHostEntity): Unit = unexpected()
        override suspend fun deleteByName(name: String): Unit = unexpected()
    }

    @Test fun enablingArchivesRegistersExactlyTheThreeNativeZipTools() {
        val tools = scopedFileTools(context(temporary.newFolder()), listOf(LocalToolOption.Archive))

        assertEquals(listOf("zip_files", "unzip_file", "list_zip_contents"), tools.map { it.name })
    }

    @Test fun disabledArchivesAreAbsentEvenWhenFileToolsAreEnabled() {
        val tools = scopedFileTools(context(temporary.newFolder()), listOf(LocalToolOption.Files))

        assertTrue(tools.any { it.name == "list_files" })
        assertTrue(tools.none { it.name in setOf("zip_files", "unzip_file", "list_zip_contents") })
    }

    @Test fun archiveToolsUseTheConversationWorkspaceAndItsCurrentDirectory() = runBlocking {
        val selectedId = Uuid.random()
        val assistantWorkspaceId = Uuid.random()
        val selected = temporary.newFolder("selected-workspace")
        val assistantWorkspace = temporary.newFolder("assistant-workspace")
        File(selected, "project").mkdir()
        File(assistantWorkspace, "project").mkdir()
        File(selected, "project/source.txt").writeText("Из выбранного рабочего пространства")
        File(assistantWorkspace, "project/source.txt").writeText("Из другого рабочего пространства")
        val assistant = Assistant(
            workspaceId = assistantWorkspaceId,
            localTools = listOf(LocalToolOption.Archive),
        )
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            config = ConversationConfig(workspaceId = selectedId),
            workspaceCwd = "/workspace/project",
        )
        val effective = Settings(assistants = listOf(assistant), assistantId = assistant.id)
            .getAssistantOf(conversation)
        val roots = mapOf(selectedId to selected, assistantWorkspaceId to assistantWorkspace)
        val tools = scopedFileTools(
            context(temporary.newFolder("files")),
            effective.localTools,
            workspaceCwd = conversation.workspaceCwd,
            resolveWorkspacePath = { path ->
                File(roots.getValue(requireNotNull(effective.workspaceId)), path.removePrefix("/workspace").trimStart('/'))
            },
        ).associateBy { it.name }

        val zipped = tools.getValue("zip_files").result("""{"sources":["source.txt"],"destination":"archive.zip"}""")
        assertTrue(zipped.toString(), zipped.getValue("success").jsonPrimitive.boolean)
        assertTrue(File(selected, "project/archive.zip").isFile)
        assertFalse(File(assistantWorkspace, "project/archive.zip").exists())

        val listing = tools.getValue("list_zip_contents").result("""{"source":"/workspace/project/archive.zip"}""")
        assertEquals(listOf("source.txt"), listing.getValue("entries").jsonArray.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        })

        val unzipped = tools.getValue("unzip_file").result("""{"source":"archive.zip","destination_dir":"/workspace/restored"}""")
        assertTrue(unzipped.toString(), unzipped.getValue("success").jsonPrimitive.boolean)
        assertEquals("Из выбранного рабочего пространства", File(selected, "restored/source.txt").readText())
        assertFalse(File(assistantWorkspace, "restored").exists())
    }

    private suspend fun Tool.result(input: String): JsonObject {
        val parts = execute(Json.parseToJsonElement(input))
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
}
