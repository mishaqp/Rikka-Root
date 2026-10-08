package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.data.ssh.sanitizeToolArgsForExport
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import me.rerere.rikkahub.service.resolveHeadlessAutoApproval
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.sanitizeWorkflowDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import kotlin.uuid.Uuid

/** Exercises the production nesting/vault/export boundary and the headless dispatch gate together. */
class PackageGSecurityIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val password = "workflow-password-canary"
    private val privateKey = "workflow-private-key-canary"
    private val passphrase = "workflow-passphrase-canary"
    private val mcpToken = "workflow-mcp-token-canary"
    private val sshArguments = Json.parseToJsonElement("""{"host":"example.org","user":"me","command":"whoami","password":"$password","private_key":"$privateKey","passphrase":"$passphrase"}""").jsonObject
    private val mcpArguments = Json.parseToJsonElement("""{"name":"Remote","url":"https://example.org/mcp","headers":[{"name":"Authorization","value":"Bearer $mcpToken"}]}""").jsonObject
    private val secrets get() = listOf(password, privateKey, passphrase, mcpToken)

    @Test
    fun `nested workflow tool history protects real SSH and MCP vaults and export removes references`() {
        val sshFile = temporary.newFile("ssh.enc").apply { delete() }
        val mcpDirectory = temporary.newFolder("mcp")
        val ssh = SshToolSecretSanitizer(SshCredentialStore(sshFile, Json) { key })
        val mcp = McpToolSecretSanitizer(McpControlSecretStore(mcpDirectory) { key })
        val nested = workflowArguments(listOf(
            action("ssh_exec", sshArguments), action("mcp_add", mcpArguments),
        ))
        val original = workflowArguments(listOf(action("workflow_update", nested))).toString()
        val protected = protectToolArguments("workflow_create", original, ssh, mcp)
        assertNoSecrets(protected)
        assertTrue(protected.contains("rikka-ssh-secret:"))
        assertTrue(protected.contains(McpControlSecretStore.REFERENCE_PREFIX))
        assertEquals(protected, protectToolArguments("workflow_create", protected, ssh, mcp))

        val nestedActions = Json.parseToJsonElement(protected).jsonObject["definition"]!!.jsonObject["actions"]!!.jsonArray
            .single().jsonObject["args"]!!.jsonObject["definition"]!!.jsonObject["actions"]!!.jsonArray
        val protectedSsh = nestedActions[0].jsonObject["args"]!!.jsonObject
        val protectedMcp = nestedActions[1].jsonObject["args"]!!.jsonObject
        // Recreate the vault clients: persistence must survive a new runtime context.
        val coldSsh = SshToolSecretSanitizer(SshCredentialStore(sshFile, Json) { key })
        assertEquals(sshArguments, coldSsh.resolveArgs("ssh_exec", protectedSsh))
        val reference = protectedMcp["headers"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content
        assertEquals("Bearer $mcpToken", McpControlSecretStore(mcpDirectory) { key }.resolve(reference))
        temporary.root.walkTopDown().filter { it.isFile }.forEach { file ->
            assertNoSecrets(file.readBytes().toString(Charsets.ISO_8859_1))
        }

        val exported = sanitizeToolArgsForExport("workflow_create", protected)
        assertNoSecrets(exported)
        assertFalse(exported.contains("rikka-ssh-secret:"))
        assertFalse(exported.contains(McpControlSecretStore.REFERENCE_PREFIX))
        assertTrue(exported.contains("whoami"))
        assertTrue(exported.contains("https://example.org/mcp"))
        assertTrue("The caller's live input remains unchanged", original.contains(password))
    }

    @Test
    fun `workflow definition uses the same nested tool boundary before persistence`() {
        val sshFile = temporary.newFile("definition-ssh.enc").apply { delete() }
        val ssh = SshToolSecretSanitizer(SshCredentialStore(sshFile, Json) { key })
        val mcp = McpToolSecretSanitizer(McpControlSecretStore(temporary.newFolder()) { key })
        val original = definition(WorkflowAction("workflow_update", workflowArguments(listOf(action("save_ssh_host", sshArguments), action("mcp_update", mcpArguments)))))
        val protected = sanitizeWorkflowDefinition(original) { name, arguments -> protectToolArguments(name, arguments, ssh, mcp) }
        assertNoSecrets(Json.encodeToString(WorkflowDefinition.serializer(), protected))
        assertEquals(original.id, protected.id)
        assertEquals(original.trigger, protected.trigger)
        assertEquals(original.actions.single().timeoutSeconds, protected.actions.single().timeoutSeconds)
        val exported = sanitizeWorkflowDefinition(protected, ::sanitizeToolArgsForExport)
        val serialized = Json.encodeToString(WorkflowDefinition.serializer(), exported)
        assertNoSecrets(serialized)
        assertFalse(serialized.contains("rikka-ssh-secret:"))
        assertFalse(serialized.contains(McpControlSecretStore.REFERENCE_PREFIX))
    }

    @Test
    fun `partial nested workflow arguments cannot save or export secret fragments`() {
        val input = """{"definition":{"actions":[{"tool":"ssh_exec","args":{"password":"$password"""
        val safe = protectToolArguments("workflow_create", input, null, null)
        assertNoSecrets(safe)
        assertTrue(Json.parseToJsonElement(safe) is JsonObject)
        assertTrue(safe.contains("_credentials_removed"))
        assertNoSecrets(sanitizeToolArgsForExport("workflow_", input))
    }

    @Test
    fun `excessively nested workflow actions fail closed without retaining deep secrets`() {
        var args = workflowArguments(listOf(action("ssh_exec", sshArguments)))
        repeat(24) { args = workflowArguments(listOf(action("workflow_create", args))) }
        val exported = sanitizeToolArgsForExport("workflow_create", args.toString())
        assertNoSecrets(exported)
        assertTrue(Json.parseToJsonElement(exported) is JsonObject)
        assertTrue(exported.contains("_credentials_removed"))
    }

    @Test
    fun `browser writes and skill installers remain blocked for yolo always grants and workflow dispatch`() = runBlocking {
        val names = BrowserToolDefaults.WRITE_TOOLS + setOf("skill_install_from_url", "skill_install_from_text")
        val args = buildJsonObject { put("selector", "#send"); put("url", "https://example.org/skill"); put("text", "public") }
        ToolApprovalTestStore(temporary.newFolder()).use { grants ->
            grants.preferences.setYolo(true)
            for (name in names) {
                grants.preferences.grantAlways(name) // Simulates an older stored grant, even though the UI forbids new ones.
                assertTrue(name, ToolPermissionPolicy.mandatoryConfirmation(name, args))
                assertFalse(name, ToolPermissionPolicy.canGrantAlways(name, args))
                assertEquals(name, "mandatory_confirmation", HeadlessToolPolicy.blockReason(name, args))
                for (origin in listOf(RunOrigin.CRON, RunOrigin.SUB_AGENT, RunOrigin.WORKFLOW)) {
                    val run = RunExecutionContext(Uuid.random(), Uuid.random(), origin)
                    assertFalse("$name/$origin", resolveHeadlessAutoApproval(grants.preferences, run, name, args, false))
                }
                var dispatches = 0
                val runner = WorkflowActionRunner { _, _, _ ->
                    dispatches++
                    HeadlessTaskResult(HeadlessTaskStatus.SUCCEEDED)
                }
                val result = runner.run(definition(WorkflowAction(name, args)))
                assertFalse(name, result.success)
                assertTrue(name, result.error.orEmpty().contains("mandatory_confirmation"))
                assertEquals("Forbidden action reached dispatch: $name", 0, dispatches)
            }
        }
    }

    private fun action(tool: String, args: JsonObject) = buildJsonObject { put("tool", tool); put("args", args) }
    private fun workflowArguments(actions: List<JsonObject>) = buildJsonObject {
        put("definition", buildJsonObject { put("actions", JsonArray(actions)) })
    }
    private fun definition(vararg actions: WorkflowAction) = WorkflowDefinition(
        id = "workflow-security", name = "Security fixture", trigger = TriggerSpec.Manual, actions = actions.toList(),
    )
    private fun assertNoSecrets(value: String) {
        secrets.forEach { assertFalse("A workflow secret escaped the vault boundary", value.contains(it)) }
    }
}
