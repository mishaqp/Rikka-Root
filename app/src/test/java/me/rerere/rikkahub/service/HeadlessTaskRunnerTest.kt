package me.rerere.rikkahub.service
import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.data.ai.tools.local.LocalFileAccess
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.fileManagerTools
import me.rerere.rikkahub.data.ai.tools.local.SmsDeliveryResult
import me.rerere.rikkahub.data.ai.tools.local.smsOutcomeJson
import me.rerere.rikkahub.data.ai.tools.local.buildRootTool
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootProcessResult
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Test
class HeadlessTaskRunnerTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun deadlineCancelsActualGenerationAndWaitsForCleanup() = runBlocking {
        var cleaned = false
        var started = false
        val result = runHeadlessDeadline(30) {
            try { started = true; awaitCancellation() }
            finally { cleaned = true }
        }
        assertTrue(started)
        assertTrue(cleaned)
        assertEquals(HeadlessTaskStatus.TIMED_OUT, result.status)
    }
    @Test fun parentCancellationPropagatesAndStopsBody() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val job = launch {
            runHeadlessDeadline(480_000) {
                try { started.complete(Unit); awaitCancellation() }
                finally { cleaned = true }
            }
        }
        withTimeout(1_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(cleaned)
        assertTrue(job.isCancelled)
    }
    @Test fun durableClaimAndCheckpointPrecedeEffectsAndPreventReplay() = runBlocking {
        val fixture = Fixture()
        val request = HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction("write_text_file", buildJsonObject {})))
        val first = fixture.runner.run(request, fixture.context)
        assertEquals(HeadlessTaskStatus.SUCCEEDED, first.status)
        assertEquals(listOf("claim", "checkpoint", "effect"), fixture.events)
        val duplicate = fixture.runner.run(request, fixture.context)
        assertEquals(HeadlessTaskStatus.INDETERMINATE, duplicate.status)
        assertEquals(1, fixture.effects)
    }
    @Test fun revocationDuringCheckpointBlocksActualToolExecution() = runBlocking {
        val fixture = Fixture(revokeAtCheckpoint = true)
        val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(
            HeadlessToolAction("write_text_file", buildJsonObject {}))), fixture.context)
        assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
        assertEquals("approval_required", result.errorCode)
        assertEquals(0, fixture.effects)
    }
    @Test fun androidPermissionIsRecheckedImmediatelyBeforeExecution() = runBlocking {
        val fixture = Fixture(permissionRevokedAtCheckpoint = true)
        val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(
            HeadlessToolAction("write_text_file", buildJsonObject {}))), fixture.context)
        assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
        assertEquals("android_permission_required", result.errorCode)
        assertEquals(0, fixture.effects)
    }
    @Test fun realGenerationUsesOwnerAndConversationConfigWithoutSelectedAssistantMutation() = runBlocking {
        val fixture = Fixture()
        val originalSelected = fixture.settings.assistantId
        val workspace = Uuid.random()
        val result = fixture.runner.run(HeadlessTaskRequest.Prompt("isolated task"), fixture.context.copy(
            callerConversationConfig = ConversationConfig(chatModelId = fixture.model.id, workspaceId = workspace)))
        assertEquals(HeadlessTaskStatus.SUCCEEDED, result.status)
        assertEquals("side result", result.output)
        assertEquals(fixture.owner.id, fixture.effectiveAssistant?.id)
        assertEquals(workspace, fixture.effectiveAssistant?.workspaceId)
        assertEquals(originalSelected, fixture.settings.assistantId)
        assertTrue(fixture.requestBody.contains("isolated task"))
        assertTrue(fixture.requestBody.contains("owner system"))
        assertFalse(fixture.requestBody.contains("selected assistant system"))
    }

    @Test fun pausedOccurrenceBlocksNextActionEvenBeforeWorkerCancellationArrives() = runBlocking {
        val fixture = Fixture(pauseAfterFirstEffect = true)
        val action = HeadlessToolAction("write_text_file", buildJsonObject {})
        val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(action, action)), fixture.context)
        assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
        assertEquals("run_no_longer_allowed", result.errorCode)
        assertEquals(1, fixture.effects)
    }
    @Test fun liveOccurrenceGateRunsBeforeProviderRequest() = runBlocking {
        val fixture = Fixture(pauseWhenToolsBuilt = true)
        val result = fixture.runner.run(HeadlessTaskRequest.Prompt("task"), fixture.context)
        assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
        assertEquals("", fixture.requestBody)
    }
    @Test fun disablingExternalMasterAtCheckpointStopsActualSideEffect() = runBlocking {
        val fixture = Fixture(disableExternalAtCheckpoint = true)
        val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(
            HeadlessToolAction("write_text_file", buildJsonObject {}))),
            fixture.context.copy(origin = RunOrigin.EXTERNAL_AUTOMATION))
        assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
        assertEquals("feature_disabled", result.errorCode)
        assertEquals(0, fixture.effects)
    }
    @Test fun externalRunsCannotExecuteOwnTrustMutationsEvenWithAutomaticAuthorization() = runBlocking {
        for (name in listOf("external_automation_set_enabled", "external_automation_add_trusted_package",
            "external_automation_remove_trusted_package")) {
            var effects = 0
            val fixture = Fixture(toolsOverride = listOf(Tool(name, "test", needsApproval = { true }, execute = {
                effects++; listOf(UIMessagePart.Text("unexpected"))
            })))
            val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(
                HeadlessToolAction(name, buildJsonObject {}))), fixture.context.copy(origin = RunOrigin.EXTERNAL_AUTOMATION))
            assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals("mandatory_confirmation", result.errorCode)
            assertEquals(0, effects)
        }
    }
    @Test fun shellGuardRunsBeforeAnyTermuxOrSshEffectInExternalTasks() = runBlocking {
        for (name in listOf("ssh_exec", "ssh_exec_saved", "termux_run_command", "termux_session_start")) {
            var effects = 0
            val fixture = Fixture(toolsOverride = listOf(Tool(name, "test", needsApproval = { true }, execute = {
                effects++; listOf(UIMessagePart.Text("unexpected"))
            })))
            val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction(name,
                buildJsonObject { put("command", "rm -rf /system") }))),
                fixture.context.copy(origin = RunOrigin.EXTERNAL_AUTOMATION))
            assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
            assertEquals("root_command_blocked", result.errorCode)
            assertEquals(0, effects)
        }
    }
    @Test fun inheritedBudgetWinsAndStandaloneRunObtainsItsOwnLedger() = runBlocking {
        val inherited = TokenBudgetLedger(enabled = true, hardCap = 1000)
        val standalone = TokenBudgetLedger(enabled = true, hardCap = 500)
        val fixture = Fixture(standaloneBudget = standalone)
        val request = HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction("write_text_file", buildJsonObject {})))
        assertEquals(HeadlessTaskStatus.SUCCEEDED, fixture.runner.run(request, fixture.context.copy(costBudget = inherited)).status)
        assertSame(inherited, fixture.builtContext?.costBudget)
        assertEquals(0, fixture.budgetRequests)
        assertEquals(HeadlessTaskStatus.SUCCEEDED, fixture.runner.run(request, fixture.context.copy(runId = Uuid.random())).status)
        assertSame(standalone, fixture.builtContext?.costBudget)
        assertEquals(1, fixture.budgetRequests)
    }
    @Test fun actualNativeBatchFailureIsFailedWithoutRetry() = runBlocking {
        val access = LocalFileAccess(folder.newFolder("scratch"), folder.newFolder("upload"))
        val tools = fileManagerTools(access)
        for (name in listOf("batch_copy", "batch_move", "batch_delete")) {
            val native = tools.single { it.name == name }
            var invocations = 0
            val observed = native.copy(execute = { arguments ->
                invocations++
                native.execute(arguments).also { parts ->
                    val envelope = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
                    assertTrue(envelope.getValue("failed").jsonArray.isNotEmpty())
                }
            })
            val fixture = Fixture(toolsOverride = listOf(observed))
            val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction(name,
                Json.parseToJsonElement("""{"paths":["missing-file"],"dst_dir":"/scratch"}""")))), fixture.context)
            assertEquals("Native $name must report its failed array", HeadlessTaskStatus.FAILED, result.status)
            assertEquals(1, invocations)
        }
    }
    @Test fun realSmsPartialAndTimeoutEnvelopesAreIndeterminate() {
        val partial = smsOutcomeJson(SmsDeliveryResult(sentParts = 1, unknownParts = 1, failedParts = emptyMap(), timedOut = true), 2)
        assertEquals(HeadlessTaskStatus.INDETERMINATE, classifyHeadlessToolResult("send_sms", listOf(UIMessagePart.Text(partial.toString()))))
        val failed = smsOutcomeJson(SmsDeliveryResult(sentParts = 0, unknownParts = 0, failedParts = mapOf(0 to 2)), 1)
        assertEquals(HeadlessTaskStatus.FAILED, classifyHeadlessToolResult("send_sms", listOf(UIMessagePart.Text(failed.toString()))))
        assertNull(classifyHeadlessToolResult("read_file", listOf(UIMessagePart.Text("""{"content":"{\"error\":\"example\"}","failed":[{"error":"example"}]}"""))))
    }

    @Test fun rootNativeErrorsPreserveOnlyWhitelistedOutcomeCodes() {
        fun envelope(result: RootProcessResult) = listOf(UIMessagePart.Text(buildJsonObject {
            put("backend", "root")
            put("success", result.error == null && result.exitCode == 0)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            result.exitCode?.let { put("exit_code", it) }
            result.error?.let { put("error", it) }
            result.reason?.let { put("reason", it) }
        }.toString()))
        for (code in listOf("command_timeout", "root_cleanup_unconfirmed", "root_execution_indeterminate")) {
            assertEquals(HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, code),
                classifyHeadlessToolFailure("root_exec", envelope(RootProcessResult(error = code))))
        }
        for (code in listOf("root_interaction_required", "root_approval_required", "root_not_granted", "root_command_blocked")) {
            assertEquals(HeadlessToolFailure(HeadlessTaskStatus.BLOCKED, code),
                classifyHeadlessToolFailure("root_exec", envelope(RootProcessResult(error = code))))
        }
        val arbitrary = envelope(RootProcessResult(error = "private arbitrary details"))
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.FAILED, "tool_failed"), classifyHeadlessToolFailure("root_exec", arbitrary))
        assertNull(classifyHeadlessToolFailure("unregistered_reader", arbitrary))
        assertNull(classifyHeadlessToolFailure("mcp__Reader__read", arbitrary))
        assertNull(classifyHeadlessToolFailure("root_exec", envelope(RootProcessResult(exitCode = 0, stdout = "{\"error\":\"root_cleanup_unconfirmed\"}"))))
    }
    @Test fun actualRootToolWithoutForegroundBrokerReportsBlockedSafeCode() = runBlocking {
        val fixture = Fixture(toolsOverride = listOf(buildRootTool(RootShellManager(suExecutable = "/nonexistent-no-launch"))))
        val result = fixture.runner.run(HeadlessTaskRequest.DirectActions(listOf(HeadlessToolAction("root_exec",
            buildJsonObject { put("command", "id") }))), fixture.context)
        assertEquals(HeadlessTaskStatus.BLOCKED, result.status)
        assertEquals("root_interaction_required", result.errorCode)
    }

    @Test fun workspaceNativeResultDistinguishesTimeoutFailureAndOpaqueStdout() {
        fun envelope(result: WorkspaceCommandResult) = listOf(UIMessagePart.Text(buildJsonObject {
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timedOut", result.timedOut)
            if (result.truncated) put("truncated", true)
        }.toString()))
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, "command_timeout"),
            classifyHeadlessToolFailure("workspace_shell", envelope(WorkspaceCommandResult(137, "partial", "", timedOut = true))))
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.FAILED, "tool_failed"),
            classifyHeadlessToolFailure("workspace_shell", envelope(WorkspaceCommandResult(1, "", "command failed"))))
        assertNull(classifyHeadlessToolFailure("workspace_shell", envelope(WorkspaceCommandResult(0,
            "{\"error\":\"example\",\"exitCode\":1,\"timedOut\":true}", ""))))
        assertNull(classifyHeadlessToolFailure("workspace_read_file", listOf(UIMessagePart.Text(
            buildJsonObject { put("text", "{\"exitCode\":1,\"timedOut\":true}"); put("path", "/workspace/example.json") }.toString()))))
    }

    private class Fixture(
        private val revokeAtCheckpoint: Boolean = false,
        private val permissionRevokedAtCheckpoint: Boolean = false,
        private val pauseAfterFirstEffect: Boolean = false,
        private val pauseWhenToolsBuilt: Boolean = false,
        private val standaloneBudget: TokenBudgetLedger? = null,
        private val toolsOverride: List<Tool>? = null,
        private val disableExternalAtCheckpoint: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        var effects = 0
        var allowed = true
        var permission = true
        var requestBody = ""
        var effectiveAssistant: Assistant? = null
        var builtContext: RunExecutionContext? = null
        var runAllowed = true
        var budgetRequests = 0
        var externalMasterEnabled = true
        val model = Model(modelId = "test-chat")
        val owner = Assistant(chatModelId = model.id, systemPrompt = "owner system", streamOutput = false)
        private val selected = Assistant(systemPrompt = "selected assistant system")
        private val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
        val settings = Settings(providers = listOf(provider), assistants = listOf(owner, selected), assistantId = selected.id)
        val context = RunExecutionContext(Uuid.random(), owner.id, RunOrigin.SUB_AGENT, modelId = model.id.toString(), runStillAllowed = { runAllowed })
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"response","object":"chat.completion","model":"test-chat","choices":[{"index":0,"message":{"role":"assistant","content":"side result"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":3,"total_tokens":11}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val androidContext = object : ContextWrapper(null) {}
        private val journal = object : HeadlessRunJournal {
            val claims = mutableSetOf<String>()
            override suspend fun claim(runId: String): Boolean { events += "claim"; return claims.add(runId) }
            override suspend fun checkpoint(runId: String, toolCallIds: Set<String>) {
                check(runId in claims)
                assertTrue(toolCallIds.isNotEmpty())
                events += "checkpoint"
                if (revokeAtCheckpoint) allowed = false
                if (permissionRevokedAtCheckpoint) permission = false
                if (disableExternalAtCheckpoint) externalMasterEnabled = false
            }
        }
        val runner = HeadlessTaskRunner(
            generationLoop = GenerationLoop(androidContext, ProviderManager(client, androidContext), Json { ignoreUnknownKeys = true }),
            toolSource = { _, assistant, _, execution ->
                effectiveAssistant = assistant
                builtContext = execution
                if (pauseWhenToolsBuilt) runAllowed = false
                toolsOverride ?: listOf(Tool("write_text_file", "test", needsApproval = { true }, execute = {
                    events += "effect"; effects++
                    if (pauseAfterFirstEffect) runAllowed = false
                    listOf(UIMessagePart.Text("done"))
                }))
            },
            settings = { settings }, journal = journal,
            authorize = { _, _, _ -> allowed },
            preflight = { _, _ -> if (permission) null else "android_permission_required" },
            featureEnabled = { execution ->
                if (execution.origin == RunOrigin.EXTERNAL_AUTOMATION) {
                    headlessFeatureEnabled(execution, owner.copy(localTools = listOf(LocalToolOption.ExternalAutomation))) { externalMasterEnabled }
                } else true
            },
            budgetFor = { _, _ -> budgetRequests++; standaloneBudget },
        )
    }

}
