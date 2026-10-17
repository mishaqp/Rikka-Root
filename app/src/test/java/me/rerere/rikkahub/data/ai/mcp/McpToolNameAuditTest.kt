package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Audit 9b74e541 finding 18: tool names must fit provider restrictions without collisions. */
class McpToolNameAuditTest {
    private val id = Uuid.parse("12345678-1111-2222-3333-444444444444")

    @Test fun `audit 18 names use only provider supported characters`() {
        val name = buildMcpToolName(id, "Мой сервер / alpha", "space tool.查询/🦀")
        assertTrue(name, name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
    }

    @Test fun `audit 18 long names are bounded and keep differences after the truncation point`() {
        val prefix = "a".repeat(200)
        val first = buildMcpToolName(id, "server", prefix + "first")
        val second = buildMcpToolName(id, "server", prefix + "second")
        assertTrue(first, first.length <= 64)
        assertTrue(second, second.length <= 64)
        assertNotEquals(first, second)
        assertEquals(first, buildMcpToolName(id, "server", prefix + "first"))
    }

    @Test fun `audit 18 lossy character normalization retains unique dispatch names`() {
        assertNotEquals(buildMcpToolName(id, "server", "tool/a"), buildMcpToolName(id, "server", "tool.a"))
        assertNotEquals(buildMcpToolName(id, "сервер", "tool"), buildMcpToolName(id, "伺服器", "tool"))
    }

    @Test fun `review valid names stay unchanged for saved approvals and scheduled tasks`() {
        assertEquals("mcp__12345678_server__tool", buildMcpToolName(id, "server", "tool"))
    }
}
