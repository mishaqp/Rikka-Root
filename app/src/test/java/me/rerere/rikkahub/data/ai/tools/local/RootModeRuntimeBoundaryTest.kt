package me.rerere.rikkahub.data.ai.tools.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootInvocation
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.service.HeadlessRunJournal
import me.rerere.rikkahub.service.HeadlessRuntimeBindings
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.service.HeadlessToolAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RootModeRuntimeBoundaryTest {
    @get:Rule val folder = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { stopKoin(); Dispatchers.resetMain() }

    @Test fun promotedRootExecFromActualLocalToolsReachesTheOwnedCommandBoundary() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            val tool = f.localRoot()
            val output = withContext(RootInvocation(automatic = false, automaticAllowed = { false })) {
                tool.execute(command("id -u"))
            }
            assertEquals("0\n", envelope(output)["stdout"]?.jsonPrimitive?.content)
            assertEquals(1, f.effects)
        }
    }

    @Test fun factoryAdvertisedRootExecActuallyExecutesWithTheSameEffectiveOptions() = runBlocking {
        Fixture(folder.newFolder()).use { f ->
            val tools = f.factory.createTools(f.live.value, f.owner, f.model)
            assertEquals(listOf("root_exec"), tools.map { it.name })
            val output = withContext(RootInvocation(automatic = false, automaticAllowed = { false })) {
                tools.single().execute(command("id -u"))
            }
            assertEquals("0\n", envelope(output)["stdout"]?.jsonPrimitive?.content)
            assertEquals(1, f.effects)
        }
    }

    @Test fun headlessDirectRootUsesTheSameLiveRootModeCapability() = runBlocking {
        Fixture(folder.newFolder(), workflow = true).use { f ->
            val result = f.runner().run(HeadlessTaskRequest.DirectActions(
                listOf(HeadlessToolAction("root_exec", command("id -u")))), f.execution)
            assertEquals(HeadlessTaskStatus.SUCCEEDED, result.status)
            assertEquals(1, f.effects)
        }
    }

    @Test fun headlessStillRequiresThePreparedRootTransport() = runBlocking {
        Fixture(folder.newFolder(), workflow = true).use { f ->
            f.prepared = false
            assertEquals("root_interaction_required", f.bindings.preflight(f.execution, "root_exec"))
            assertEquals(0, f.effects)
        }
    }

    @Test fun cachedPromotedToolCannotSurviveLiveRootModeRevocation() = runBlocking {
        Fixture(folder.newFolder(), workflow = true).use { f ->
            val cached = f.localRoot()
            f.live.value = f.live.value.copy(assistants = listOf(f.owner.copy(rootMode = false)))
            assertTrue(envelope(cached.execute(command("id -u")))["error"]!!.jsonPrimitive.content.contains("отключена"))
            assertEquals("tool_feature_disabled", f.bindings.preflight(f.execution, "root_exec"))
            assertEquals(0, f.effects)
        }
    }

    @Test fun definiteRootFailureDoesNotGrantAnUnselectedRootCapability() = runBlocking {
        Fixture(folder.newFolder(), workflow = true).use { f ->
            val cached = f.localRoot()
            f.status.value = RootStatus.NOT_ROOT
            assertTrue(envelope(cached.execute(command("id -u")))["error"]!!.jsonPrimitive.content.contains("отключена"))
            assertEquals("tool_feature_disabled", f.bindings.preflight(f.execution, "root_exec"))
            assertEquals(0, f.effects)
        }
    }

    @Test fun rootModeDoesNotBypassProtectedAutomaticCommandsOrCreateWorkflowPermission() = runBlocking {
        Fixture(folder.newFolder(), workflow = true, explicitRoot = true).use { f ->
            val tool = f.localRoot()
            assertTrue(tool.needsApproval(command("reboot bootloader")))
            val output = withContext(f.execution + RootInvocation(automatic = true, automaticAllowed = { true })) {
                tool.execute(command("rm -rf /system"))
            }
            assertEquals("root_command_blocked", envelope(output)["error"]?.jsonPrimitive?.content)
            f.live.value = f.live.value.copy(assistants = listOf(f.owner.copy(localTools = listOf(LocalToolOption.Root))))
            assertEquals("feature_disabled", f.bindings.preflight(f.execution, "root_exec"))
            assertEquals(0, f.effects)
        }
    }

    private class Fixture(directory: File, workflow: Boolean = false, explicitRoot: Boolean = false) : Closeable {
        private val context = ApplicationProvider.getApplicationContext<Application>()
        val model = Model(modelId = "root-mode-runtime")
        val owner = Assistant(rootMode = true, chatModelId = model.id, localTools = buildList {
            add(LocalToolOption.TimeInfo)
            if (workflow) add(LocalToolOption.Workflows)
            if (explicitRoot) add(LocalToolOption.Root)
        })
        val live = MutableStateFlow(Settings(assistants = listOf(owner), assistantId = owner.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model)))))
        private val store = mockk<SettingsStore> { every { settingsFlow } returns live }
        val status = MutableStateFlow(RootStatus.READY)
        var effects = 0
        var prepared = true
        private val manager = mockk<RootShellManager> {
            every { status } returns this@Fixture.status
            every { isHeadlessReady() } answers { prepared }
        }
        private val access = RootAccessStore(File(directory, "root-access").apply { mkdirs() })
        private val grants = ToolApprovalTestStore(File(directory, "grants").apply { mkdirs() })
        private val local = LocalTools(context, mockk(), mockk(), store, manager, access, mockk(), mockk(), mockk())
        val bindings = HeadlessRuntimeBindings(context, store, grants.preferences, access, mockk(), manager, mockk(), mockk())
        val execution = RunExecutionContext(Uuid.random(), owner.id, RunOrigin.WORKFLOW, modelId = model.id.toString())
        private val mcp = mockk<McpManager> {
            every { getAllAvailableTools(any(), any()) } returns emptyList()
        }
        val factory = ChatToolFactory(Json, mockk(), mockk(), local, mcp, mockk(), mockk(), access,
            grants.preferences, bindings, { error("Subagents disabled") }, mockk())

        init {
            startKoin { modules(module { single { manager } }) }
            coEvery { manager.exec(any(), any(), any(), any()) } coAnswers {
                assertTrue("The real RootTool must pass its live authorization into the boundary",
                    arg<suspend () -> Boolean>(3).invoke())
                effects++
                RootProcessResult(exitCode = 0, stdout = "0\n")
            }
            runBlocking { access.setAutoApprove(true); grants.preferences.grantAlways("root_exec") }
        }

        fun localRoot(): Tool = local.getTools(
            RootMode.effectiveOptions(owner.localTools, owner.rootMode, status.value).filter { it == LocalToolOption.Root },
            assistantId = owner.id.toString(), callerAssistant = owner,
        ).single { it.name == "root_exec" }

        fun runner() = HeadlessTaskRunner(mockk(), { _, _, _, _ -> listOf(localRoot()) }, { live.value },
            object : HeadlessRunJournal {
                override suspend fun claim(runId: String) = true
                override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) { }
            }, bindings::authorize, bindings::preflight, bindings::featureEnabled)

        override fun close() { grants.close(); stopKoin() }
    }

    companion object {
        private fun command(value: String) = buildJsonObject { put("command", value) }
        private fun envelope(output: List<UIMessagePart>): JsonObject =
            Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }
}
