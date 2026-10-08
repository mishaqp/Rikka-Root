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

class PackageCApprovalTest {
    @get:Rule val folder = TemporaryFolder()
    private val names = listOf("get_location", "list_contacts", "search_contacts", "list_call_log", "list_sms_inbox", "search_sms", "send_sms",
        "take_photo", "record_audio", "speech_to_text", "verify_fingerprint", "keystore_generate_key", "keystore_sign", "keystore_verify",
        "keystore_encrypt", "keystore_decrypt", "keystore_delete_key", "keystore_list_keys")
    private val backupNames = listOf("location", "contacts", "call_log", "sms_inbox", "sms_send", "camera_photo", "mic_recorder", "speech_to_text", "fingerprint", "keystore")
    private val input = Json.parseToJsonElement("{}")

    @Test fun agentBackupNamesRoundTripAndEveryPersonalFeatureDefaultsOff() {
        val defaults = Json.decodeFromString<Assistant>("{}").localTools
        assertEquals(listOf(LocalToolOption.TimeInfo), defaults)
        backupNames.forEach { name ->
            val encoded = "{\"type\":\"$name\"}"
            val option = Json.decodeFromString<LocalToolOption>(encoded)
            assertEquals(encoded, Json.encodeToString<LocalToolOption>(option))
            assertFalse(option in defaults)
        }
    }

    @Test fun allPersonalReadsAndCryptoOperationsRequireApproval() {
        names.forEach { name ->
            assertTrue(name, name in ToolPermissionPolicy.registry)
            assertTrue(name, ToolPermissionPolicy.apply(Tool(name, "test", execute = { emptyList() })).needsApproval(input))
        }
    }

    @Test fun personalGrantsAreRevocableAndChatScopedAndWebContentRestoresTheGate() = runBlocking {
        ToolApprovalTestStore(folder.newFolder()).use { fixture ->
            val chat = Uuid.random(); val other = Uuid.random()
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
