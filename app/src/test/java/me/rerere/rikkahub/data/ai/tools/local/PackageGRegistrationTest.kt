package me.rerere.rikkahub.data.ai.tools.local

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
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolPermissionPolicy
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.skills.SkillUrlImporter
import me.rerere.rikkahub.skills.js.JsSkillRunner
import me.rerere.rikkahub.skills.js.SkillSecretsStore
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.*
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
import javax.crypto.spec.SecretKeySpec

/** Exercise the model-facing LocalTools dispatcher, including its real preference gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PackageGRegistrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val options = listOf(LocalToolOption.Browser, LocalToolOption.SkillImport, LocalToolOption.JsSkills,
        LocalToolOption.ScreenAutomation, LocalToolOption.Workflows)

    @Test fun `new and restored assistants leave G absent even when browser read preferences are on`() = fixture { local, _ ->
        for (assistant in listOf(Assistant(), Json.decodeFromString<Assistant>("{}"))) {
            assertTrue(assistant.localTools.none { it in options })
            assertEquals(listOf("get_time_info"), local.getTools(assistant.localTools).map { it.name })
        }
        assertTrue(local.getTools(emptyList()).isEmpty())
    }

    @Test fun `master browser and per-tool preference both gate real model registration`() = fixture { local, browser ->
        val on = { local.getTools(listOf(LocalToolOption.Browser)).map { it.name }.toSet() }
        assertTrue("browser_get_text" in on())
        assertFalse("browser_click" in on())
        runBlocking { browser.setToolEnabled("browser_get_text", false) }
        assertFalse("browser_get_text" in on())
        runBlocking { browser.setToolEnabled("browser_get_text", true); browser.setToolEnabled("browser_click", true) }
        assertTrue("browser_get_text" in on())
        assertTrue("browser_click" in on())
        assertTrue(local.getTools(emptyList()).none { it.name.startsWith("browser_") })
        val click = local.getTools(listOf(LocalToolOption.Browser)).single { it.name == "browser_click" }
        assertTrue(ToolPermissionPolicy.apply(click).needsApproval(Json.parseToJsonElement("{\"selector\":\"button\"}")))
        assertTrue(ToolPermissionPolicy.mandatoryConfirmation(click.name, Json.parseToJsonElement("{}")))
    }

    @Test fun `restored unsafe browser preferences cannot re-expose unrestricted secret surfaces`() = fixture { local, browser ->
        runBlocking {
            browser.setToolEnabled("browser_eval_js", true)
            browser.setToolEnabled("browser_get_dom", true)
            browser.setToolEnabled("browser_screenshot", true)
        }
        val names = local.getTools(listOf(LocalToolOption.Browser)).map { it.name }.toSet()
        assertTrue("browser_get_text" in names)
        assertTrue("browser_current_url" in names)
        assertFalse("browser_eval_js" in names)
        assertFalse("browser_get_dom" in names)
        assertFalse("browser_screenshot" in names)
    }

    @Test fun `enabled G exposes its critical Agent tools once through LocalTools`() = fixture { local, _ ->
        val assistant = Assistant(localTools = options)
        val tools = local.getTools(options, assistantId = assistant.id.toString(), callerAssistant = assistant)
        val names = tools.map { it.name }
        for (critical in listOf("browser_get_text", "browser_current_url", "browser_get_links", "skill_install_from_url", "skill_install_from_text",
            "run_js", "read_window_tree", "find_node", "click_node", "set_text", "take_screenshot",
            "workflow_create", "workflow_get", "workflow_run")) {
            assertEquals(critical, 1, names.count { it == critical })
        }
        assertEquals("Enabled subsystems must not introduce duplicate names", names.size, names.toSet().size)
        val args = Json.parseToJsonElement("{}")
        tools.forEach { tool ->
            assertTrue(tool.name, tool.name in ToolPermissionPolicy.registry)
            assertTrue(tool.name, ToolApprovalDefaults.requiresApproval(tool.name))
            assertTrue(tool.name, ToolPermissionPolicy.apply(tool).needsApproval(args))
        }
    }

    @Test fun `enabling screen does not activate browser skills or workflows`() = fixture { local, _ ->
        val tools = local.getTools(listOf(LocalToolOption.ScreenAutomation)).map { it.name }
        assertTrue("read_window_tree" in tools)
        assertTrue("tap" in tools)
        assertTrue(tools.none { it.startsWith("browser_") || it.startsWith("workflow_") || it.startsWith("skill_install_") || it == "run_js" })
    }

    @Test fun `G option backup names restore the exact Agent serial names`() {
        for ((name, option) in listOf("browser" to LocalToolOption.Browser, "skill_import" to LocalToolOption.SkillImport,
            "js_skills" to LocalToolOption.JsSkills, "screen_automation" to LocalToolOption.ScreenAutomation,
            "workflows" to LocalToolOption.Workflows)) {
            val backup = "{\"type\":\"$name\"}"
            assertEquals(option, Json.decodeFromString<LocalToolOption>(backup))
            assertEquals(backup, Json.encodeToString<LocalToolOption>(option))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fixture(check: (LocalTools, BrowserPreferences) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = AppScope().apply { cancel() }
        var started = false
        try {
            val dir = folder.newFolder()
            val application: Context = RuntimeEnvironment.getApplication()
            val context = object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = dir
                override fun getNoBackupFilesDir(): File = File(dir, "no-backup").apply { mkdirs() }
                override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
            }
            val settings = SettingsStore(context, scope)
            val files = FilesManager(context, FilesRepository(noDataAccess(ManagedFileDAO::class.java)), scope)
            val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val ssh = SshHostRepository(noDataAccess(SshHostDao::class.java),
                SshCredentialStore(File(context.noBackupFilesDir, "ssh.enc"), Json) { key })
            val browser = BrowserPreferences(context)
            val skills = SkillManager(context, settings)
            val workflows = WorkflowRepository(noDataAccess(WorkflowDao::class.java), noDataAccess(WorkflowRunDao::class.java)) { it }
            val engine = WorkflowEngine(workflows, settings, ContextProvider(context),
                WorkflowActionRunner { _, _, _ -> error("Registration cannot execute a workflow") })
            startKoin { modules(module {
                single { skills }
                single { SkillUrlImporter(skills) }
                single { JsSkillRunner(context) }
                single { SkillSecretsStore(context) }
                single { workflows }
                single { engine }
            }) }
            started = true
            check(LocalTools(context, AppEventBus(), TTSManager(context), settings, RootShellManager(),
                RootAccessStore(context.noBackupFilesDir), files, TermuxPreferences(context), ssh, browser), browser)
        } finally {
            if (started) stopKoin()
            scope.cancel()
            runBlocking { scope.coroutineContext[Job]?.join() }
            Dispatchers.resetMain()
        }
    }

    private fun <T> noDataAccess(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        error("Registration must not access Room: ${type.simpleName}.${method.name}")
    })
}
