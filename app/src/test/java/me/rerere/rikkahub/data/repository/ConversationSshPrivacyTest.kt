package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.data.ai.mcp.control.McpToolSecretSanitizer
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import me.rerere.rikkahub.data.ssh.sanitizeSshToolMessages
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

@Suppress("DEPRECATION")
class ConversationSshPrivacyTest {
    @get:Rule val folder = TemporaryFolder()
    private val sanitizer by lazy { SshToolSecretSanitizer(SshCredentialStore(folder.root.resolve("ssh_credentials.enc"), Json) {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    }) }
    private val args = """{"host":"server","user":"me","command":"whoami","password":"private-password","private_key":"private-key","passphrase":"private-passphrase"}"""
    private fun conversation() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call", "ssh_exec", args,
            approvalState = ToolApprovalState.Pending))),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.ToolCall("call", "save_ssh_host", args))),
        UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.ToolResult("call", "ssh_exec",
            Json.parseToJsonElement("{}"), Json.parseToJsonElement(args)))),
    ))))

    @Test fun `serialized conversation stores opaque refs and pending call can resume after reload`() {
        val live = conversation()
        val safe = sanitizeSshConversation(live, sanitizer)
        val persisted = JsonInstant.encodeToString(safe)
        listOf("private-password", "private-key", "private-passphrase").forEach { assertFalse(persisted.contains(it)) }
        assertTrue(persisted.contains("rikka-ssh-secret:"))
        val reloaded = JsonInstant.decodeFromString<Conversation>(persisted)
        val pending = reloaded.messageNodes.single().messages.first().parts.single() as UIMessagePart.Tool
        assertEquals(ToolApprovalState.Pending, pending.approvalState)
        assertEquals(Json.parseToJsonElement(args), sanitizer.resolveArgs("ssh_exec", pending.inputAsJson()))
        assertEquals(args, (live.messageNodes.single().messages.first().parts.single() as UIMessagePart.Tool).input)
        assertEquals(persisted, JsonInstant.encodeToString(sanitizeSshConversation(safe, sanitizer)))
    }

    @Test fun `missing sanitizer fails closed for all current and legacy tool args`() {
        val safe = sanitizeSshConversation(conversation(), null)
        val persisted = JsonInstant.encodeToString(safe)
        listOf("private-password", "private-key", "private-passphrase", "rikka-ssh-secret:").forEach {
            assertFalse(persisted.contains(it))
        }
        assertTrue(persisted.contains("whoami"))
    }

    @Test fun `export drops credentials and opaque refs even from live or persisted messages`() {
        val live = conversation().messageNodes.single().messages
        val persisted = sanitizeSshToolMessages(live, sanitizer::sanitizeForPersistence)
        listOf(live, persisted).forEach { messages ->
            val exported = JsonInstant.encodeToString(sanitizeSshToolMessages(messages))
            listOf("private-password", "private-key", "private-passphrase", "rikka-ssh-secret:").forEach {
                assertFalse(exported.contains(it))
            }
            assertTrue(exported.contains("whoami"))
        }
    }

    @Test fun `streaming incomplete input or tool name is removed from persistent snapshot`() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call1", "ssh_exec", """{"password":"private-password"""),
            UIMessagePart.Tool("call2", "ssh_", args),
            UIMessagePart.Tool("call3", "", args),
        )))
        val safe = JsonInstant.encodeToString(sanitizeSshToolMessages(messages, sanitizer::sanitizeForPersistence))
        assertFalse(safe.contains("private-password"))
        assertFalse(safe.contains("private-key"))
    }

    @Test fun `MCP headers share the persistence and export boundary with SSH`() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val mcpStore = McpControlSecretStore(folder.root.resolve("mcp_headers")) { key }
        val mcpSecrets = McpToolSecretSanitizer(mcpStore)
        val input = """{"name":"server","url":"https://public.example/mcp","headers":[{"name":"Authorization","value":"Bearer private-token"}]}"""
        val live = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call", "mcp_add", input)))
        ))))
        val safe = sanitizeSshConversation(live, sanitizer, mcpSecrets)
        val encoded = JsonInstant.encodeToString(safe)
        assertFalse(encoded.contains("private-token"))
        assertTrue(encoded.contains(McpControlSecretStore.REFERENCE_PREFIX))
        val fallback = JsonInstant.encodeToString(sanitizeSshConversation(live, null))
        assertFalse(fallback.contains("private-token"))
        assertFalse(fallback.contains(McpControlSecretStore.REFERENCE_PREFIX))
        val exported = JsonInstant.encodeToString(sanitizeSshToolMessages(safe.messageNodes.single().messages))
        assertFalse(exported.contains("private-token"))
        assertFalse(exported.contains(McpControlSecretStore.REFERENCE_PREFIX))
    }
}
