package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ConversationConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class HeadlessRuntimeBindingsTest {
    @Test fun externalRunsRequireBothLiveMasterAndOwnerFeature() = runBlocking {
        val assistant = Assistant(localTools = listOf(LocalToolOption.ExternalAutomation))
        val context = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.EXTERNAL_AUTOMATION)
        var masterEnabled = true
        assertTrue(headlessFeatureEnabled(context, assistant) { masterEnabled })
        masterEnabled = false
        assertFalse(headlessFeatureEnabled(context, assistant) { masterEnabled })
        masterEnabled = true
        assertFalse(headlessFeatureEnabled(context, assistant.copy(localTools = emptyList())) { masterEnabled })
        assertFalse(headlessFeatureEnabled(context, null) { masterEnabled })
        assertFalse(headlessFeatureEnabled(context, assistant.copy(id = Uuid.random())) { masterEnabled })
    }

    @Test fun externalCallerRevocationWinsOverEnabledMasterAndAssistant() = runBlocking {
        val assistant = Assistant(localTools = listOf(LocalToolOption.ExternalAutomation))
        var trusted = true
        val context = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.EXTERNAL_AUTOMATION,
            runStillAllowed = { trusted })
        assertTrue(headlessFeatureEnabled(context, assistant) { true })
        trusted = false
        assertFalse(headlessFeatureEnabled(context, assistant) { true })
    }

    @Test fun cronAndSubagentFeaturesRemainIndependentOfExternalMaster() = runBlocking {
        val assistant = Assistant(localTools = listOf(LocalToolOption.SubAgents, LocalToolOption.CronJobs))
        for (origin in listOf(RunOrigin.SUB_AGENT, RunOrigin.CRON)) {
            val context = RunExecutionContext(Uuid.random(), assistant.id, origin)
            assertTrue(headlessFeatureEnabled(context, assistant) { error("External master must not be read") })
            assertFalse(headlessFeatureEnabled(context, assistant.copy(localTools = emptyList())) { true })
        }
    }

    @Test fun allPackageFDefinitionsAreSubjectToTheirOwnFeatureSwitch() {
        val groups = mapOf(
            LocalToolOption.Termux to listOf("termux_run_command", "termux_session_start", "termux_session_send",
                "termux_session_read", "termux_session_kill", "termux_session_list"),
            LocalToolOption.Ssh to listOf("ssh_exec", "save_ssh_host", "list_ssh_hosts", "delete_ssh_host",
                "ssh_exec_saved", "ssh_forget_host_key", "ssh_upload", "ssh_download"),
            LocalToolOption.McpControl to listOf("mcp_list", "mcp_get", "mcp_add", "mcp_update", "mcp_delete",
                "mcp_set_enabled", "mcp_test", "mcp_list_tools", "mcp_set_tool_approval"),
            LocalToolOption.ExternalAutomation to listOf("external_automation_status", "external_automation_set_enabled",
                "external_automation_add_trusted_package", "external_automation_remove_trusted_package"),
        )
        groups.forEach { (option, names) -> names.forEach { assertEquals(it, option, localOptionForHeadlessTool(it)) } }
        assertNull(localOptionForHeadlessTool("mcp__untrusted__read"))
    }

    @Test fun remoteMcpUsesServerIdScopedNamesAndLiveConversationConfiguration() {
        val server = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(
            name = "shared", tools = listOf(McpTool(name = "read"))))
        val second = server.copy(id = Uuid.random())
        val settings = Settings(mcpServers = listOf(server, second))
        val assistant = Assistant(mcpServers = setOf(server.id, second.id))
        val context = RunExecutionContext(Uuid.random(), assistant.id, RunOrigin.EXTERNAL_AUTOMATION,
            callerConversationConfig = ConversationConfig(mcpServers = setOf(server.id)))
        val name = buildMcpToolName(server.id, "shared", "read")
        assertTrue(isHeadlessMcpToolEnabled(settings, assistant, context, name))
        assertFalse(isHeadlessMcpToolEnabled(settings, assistant, context, "mcp__shared__read"))
        assertFalse(isHeadlessMcpToolEnabled(settings, assistant, context, buildMcpToolName(second.id, "shared", "read")))
        assertFalse(isHeadlessMcpToolEnabled(settings, assistant,
            context.copy(callerConversationConfig = ConversationConfig(mcpServers = emptySet())), name))
        assertFalse(isHeadlessMcpToolEnabled(settings.copy(mcpServers = listOf(server.copy(
            commonOptions = server.commonOptions.copy(enable = false)))), assistant, context, name))
        assertFalse(isHeadlessMcpToolEnabled(settings.copy(mcpServers = listOf(server.copy(
            commonOptions = server.commonOptions.copy(tools = listOf(McpTool(name = "read", enable = false)))))), assistant, context, name))
    }
}
