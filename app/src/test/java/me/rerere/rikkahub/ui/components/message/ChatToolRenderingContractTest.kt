package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the real registry, so removing a specialized renderer causes a test failure. */
class ChatToolRenderingContractTest {
    @Test fun allSeventeenSpecializedToolNamesResolveToTheirProductionRenderers() {
        listOf(
            "memory_tool", "search_web", "scrape_web", "get_time_info", "clipboard_tool",
            "text_to_speech", "get_screen_time", "calendar_query", "calendar_create", "chart_display",
            "use_skill", "recent_chats", "conversation_search", "workspace_edit_file",
            "workspace_read_file", "workspace_write_file", "workspace_shell",
        ).forEach { name -> assertEquals("Missing specialized renderer for $name", name, ToolUIRegistry.resolve(name).toolName) }
    }

    @Test fun toolsWithoutSpecializedUiAndUnknownFutureToolsKeepTheGenericFallback() {
        val generic = ToolUIRegistry.resolve("a_future_tool_name")
        assertEquals("", generic.toolName)
        listOf("root_shell", "generate_bug_report", "browser_eval_js", "mcp_configure", "ask_user", "").forEach { name ->
            assertSame("$name must remain displayable using the generic tool renderer", generic, ToolUIRegistry.resolve(name))
        }
    }

    @Test fun askUserAndSuccessfulChartsRetainTheirDedicatedMessageRenderRoutes() {
        val tools = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/ChatMessageTools.kt", "ChatMessageToolStep"))
        assertTrue(tools.contains(ChatProductionSource.canonical("if (tool.toolName == ASK_USER_TOOL_NAME) { AskUserToolStep(tool = tool, loading = loading, fontSizeRatio = fontSizeRatio, onToolAnswer = onToolAnswer) return }")))
        val message = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/ChatMessage.kt", "MessagePartsBlock"))
        assertTrue(message.contains(ChatProductionSource.canonical("is MessagePartBlock.ChartBlock")))
        assertTrue(message.contains(ChatProductionSource.canonical("spec?.let { ChartCard(spec = it) }")))
    }

    @Test fun toolSummaryAndDetailsKeepSkillWebviewsAndGenericUnparseableOutputs() {
        val body = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/ChatMessageTools.kt", "ChatMessageToolStep"))
        listOf(
            "renderer.Summary(context)", "webviewParts.forEach { SkillWebviewCardOrNull(it) }",
            "val outputUnparsable = tool.isExecuted && outputJson == null", "DefaultToolPreview(", "renderer.Preview(",
        ).forEach { fragment -> assertTrue("Tool rendering lost $fragment", body.contains(ChatProductionSource.canonical(fragment))) }
    }

    @Test fun expandedGenericAndServerCardsNeverDumpJsonEnvelopes() {
        val generic = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/tools/ToolUI.kt", "DefaultToolPreview"))
        val server = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/ChatMessageTools.kt", "ChatMessageServerToolStep"))
        listOf("JsonInstantPretty.encodeToString", "language = \"json\"").forEach { raw ->
            assertFalse("Generic details still display raw JSON", generic.contains(ChatProductionSource.canonical(raw)))
            assertFalse("Server details still display raw JSON", server.contains(ChatProductionSource.canonical(raw)))
        }
    }

    @Test fun expandedErrorsUseTheReadablePreviewWithoutEllipsis() {
        val tools = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/ChatMessageTools.kt", "ChatMessageToolStep"))
        assertTrue("Failed results must bypass specialized JSON details", tools.contains(ChatProductionSource.canonical("if (resultPreview is ToolResultPreview.Error)")))
        val preview = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/ChatMessageTools.kt", "ToolResultPreviewText"))
        val errorBranch = preview.substringAfter("isToolResultPreview.Error->").substringBefore("isToolResultPreview.Summary->")
        assertFalse("Partial failure warnings must not be ellipsized", errorBranch.contains("maxLines="))
        assertFalse("Partial failure warnings must not be ellipsized", errorBranch.contains("overflow="))
    }

    @Test fun mcpFailureDoesNotSuppressAllOriginalTextParts() {
        val generic = ChatProductionSource.canonical(ChatProductionSource.function("ui/components/message/tools/ToolUI.kt", "DefaultToolPreview"))
        assertFalse("Partial effects reported by the server must remain visible", generic.contains(
            ChatProductionSource.canonical("is UIMessagePart.Text -> if (resultPreview !is ToolResultPreview.Error)")))
    }
}
