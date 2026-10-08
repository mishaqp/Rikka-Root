package me.rerere.rikkahub.data.ai.mcp.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class McpControlSecretStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `references survive reload and encrypted files contain no plaintext`() {
        val directory = temporary.newFolder()
        val vault = McpControlSecretStore(directory) { key }
        val secret = "Bearer sk-unique-canary-secret-123456789"
        val reference = vault.put(secret)
        assertTrue(McpControlSecretStore.isReference(reference))
        assertFalse(reference.contains(secret))
        assertEquals(secret, McpControlSecretStore(directory) { key }.resolve(reference))
        assertEquals(1, directory.listFiles()!!.size)
        assertFalse(directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        assertFalse(directory.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun `unique references use different authenticated ciphertext`() {
        val directory = temporary.newFolder()
        val vault = McpControlSecretStore(directory) { key }
        val first = vault.put("same-secret")
        val second = vault.put("same-secret")
        assertNotEquals(first, second)
        val files = directory.listFiles()!!
        assertFalse(files[0].readBytes().contentEquals(files[1].readBytes()))
    }

    @Test fun `missing wrong key and tampered ciphertext never return plaintext`() {
        val directory = temporary.newFolder()
        val vault = McpControlSecretStore(directory) { key }
        val reference = vault.put("secret-to-protect")
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(runCatching { McpControlSecretStore(directory) { wrongKey }.resolve(reference) }.isFailure)
        val file = File(directory, reference.removePrefix(McpControlSecretStore.REFERENCE_PREFIX) + ".enc")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertTrue(runCatching { vault.resolve(reference) }.isFailure)
        file.delete()
        val message = runCatching { vault.resolve(reference) }.exceptionOrNull()!!.message!!
        assertTrue(message.contains("настройках MCP"))
        assertFalse(message.contains("secret-to-protect"))
    }

    @Test fun `invalid references cannot read files outside vault`() {
        val vault = McpControlSecretStore(temporary.newFolder()) { key }
        for (value in listOf("", "rikka-keystore:mcp:../../outside", "rikka-keystore:ssh:123", "Bearer secret")) {
            assertFalse(McpControlSecretStore.isReference(value))
            assertTrue(runCatching { vault.resolve(value) }.isFailure)
        }
        assertTrue(runCatching { vault.put("secret\r\nInjected: header") }.isFailure)
    }
}
