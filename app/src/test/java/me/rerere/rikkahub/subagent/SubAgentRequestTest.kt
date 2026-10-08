package me.rerere.rikkahub.subagent
import me.rerere.rikkahub.data.ai.tools.subAgentToolAllowlist
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test
class SubAgentRequestTest {
    @Test fun limitsAreStrictInsteadOfSilentlyClamped() {
        assertNull(validateSubAgentRequest(SubAgentRequest("task")))
        for (request in listOf(SubAgentRequest(" "), SubAgentRequest("task", timeoutSeconds = 481),
            SubAgentRequest("task", maxTrips = 0), SubAgentRequest("task", modelId = ""))) {
            assertNotNull(validateSubAgentRequest(request))
        }
    }
    @Test fun modelSelectionRejectsUnknownAmbiguousAndDisabledProviders() {
        val one = Model(modelId = "same", displayName = "First")
        val two = Model(modelId = "same", displayName = "Second")
        val providers = listOf(ProviderSetting.OpenAI(models = listOf(one, two)))
        assertEquals(one, resolveSubAgentModel(providers, one.id.toString(), null))
        assertNull(resolveSubAgentModel(providers, "same", null))
        assertNull(resolveSubAgentModel(providers, "unknown", one.id.toString()))
        assertNull(resolveSubAgentModel(listOf(ProviderSetting.OpenAI(enabled = false, models = listOf(one))), one.id.toString(), null))
    }
    @Test fun delegatedAllowlistFreezesAdvertisedCapabilitiesAndGatesSearchReplacement() {
        val advertised = mutableListOf("read_file", "workspace_shell", "mcp__Server__read")
        val bounded = subAgentToolAllowlist(advertised, false, listOf("search_web", "scrape_web"))
        advertised += "send_sms"
        assertEquals(setOf("read_file", "workspace_shell", "mcp__Server__read"), bounded)
        assertFalse("search_web" in bounded)
        val replacement = subAgentToolAllowlist(bounded.toList(), true, listOf("search_web", "scrape_web"))
        assertTrue(replacement.containsAll(listOf("search_web", "scrape_web", "workspace_shell", "mcp__Server__read")))
        assertFalse("send_sms" in replacement)
    }

}
