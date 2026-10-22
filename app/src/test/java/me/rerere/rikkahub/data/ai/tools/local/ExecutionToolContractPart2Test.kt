package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import io.mockk.mockk
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.control.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.root.RootShellManager
import org.junit.Assert.*
import org.junit.Test

/** Public factory contracts, without opening a shell, network connection or WebView. */
class ExecutionToolContractPart2Test {
    private val context = mockk<Context>()
    private val hosts = mockk<SshHostRepository>()
    private val secrets = mockk<SshToolSecretSanitizer>()
    private val files = mockk<LocalFileAccess>()
    private val settings = mockk<SettingsStore>()
    private val manager = mockk<McpManager>()
    private val vault = mockk<McpControlSecretStore>()
    private val caller = McpControlCaller({ emptySet() }, {}, {})

    private fun root() = buildRootTool(RootShellManager(suExecutable = "/nonexistent/part2-su"))
    private fun ssh() = listOf(sshExecTool(context, secrets), sshExecSavedTool(context, hosts),
        saveSshHostTool(hosts), listSshHostsTool(hosts), deleteSshHostTool(hosts), forgetSshHostKeyTool(context),
        sshUploadTool(context, hosts, files), sshDownloadTool(context, hosts, files))
    private fun termux() = listOf(termuxRunCommandTool(context), termuxSessionStartTool(context),
        termuxSessionSendTool(context), termuxSessionReadTool(context), termuxSessionKillTool(context), termuxSessionListTool(context))
    private fun browser() = listOf(browserOpenTool(context), browserCurrentUrlTool(), browserScreenshotTool(context),
        browserGetTextTool(), browserGetDomTool(), browserGetLinksTool(), browserBackTool(), browserForwardTool(),
        browserWaitForTool(), browserClickTool(), browserTypeTool(), browserScrollTool(), browserSubmitTool(),
        browserSelectTool(), browserPressKeyTool(), browserEvalJsTool(), browserClickAndReadTool(), browserDoneTool())
    private fun mcp() = listOf(mcpListTool(settings, manager, caller), mcpGetTool(settings, manager, caller),
        mcpAddTool(settings, manager, caller, vault), mcpUpdateTool(settings, manager, vault),
        mcpDeleteTool(settings, manager, caller), mcpSetEnabledTool(settings, manager, vault),
        mcpTestTool(settings, manager, vault), mcpListToolsTool(settings, manager, caller), mcpSetToolApprovalTool(settings))

    @Test fun rootContractExplainsTheOperationAndParametersInRussian() = russianContract(listOf(root()))
    @Test fun sshContractsExplainTheOperationAndEveryParameterInRussian() = russianContract(ssh())
    @Test fun termuxContractsExplainTheOperationAndEveryParameterInRussian() = russianContract(termux())
    @Test fun browserContractsExplainTheOperationAndEveryParameterInRussian() = russianContract(browser())
    @Test fun mcpContractsDescribeNestedHeaderFieldsAndEveryParameterInRussian() = russianContract(mcp())

    @Test fun rootSchemaPublishesUserTimeoutWithinTheExistingClamp() {
        val savedTimeout = TermuxRuntime.commandTimeoutMs
        try {
            TermuxRuntime.commandTimeoutMs = 120_000
            val tool = root()
            bounds(tool, "timeout_ms", 1_000, 300_000, 120_000)
            // Reusing a registered tool must still publish the current preference.
            TermuxRuntime.commandTimeoutMs = 180_000
            bounds(tool, "timeout_ms", 1_000, 300_000, 180_000)
            TermuxRuntime.commandTimeoutMs = 600_000
            bounds(tool, "timeout_ms", 1_000, 300_000, 300_000)
            assertEquals(listOf("command"), schema(tool).required)
        } finally {
            TermuxRuntime.commandTimeoutMs = savedTimeout
        }
    }

    @Test fun sshSchemasPublishTimeoutAndPortBoundsWithActualDefaults() {
        val tools = ssh().associateBy { it.name }
        for (name in listOf("ssh_exec", "ssh_exec_saved")) bounds(tools.getValue(name), "timeout_seconds", 1, 300, 30)
        for (name in listOf("ssh_upload", "ssh_download")) bounds(tools.getValue(name), "timeout_seconds", 1, 600, 60)
        for (name in listOf("ssh_exec", "save_ssh_host")) bounds(tools.getValue(name), "port", 1, 65535, 22)
    }

