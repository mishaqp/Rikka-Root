package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class PackageAApprovalTest {
    @get:Rule val folder = TemporaryFolder()
    private val outputs = listOf("show_toast", "post_notification", "share")
    private val input = Json.parseToJsonElement("{}")

    @Test fun outputAlwaysUsesTheForkApprovalGate() {
        outputs.forEach { name ->
            assertTrue(name, name in ToolPermissionPolicy.registry)
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }))
            assertTrue(name, tool.needsApproval(input))
        }
    }

    @Test fun deviceInformationDoesNotAskForApproval() {
        listOf("get_battery_status", "get_audio_info", "get_telephony_info", "get_wifi_info",
            "list_sensors", "read_sensor", "get_storage_info").forEach { name ->
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }))
            assertFalse(name, tool.needsApproval(input))
        }
    }

    @Test fun outputGrantsCanBeRevokedAndChatGrantsStayInTheirChat() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            val chat = Uuid.random()
            val otherChat = Uuid.random()
            try {
                for (name in outputs) {
                    assertFalse(resolveToolAutoApproval(fixture.preferences, chat, name, false))
                    ToolApprovalAllowList.grantForChat(chat, name)
                    assertTrue(resolveToolAutoApproval(fixture.preferences, chat, name, false))
                    assertFalse(resolveToolAutoApproval(fixture.preferences, otherChat, name, false))
                    ToolApprovalAllowList.revokeForChat(chat, name)
                    fixture.preferences.grantAlways(name)
                    assertTrue(resolveToolAutoApproval(fixture.preferences, otherChat, name, false))
                    fixture.preferences.revoke(name)
                    assertFalse(resolveToolAutoApproval(fixture.preferences, chat, name, false))
                }
                fixture.preferences.setYolo(true)
                outputs.forEach { assertTrue(resolveToolAutoApproval(fixture.preferences, chat, it, false)) }
                fixture.preferences.setAskAfterWebContent(true)
                outputs.forEach { assertFalse(resolveToolAutoApproval(fixture.preferences, chat, it, true)) }
            } finally { ToolApprovalAllowList.clearChat(chat) }
        }
    }
}
