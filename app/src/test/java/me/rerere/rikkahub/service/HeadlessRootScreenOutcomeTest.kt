package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.CronRunPolicy
import org.junit.Assert.*
import org.junit.Test

class HeadlessRootScreenOutcomeTest {
    private val tools = listOf("tap", "long_press", "swipe", "read_window_tree", "find_node", "click_node", "set_text", "scroll", "global_action", "take_screenshot", "wake_screen")

    @Test fun `uncertain root screen outcomes stay indeterminate in background tasks`() {
        for (name in tools) for (code in listOf("command_timeout", "root_cleanup_unconfirmed", "root_execution_indeterminate")) {
            val output = listOf(UIMessagePart.Text(buildJsonObject { put("error", code) }.toString()))
            assertEquals(name, HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, code), classifyHeadlessToolFailure(name, output))
        }
    }

    @Test fun `root permission failures stay blocked in screen background tasks`() {
        for (name in tools) for (code in listOf("root_interaction_required", "root_approval_required", "root_not_granted", "root_command_blocked")) {
            val output = listOf(UIMessagePart.Text(buildJsonObject { put("error", code) }.toString()))
            assertEquals(name, HeadlessToolFailure(HeadlessTaskStatus.BLOCKED, code), classifyHeadlessToolFailure(name, output))
        }
    }

    @Test fun `screenshot wrapper preserves the actual native root timeout category`() {
        val output = listOf(UIMessagePart.Text(buildJsonObject {
            put("error", "screenshot_unavailable")
            put("root_result", buildJsonObject { put("error", "command_timeout"); put("stdout", "partial") })
        }.toString()))
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.INDETERMINATE, "command_timeout"), classifyHeadlessToolFailure("take_screenshot", output))
    }

    @Test fun `revoked screen permission is blocked rather than a command failure`() {
        val output = listOf(UIMessagePart.Text(buildJsonObject { put("error", "screen_permission_revoked") }.toString()))
        for (name in tools) {
            assertEquals(name, HeadlessToolFailure(HeadlessTaskStatus.BLOCKED, "screen_permission_revoked"), classifyHeadlessToolFailure(name, output))
        }
    }

    @Test fun `an auxiliary command result cannot mask the native envelope error`() {
        val output = listOf(UIMessagePart.Text(buildJsonObject {
            put("error", "root_command_blocked")
            put("root_result", buildJsonObject { put("success", true); put("exit_code", 0) })
        }.toString()))
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.BLOCKED, "root_command_blocked"), classifyHeadlessToolFailure("set_text", output))
    }

    @Test fun `screen revocation code survives scheduled job history sanitization`() {
        assertEquals("screen_permission_revoked", CronRunPolicy.safeOutcome("screen_permission_revoked"))
        assertEquals("execution_failed", CronRunPolicy.safeOutcome("screen_permission_revoked: private details"))
    }

    @Test fun `ordinary failures remain failed and output text cannot manufacture a native outcome`() {
        val ordinary = listOf(UIMessagePart.Text(buildJsonObject {
            put("success", false); put("error", "root_input_failed"); put("reason", "command_timeout")
            put("root_result", buildJsonObject { put("exit_code", 1); put("stderr", "root_cleanup_unconfirmed") })
        }.toString()))
        assertEquals(HeadlessToolFailure(HeadlessTaskStatus.FAILED, "tool_failed"), classifyHeadlessToolFailure("tap", ordinary))
        val accepted = listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("after", buildJsonObject { put("tree_error", "command_timeout") })
        }.toString()))
        assertNull(classifyHeadlessToolFailure("tap", accepted))
        assertNull(classifyHeadlessToolFailure("unregistered_reader", ordinary))
    }
}