    @Test fun termuxSchemasKeepZeroTimeoutSentinelAndActualSessionDefaults() {
        val tools = termux().associateBy { it.name }
        bounds(tools.getValue("termux_run_command"), "timeout_seconds", 0, 600, 0)
        for (name in listOf("termux_session_send", "termux_session_read")) bounds(tools.getValue(name), "timeout_seconds", 0, 600, 20)
        assertEquals(200, property(tools.getValue("termux_session_start"), "cols")["default"]?.jsonPrimitive?.intOrNull)
        assertEquals(50, property(tools.getValue("termux_session_start"), "rows")["default"]?.jsonPrimitive?.intOrNull)
        assertEquals(200, property(tools.getValue("termux_session_read"), "lines")["default"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, property(tools.getValue("termux_session_send"), "enter")["default"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, property(tools.getValue("termux_run_command"), "interactive")["default"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test fun browserSchemasPublishExistingTextCapsAndModesWithoutChangingApproval() {
        bounds(browserGetTextTool(), "max_chars", 100, 65536, 8000)
        bounds(browserGetDomTool(), "max_chars", 100, 65536, 4000)
        bounds(browserClickAndReadTool(), "max_chars", 100, 65536, 4000)
        assertEquals("auto", property(browserGetTextTool(), "extract_mode")["default"]?.jsonPrimitive?.content)
        assertEquals("diff", property(browserClickAndReadTool(), "extract_mode")["default"]?.jsonPrimitive?.content)
        assertEquals("attached", property(browserWaitForTool(), "state")["default"]?.jsonPrimitive?.content)
    }

    @Test fun mcpSchemasPublishActualWaitBoundsAndDoNotOfferApprovalDisable() {
        bounds(mcpAddTool(settings, manager, caller, vault), "connect_timeout_seconds", 1, 60, 15)
        bounds(mcpUpdateTool(settings, manager, vault), "connect_timeout_seconds", 1, 60, 15)
        bounds(mcpTestTool(settings, manager, vault), "wait_seconds", 1, 60, 15)
        assertEquals(buildJsonArray { add(true) }, property(mcpSetToolApprovalTool(settings), "needs_approval")["enum"])
    }

    @Test fun browserContractWorkDoesNotAddToolLevelConfirmations() {
        // Current policy/dispatcher decisions remain separate; do not introduce another browser gate.
        for (tool in browser()) assertFalse(tool.name, tool.needsApproval(JsonObject(emptyMap())))
    }

    @Test fun requiredPropertiesStayDeclaredAndCollectionItemsStayTyped() {
        val tools = listOf(root()) + ssh() + termux() + browser() + mcp()
        assertEquals(42, tools.size)
        for (tool in tools) {
            val spec = schema(tool)
            assertTrue(tool.name, spec.required.orEmpty().all { it in spec.properties })
            for ((name, raw) in spec.properties) {
                val prop = raw.jsonObject
                if (prop["type"]?.jsonPrimitive?.content == "array") {
                    assertNotNull("${tool.name}.$name.items.type", prop["items"]?.jsonObject?.get("type"))
                }
            }
        }
    }

    private fun schema(tool: Tool): InputSchema.Obj = tool.parameters() as InputSchema.Obj
    private fun property(tool: Tool, name: String): JsonObject = schema(tool).properties.getValue(name).jsonObject
    private fun bounds(tool: Tool, name: String, min: Int, max: Int, default: Int) {
        val prop = property(tool, name)
        assertEquals("${tool.name}.$name.minimum", min, prop["minimum"]?.jsonPrimitive?.intOrNull)
        assertEquals("${tool.name}.$name.maximum", max, prop["maximum"]?.jsonPrimitive?.intOrNull)
        assertEquals("${tool.name}.$name.default", default, prop["default"]?.jsonPrimitive?.intOrNull)
    }
    private fun russianContract(tools: List<Tool>) {
        val missing = mutableListOf<String>()
        val ru = Regex("[А-Яа-яЁё]")
        fun inspect(path: String, props: JsonObject) {
            for ((name, raw) in props) {
                val prop = raw.jsonObject
                if (prop["type"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) missing += "$path.$name.type"
                if (!ru.containsMatchIn(prop["description"]?.jsonPrimitive?.contentOrNull.orEmpty())) missing += "$path.$name.description"
                prop["properties"]?.jsonObject?.let { inspect("$path.$name", it) }
                prop["items"]?.jsonObject?.get("properties")?.jsonObject?.let { inspect("$path.$name[]", it) }
            }
        }
        for (tool in tools) {
            if (!ru.containsMatchIn(tool.description.take(160))) missing += "${tool.name}.description introduction"
            inspect(tool.name, schema(tool).properties)
        }
        assertTrue("Missing Russian public contracts: ${missing.joinToString()}", missing.isEmpty())
    }
}
