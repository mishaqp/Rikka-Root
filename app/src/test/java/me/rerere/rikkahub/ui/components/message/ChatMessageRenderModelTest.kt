package me.rerere.rikkahub.ui.components.message

import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production render grouping used by MessagePartsBlock. */
class ChatMessageRenderModelTest {
    private val reasoning = UIMessagePart.Reasoning("Plan", createdAt = Instant.parse("2026-01-02T03:04:05Z"))
    private val tool = UIMessagePart.Tool("search-1", "search_web", "{}")
    private val serverTool = UIMessagePart.ServerTool("server-1", "web_search", status = ServerToolStatus.COMPLETED)
    private val text = UIMessagePart.Text("Answer")

    @Test fun consecutiveReasoningAndBothKindsOfToolsShareAThinkingBlockInOriginalOrder() {
        assertEquals(
            listOf(MessagePartBlock.ThinkingBlock(listOf(
                ThinkingStep.ReasoningStep(reasoning),
                ThinkingStep.ToolStep(tool),
                ThinkingStep.ServerToolStep(serverTool),
            ))),
            listOf(reasoning, tool, serverTool).groupMessageParts(),
        )
    }

    @Test fun textAndAttachmentsSplitThinkingBlocksWithoutMovingOrLosingParts() {
        val image = UIMessagePart.Image("file:///image.png")
        val document = UIMessagePart.Document("file:///manual.pdf", "manual.pdf", "application/pdf")
        val video = UIMessagePart.Video("file:///clip.mp4")
        val audio = UIMessagePart.Audio("file:///voice.ogg")
        assertEquals(listOf(
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ReasoningStep(reasoning))),
            MessagePartBlock.ContentBlock(text, 1),
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ToolStep(tool))),
            MessagePartBlock.ContentBlock(image, 3),
            MessagePartBlock.ContentBlock(document, 4),
            MessagePartBlock.ContentBlock(video, 5),
            MessagePartBlock.ContentBlock(audio, 6),
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ServerToolStep(serverTool))),
        ), listOf(reasoning, text, tool, image, document, video, audio, serverTool).groupMessageParts())
    }

    @Test fun multipleTextAndReasoningSegmentsRetainTheirSemanticSequence() {
        val nextReasoning = reasoning.copy(reasoning = "Reconsider", createdAt = reasoning.createdAt + kotlin.time.Duration.parse("1s"))
        val nextText = UIMessagePart.Text("Final answer")
        assertEquals(listOf(
            MessagePartBlock.ContentBlock(text, 0),
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ReasoningStep(reasoning))),
            MessagePartBlock.ContentBlock(nextText, 2),
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ReasoningStep(nextReasoning))),
        ), listOf(text, reasoning, nextText, nextReasoning).groupMessageParts())
    }

    @Test fun successfulChartRendersInPlaceAndSplitsTheThinkingChain() {
        val chart = tool.copy(toolName = CHART_DISPLAY_TOOL_NAME, output = listOf(UIMessagePart.Text("{\"success\":true}")))
        assertEquals(listOf(
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ReasoningStep(reasoning))),
            MessagePartBlock.ChartBlock(chart, 1),
            MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ServerToolStep(serverTool))),
            MessagePartBlock.ContentBlock(text, 3),
        ), listOf(reasoning, chart, serverTool, text).groupMessageParts())
    }

    @Test fun pendingFailedMalformedAndNonChartToolsRemainInspectableThinkingSteps() {
        val chart = tool.copy(toolName = CHART_DISPLAY_TOOL_NAME)
        listOf(
            chart,
            chart.copy(approvalState = ToolApprovalState.Pending),
            chart.copy(output = listOf(UIMessagePart.Text("{\"success\":false}"))),
            chart.copy(output = listOf(UIMessagePart.Text("{\"error\":\"Unavailable\"}"))),
            chart.copy(output = listOf(UIMessagePart.Text("Invalid JSON"))),
            chart.copy(output = listOf(UIMessagePart.Text("[]"))),
            chart.copy(output = listOf(UIMessagePart.Image("file:///chart.png"))),
            tool.copy(output = listOf(UIMessagePart.Text("{\"success\":true}"))),
        ).forEach { part ->
            assertEquals(listOf(MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ToolStep(part)))), listOf(part).groupMessageParts())
        }
    }

    @Test fun emptyMessageHasNoBlocksAndBlankContentIsStillPreservedForTheRenderer() {
        assertEquals(emptyList<MessagePartBlock>(), emptyList<UIMessagePart>().groupMessageParts())
        val blank = UIMessagePart.Text("  ")
        assertEquals(listOf(MessagePartBlock.ContentBlock(blank, 0)), listOf(blank).groupMessageParts())
    }

    @Test fun actualVisibilityPredicateRecognizesContentAndToolsForActionAvailability() {
        assertTrue(emptyList<UIMessagePart>().isEmptyUIMessage())
        val blankParts = listOf(
            UIMessagePart.Text("\n "), UIMessagePart.Reasoning(""), UIMessagePart.Image(""),
            UIMessagePart.Document("", "manual.pdf"), UIMessagePart.Audio(""), UIMessagePart.Video(""),
        )
        assertTrue(blankParts.isEmptyUIMessage())
        listOf<UIMessagePart>(
            text, reasoning, tool, serverTool,
            UIMessagePart.Image("file:///image.png"), UIMessagePart.Document("file:///manual.pdf", "manual.pdf"),
            UIMessagePart.Audio("file:///voice.ogg"), UIMessagePart.Video("file:///clip.mp4"),
        ).forEach { part -> assertFalse("${part::class.simpleName} must keep message actions available", listOf(part).isEmptyUIMessage()) }
    }

    @Suppress("DEPRECATION")
    @Test fun legacyPartsRemainContentBlocksForTheProductionRenderersFallback() {
        val legacy = listOf(
            UIMessagePart.ToolCall("legacy-1", "search_web", "{}"),
            UIMessagePart.ToolResult("legacy-1", "search_web", JsonPrimitive("Result"), JsonObject(emptyMap())),
            UIMessagePart.Search,
        )
        assertEquals(legacy.mapIndexed { index, part -> MessagePartBlock.ContentBlock(part, index) }, legacy.groupMessageParts())
        assertTrue(legacy.isEmptyUIMessage())
    }
}
