package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.ai.mcp.McpStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class SettingsDashboardStatusPresentationTest {
    @Test
    fun `empty counts have no badge`() {
        assertEquals(SettingsDashboardStatus.Ready(), SettingsDashboardStatusPresentation.ssh(0))
        assertEquals(SettingsDashboardStatus.Ready(), SettingsDashboardStatusPresentation.workflows(0))
        assertEquals(SettingsDashboardStatus.Ready(), SettingsDashboardStatusPresentation.cron(0))
    }

    @Test
    fun `positive counts remain visible`() {
        assertEquals(ready("3 хостов"), SettingsDashboardStatusPresentation.ssh(3))
        assertEquals(ready("2 активных"), SettingsDashboardStatusPresentation.workflows(2))
        assertEquals(ready("1 в расписании"), SettingsDashboardStatusPresentation.cron(1))
    }

    @Test
    fun `MCP without connections or actionable state has no badge`() {
        assertEquals(SettingsDashboardStatus.Ready(), SettingsDashboardStatusPresentation.mcp(null))
        assertEquals(SettingsDashboardStatus.Ready(), SettingsDashboardStatusPresentation.mcp(emptyList()))
        assertEquals(
            SettingsDashboardStatus.Ready(),
            SettingsDashboardStatusPresentation.mcp(listOf(
                McpStatus.Idle,
                McpStatus.Connecting,
                McpStatus.Reconnecting(attempt = 1, maxAttempts = 3),
                McpStatus.Authorizing,
            )),
        )
    }

    @Test
    fun `MCP connection error with zero connections stays visible without zero count`() {
        assertEquals(
            ready("Ошибка подключения", SettingsDashboardStatusTone.Error),
            SettingsDashboardStatusPresentation.mcp(listOf(McpStatus.Error("Private endpoint"))),
        )
    }

    @Test
    fun `MCP authorization with zero connections stays visible without zero count`() {
        assertEquals(
            ready("Нужен вход", SettingsDashboardStatusTone.Warning),
            SettingsDashboardStatusPresentation.mcp(listOf(McpStatus.NeedsAuthorization)),
        )
    }

    @Test
    fun `MCP connection count retains success tone`() {
        assertEquals(
            ready("2 подключено", SettingsDashboardStatusTone.Success),
            SettingsDashboardStatusPresentation.mcp(listOf(McpStatus.Connected, McpStatus.Connected)),
        )
    }

    @Test
    fun `MCP connection count retains errors and authorization warnings`() {
        assertEquals(
            ready("1 подключено · ошибка", SettingsDashboardStatusTone.Error),
            SettingsDashboardStatusPresentation.mcp(listOf(McpStatus.Connected, McpStatus.Error("Private endpoint"))),
        )
        assertEquals(
            ready("1 подключено · нужен вход", SettingsDashboardStatusTone.Warning),
            SettingsDashboardStatusPresentation.mcp(listOf(McpStatus.Connected, McpStatus.NeedsAuthorization)),
        )
    }

    @Test
    fun `MCP error takes priority over authorization without exposing details`() {
        assertEquals(
            ready("Ошибка подключения", SettingsDashboardStatusTone.Error),
            SettingsDashboardStatusPresentation.mcp(listOf(
                McpStatus.NeedsAuthorization,
                McpStatus.Error("Private endpoint", "Private credentials"),
            )),
        )
    }

    @Test
    fun `storage with no files and no bytes has no badge`() {
        assertEquals(SettingsDashboardStatus.Ready(), SettingsDashboardStatusPresentation.storage(0, 0))
    }

    @Test
    fun `storage retains files with zero size and size with zero files`() {
        assertEquals(ready("Файлов: 2"), SettingsDashboardStatusPresentation.storage(2, 0))
        assertEquals(ready("1,5 КБ"), SettingsDashboardStatusPresentation.storage(0, 1536))
        assertEquals(ready("Файлов: 4 · 2 МБ"), SettingsDashboardStatusPresentation.storage(4, 2 * MIB))
    }

    @Test
    fun `byte formatting uses deterministic Russian units and binary boundaries`() {
        val samples = mapOf(
            0L to "0 Б",
            1L to "1 Б",
            1023L to "1 023 Б",
            1024L to "1 КБ",
            1536L to "1,5 КБ",
            MIB - 1 to "1 024 КБ",
            MIB to "1 МБ",
            MIB + MIB / 2 to "1,5 МБ",
            GIB - 1 to "1 024 МБ",
            GIB to "1 ГБ",
            GIB + GIB / 2 to "1,5 ГБ",
            1024 * GIB to "1 024 ГБ",
            Long.MAX_VALUE to "8 589 934 592 ГБ",
        )
        samples.forEach { (bytes, expected) ->
            assertEquals("Size for $bytes bytes", expected, SettingsDashboardStatusPresentation.formatSize(bytes))
        }
    }

    @Test
    fun `byte formatting rounds halves up to one decimal`() {
        assertEquals("1,3 КБ", SettingsDashboardStatusPresentation.formatSize(1280))
        assertEquals("1,2 КБ", SettingsDashboardStatusPresentation.formatSize(1279))
    }

    @Test
    fun `backup without timestamp has no badge`() {
        assertEquals(
            SettingsDashboardStatus.Ready(),
            SettingsDashboardStatusPresentation.backup(0, TimeZone.getTimeZone("UTC")),
        )
    }

    @Test
    fun `backup keeps date in the supplied time zone`() {
        val timestamp = 86_399_000L
        assertEquals(
            ready("01.01.1970"),
            SettingsDashboardStatusPresentation.backup(timestamp, TimeZone.getTimeZone("UTC")),
        )
        assertEquals(
            ready("02.01.1970"),
            SettingsDashboardStatusPresentation.backup(timestamp, TimeZone.getTimeZone("GMT+03:00")),
        )
    }

    private fun ready(text: String, tone: SettingsDashboardStatusTone = SettingsDashboardStatusTone.Neutral) =
        SettingsDashboardStatus.Ready(SettingsDashboardStatusChip(text, tone))

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = MIB * 1024L
    }
}
