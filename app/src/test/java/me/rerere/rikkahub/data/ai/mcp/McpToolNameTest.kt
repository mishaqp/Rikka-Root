// Copied from ExTV/rikkahub-agent (AGPL v3).
package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #88: [buildMcpToolName] is the single helper the tool-registration path (ChatService) and
 * the mcp_list_tools diagnostic listing (McpControlTools) both call, so the dispatchable name
 * shown to the model can never drift from the name the dispatch table was actually built with.
 */
class McpToolNameTest {

    @Test
    fun `builds a readable mcp name with a stable full identity hash suffix`() {
        val id = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val name = buildMcpToolName(id, "my-server", "do_thing")

        assertEquals("mcp__aaaaaaaa_my-server__do_thing_be34b7081f31f5e6", name)
    }

    @Test
    fun `UUID slug is followed by the complete identity hash`() {
        val id = Uuid.parse("12345678-9999-cccc-dddd-eeeeeeeeeeee")
        val name = buildMcpToolName(id, "s", "t")

        assertEquals("mcp__12345678_s__t_bf1cb35c7495c04f", name)
    }

    @Test
    fun `two identically-named servers produce distinct dispatchable names`() {
        val a = buildMcpToolName(Uuid.random(), "shared-name", "tool")
        val b = buildMcpToolName(Uuid.random(), "shared-name", "tool")

        org.junit.Assert.assertNotEquals(a, b)
    }
}
