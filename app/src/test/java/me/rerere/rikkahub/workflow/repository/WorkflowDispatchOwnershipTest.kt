package me.rerere.rikkahub.workflow.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.tools.workflowRunTool
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
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class WorkflowDispatchOwnershipTest {
    @get:Rule val folder = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `run tool rejects foreign and missing caller before dispatch but runs the owner's definition`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = AppScope().apply { cancel() }
        val directory = folder.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        startKoin { modules(module { single<Context> { context } }) }
        try {
            val owner = Assistant(localTools = listOf(LocalToolOption.Workflows))
            val settings = SettingsStore(context, scope).also {
                it.settingsFlow.value = Settings(assistants = listOf(owner), assistantId = owner.id)
            }
            val repository = WorkflowRepository(MemoryWorkflowDao(), MemoryRunDao()) { it }
            repository.upsert(WorkflowDefinition("workflow", "Owned workflow", trigger = TriggerSpec.Manual,
                authoringAssistantId = owner.id.toString(), actions = listOf(
                    WorkflowAction("show_toast", buildJsonObject { put("text", "fixture") }))))
            var dispatches = 0
            val engine = WorkflowEngine(repository, settings, ContextProvider(context),
                WorkflowActionRunner { _, _, _ -> dispatches++; HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED) })
            val arguments = buildJsonObject { put("id", "workflow") }
            fun text(parts: List<UIMessagePart>) = parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }

            for (caller in listOf(Uuid.random().toString(), null)) {
                val output = workflowRunTool(engine, repository, caller).execute(arguments)
                assertTrue(text(output).contains("owner_mismatch"))
                assertEquals(0, dispatches)
            }

            val owned = workflowRunTool(engine, repository, owner.id.toString()).execute(arguments)
            assertTrue(text(owned).contains("\"status\":\"SUCCESS\""))
            assertEquals(1, dispatches)
        } finally {
            stopKoin()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
