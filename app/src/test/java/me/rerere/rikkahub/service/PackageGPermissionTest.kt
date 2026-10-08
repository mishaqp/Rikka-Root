package me.rerere.rikkahub.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.automation.ExternalAutomationConfig
import me.rerere.rikkahub.costguards.TokenBudgetStore
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import kotlin.uuid.Uuid

/** Real permission bindings/runner; the effect seam counts whether a protected action launches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PackageGPermissionTest {
    @get:Rule val folder = TemporaryFolder()
    private val origins = listOf(RunOrigin.WORKFLOW, RunOrigin.SKILL_TEST)
    private val empty = buildJsonObject { }

    @Test fun `workflow and skill test never inherit creator chat grants`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            ToolApprovalAllowList.grantForChat(f.creator, "read_file")
            try {
                val denied = f.run("read_file")
                assertEquals(origin.name, HeadlessTaskStatus.BLOCKED, denied.status)
                assertEquals("approval_required", denied.errorCode)
                assertEquals(0, f.effects)
                f.grants.preferences.grantAlways("read_file")
                assertEquals(HeadlessTaskStatus.SUCCEEDED, f.run("read_file").status)
                assertEquals(1, f.effects)
            } finally { ToolApprovalAllowList.clearChat(f.creator) }
        }
    }

    @Test fun `device grant revoked at durable checkpoint prevents the actual effect in both origins`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            f.grants.preferences.grantAlways("read_file")
            f.onCheckpoint = { f.grants.preferences.revoke("read_file") }
            val result = f.run("read_file")
            assertEquals(origin.name, HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals("approval_required", result.errorCode)
            assertEquals(1, f.checkpoints)
            assertEquals(0, f.effects)
        }
    }

    @Test fun `Android permission revoked at checkpoint is rechecked by production bindings`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            val application = RuntimeEnvironment.getApplication() as Application
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            f.grants.preferences.grantAlways("get_location")
            f.onCheckpoint = {
                shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            val result = f.run("get_location")
            assertEquals(origin.name, HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals("android_permission_required", result.errorCode)
            assertEquals(1, f.checkpoints)
            assertEquals(0, f.effects)
        }
    }

    @Test fun `live feature removal wins over tools created before the checkpoint`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            f.grants.preferences.grantAlways("run_js")
            f.onCheckpoint = {
                val removed = if (origin == RunOrigin.WORKFLOW) LocalToolOption.Workflows else LocalToolOption.JsSkills
                f.settings.settingsFlow.value = f.settings.settingsFlow.value.copy(
                    assistants = listOf(f.owner.copy(localTools = f.owner.localTools - removed)))
            }
            val result = f.run("run_js")
            assertEquals(origin.name, HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals(if (origin == RunOrigin.WORKFLOW) "feature_disabled" else "tool_feature_disabled", result.errorCode)
            assertEquals(0, f.effects)
        }
    }

    @Test fun `browser writes and skill installs remain manual despite YOLO and Always Allow`() = runBlocking {
        val names = listOf("browser_click", "browser_type", "browser_submit", "browser_click_and_read",
            "skill_install_from_url", "skill_install_from_text")
        for (origin in origins) fixture(origin).use { f ->
            f.grants.preferences.setYolo(true)
            for (name in names) {
                f.grants.preferences.grantAlways(name)
                assertTrue(ToolPermissionPolicy.mandatoryConfirmation(name, empty))
                assertFalse(ToolPermissionPolicy.canGrantAlways(name, empty))
                val result = f.run(name)
                assertEquals("${origin.name}:$name", HeadlessTaskStatus.BLOCKED, result.status)
                assertEquals("mandatory_confirmation", result.errorCode)
                assertEquals(0, f.effects)
            }
        }
    }

    @Test fun `G unattended origins enforce root shell forbidden and protected commands before launch`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            f.grants.preferences.setYolo(true)
            for (name in listOf("root_exec", "ssh_exec", "termux_run_command")) {
                val forbidden = f.run(name, buildJsonObject { put("command", "rm -rf /system") })
                assertEquals("${origin.name}:$name", HeadlessTaskStatus.BLOCKED, forbidden.status)
                assertEquals("root_command_blocked", forbidden.errorCode)
                val protected = f.run(name, buildJsonObject { put("command", "setenforce 0") })
                assertEquals(HeadlessTaskStatus.BLOCKED, protected.status)
                assertEquals("mandatory_confirmation", protected.errorCode)
            }
            assertEquals(0, f.effects)
            assertEquals(0, f.checkpoints)
        }
    }

    @Test fun `screen automation headless preflight requires a foreground-prepared root transport`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            f.grants.preferences.setYolo(true)
            val result = f.run("read_window_tree")
            assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals("root_interaction_required", result.errorCode)
            assertEquals(0, f.effects)
            assertEquals(0, f.checkpoints)
        }
    }

    @Test fun `saved workflow modifications and recursive starts never run headless`() = runBlocking {
        for (origin in origins) fixture(origin).use { f ->
            f.grants.preferences.setYolo(true)
            for (name in listOf("workflow_create", "workflow_update", "workflow_set_enabled", "workflow_run", "workflow_delete")) {
                val result = f.run(name)
                assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
                assertEquals("mandatory_confirmation", result.errorCode)
            }
            assertEquals(0, f.effects)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fixture(origin: RunOrigin): Fixture {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        return Fixture(folder.newFolder(), origin)
    }

    private class Fixture(directory: File, private val origin: RunOrigin) : Closeable {
        val scope = AppScope().apply { cancel() }
        val creator = Uuid.random()
        val model = Model(modelId = "test-headless-g")
        val owner = Assistant(chatModelId = model.id, localTools = listOf(LocalToolOption.Workflows,
            LocalToolOption.Files, LocalToolOption.Location, LocalToolOption.Browser, LocalToolOption.SkillImport,
            LocalToolOption.JsSkills, LocalToolOption.ScreenAutomation, LocalToolOption.Root, LocalToolOption.Ssh, LocalToolOption.Termux))
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        val settings = SettingsStore(context, scope).also { it.settingsFlow.value = Settings(
            assistants = listOf(owner), assistantId = owner.id, providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))) }
        val grants = ToolApprovalTestStore(File(directory, "grants").apply { mkdirs() })
        var effects = 0
        var checkpoints = 0
        var onCheckpoint: suspend () -> Unit = { }
        private val workspaceManager = WorkspaceManager(File(directory, "workspaces"))
        private val workspaceDao = WorkspaceDAO::class.java.cast(Proxy.newProxyInstance(WorkspaceDAO::class.java.classLoader,
            arrayOf(WorkspaceDAO::class.java)) { _, method, _ -> error("No workspace required: ${method.name}") })
        private val bindings = HeadlessRuntimeBindings(context, settings, grants.preferences,
            RootAccessStore(context.noBackupFilesDir), WorkspaceRepository(workspaceDao, workspaceManager,
                RootfsInstaller(workspaceManager), settings), RootShellManager(suExecutable = "/must-not-launch-su"),
            TokenBudgetStore(File(directory, "budgets")), ExternalAutomationConfig(context))
        private val journal = object : HeadlessRunJournal {
            val claimed = mutableSetOf<String>()
            override suspend fun claim(runId: String): Boolean = claimed.add(runId)
            override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) {
                check(runId in claimed)
                check(toolCallIds.isNotEmpty())
                checkpoints++
                onCheckpoint()
            }
        }
        private val client = OkHttpClient.Builder().addInterceptor { error("Direct G checks cannot call a provider") }.build()
        suspend fun run(name: String, arguments: JsonElement = buildJsonObject { }): HeadlessTaskResult {
            val runner = HeadlessTaskRunner(
                GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true }),
                toolSource = { _, _, _, _ -> listOf(Tool(name, "fixture effect", needsApproval = { true }, execute = {
                    effects++
                    listOf(UIMessagePart.Text("done"))
                })) },
                settings = { settings.settingsFlow.value }, journal = journal,
                authorize = bindings::authorize, preflight = bindings::preflight, featureEnabled = bindings::featureEnabled,
            )
            return runner.run(HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction(name, arguments))),
                RunExecutionContext(Uuid.random(), owner.id, origin, callerConversationId = creator,
                    modelId = model.id.toString(), maxSteps = 1))
        }
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun close() {
            grants.close()
            scope.cancel()
            runBlocking { scope.coroutineContext[Job]?.join() }
            Dispatchers.resetMain()
        }
    }
}
