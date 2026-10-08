package me.rerere.rikkahub.workflow.execution

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.RunWebTaint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import me.rerere.rikkahub.service.HeadlessRunJournal
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessToolAction
import me.rerere.rikkahub.service.resolveHeadlessAutoApproval
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class WorkflowWebContentBoundaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `creatorless browser action taints the next root action through real headless approval`() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { store ->
            store.preferences.setYolo(true)
            store.preferences.setAskAfterWebContent(true)
            val model = Model(modelId = "workflow-web-boundary")
            val owner = Assistant(chatModelId = model.id)
            val settings = Settings(assistants = listOf(owner), assistantId = owner.id,
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
            val context = object : ContextWrapper(null) {}
            var browserCalls = 0
            var rootCalls = 0
            val executions = mutableListOf<RunExecutionContext>()
            val taints = mutableListOf<RunWebTaint>()
            val claims = mutableSetOf<String>()
            val tools = listOf(
                Tool("browser_get_text", "fixture browser read", needsApproval = { true }, execute = {
                    browserCalls++
                    listOf(UIMessagePart.Text("untrusted page"))
                }),
                Tool("root_exec", "fixture root effect", needsApproval = { true }, execute = {
                    rootCalls++
                    listOf(UIMessagePart.Text("root effect"))
                }),
            )
            val client = OkHttpClient.Builder().addInterceptor { error("Direct workflow cannot call a provider") }.build()
            val headless = HeadlessTaskRunner(
                GenerationLoop(context, ProviderManager(client, context), Json { ignoreUnknownKeys = true }),
                toolSource = { _, _, _, execution -> executions += execution; tools },
                settings = { settings },
                journal = object : HeadlessRunJournal {
                    override suspend fun claim(runId: String) = claims.add(runId)
                    override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) {
                        check(runId in claims)
                        check(toolCallIds.isNotEmpty())
                    }
                },
                authorize = { execution, name, args ->
                    resolveHeadlessAutoApproval(store.preferences, execution, name, args, webContentSeen = false)
                },
                preflight = { _, _ -> null },
                featureEnabled = { true },
                onWebContent = { it.webTaint.mark() },
            )
            val runner = WorkflowActionRunner { definition, action, taint ->
                taints += taint
                headless.run(HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction(action.tool, action.args))),
                    RunExecutionContext(Uuid.random(), owner.id, RunOrigin.WORKFLOW,
                        callerConversationId = definition.authoringConversationId?.let(Uuid::parse),
                        webTaint = taint, maxSteps = 1))
            }
            val definition = WorkflowDefinition("id", "Web boundary", trigger = TriggerSpec.Manual,
                authoringAssistantId = owner.id.toString(), authoringConversationId = null,
                actions = listOf(
                    WorkflowAction("browser_get_text", buildJsonObject {}),
                    WorkflowAction("root_exec", buildJsonObject { put("command", "id") }),
                ))

            val result = runner.run(definition)

            assertFalse(result.success)
            assertEquals("action 1: approval_required", result.error)
            assertEquals(1, browserCalls)
            assertEquals(0, rootCalls)
            assertEquals(2, executions.size)
            assertTrue(executions.all { it.callerConversationId == null })
            assertNotEquals(executions[0].runId, executions[1].runId)
            assertSame(taints[0], taints[1])
            assertTrue(taints[1].isTainted())

            // A separate event gets a separate context; one event's browser read is not
            // stored as a global grant or injected into unrelated creatorless workflows.
            val next = runner.run(definition.copy(actions = listOf(definition.actions[0])))
            assertTrue(next.success)
            assertNotSame(taints[0], taints.last())
            assertEquals(2, browserCalls)
        }
    }
}
