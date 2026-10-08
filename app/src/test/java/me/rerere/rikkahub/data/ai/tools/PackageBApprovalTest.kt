package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class PackageBApprovalTest {
    @get:Rule val folder = TemporaryFolder()
    private val names = listOf("set_torch", "vibrate", "get_brightness", "set_brightness",
        "get_volume", "set_volume", "set_wallpaper", "nfc_read_tag", "nfc_write_tag")
    private val backupNames = listOf("torch", "vibrate", "brightness", "volume", "wallpaper", "nfc")
    private val input = Json.parseToJsonElement("{}")

    @Test fun agentBackupsRetainExactNamesAndDefaultsStayOff() {
        val restored = Json.decodeFromString<Assistant>("{}").localTools
        assertEquals(listOf(LocalToolOption.TimeInfo), restored)
        backupNames.forEach { name ->
            val encoded = "{\"type\":\"$name\"}"
            val option = Json.decodeFromString<LocalToolOption>(encoded)
            assertEquals(encoded, Json.encodeToString<LocalToolOption>(option))
            assertFalse(option in restored)
        }
    }

    @Test fun allEquipmentAndNfcReadsUseTheForkApprovalGate() {
        names.forEach { name ->
            assertTrue(name, name in ToolPermissionPolicy.registry)
            val tool = ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() }))
            assertTrue(name, tool.needsApproval(input))
        }
    }

    @Test fun equipmentGrantsRemainChatScopedAndRevocable() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            val chat = Uuid.random()
            val other = Uuid.random()
            try {
                names.forEach { name ->
                    assertFalse(resolveToolAutoApproval(fixture.preferences, chat, name, false))
                    ToolApprovalAllowList.grantForChat(chat, name)
                    assertTrue(resolveToolAutoApproval(fixture.preferences, chat, name, false))
                    assertFalse(resolveToolAutoApproval(fixture.preferences, other, name, false))
                    ToolApprovalAllowList.revokeForChat(chat, name)
                    fixture.preferences.grantAlways(name)
                    assertTrue(resolveToolAutoApproval(fixture.preferences, other, name, false))
                    fixture.preferences.revoke(name)
                    assertFalse(resolveToolAutoApproval(fixture.preferences, chat, name, false))
                }
                fixture.preferences.setYolo(true)
                names.forEach { assertTrue(resolveToolAutoApproval(fixture.preferences, chat, it, false)) }
                fixture.preferences.setAskAfterWebContent(true)
                names.forEach { assertFalse(resolveToolAutoApproval(fixture.preferences, chat, it, true)) }
            } finally { ToolApprovalAllowList.clearChat(chat) }
        }
    }
}
