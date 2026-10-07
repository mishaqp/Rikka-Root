package me.rerere.rikkahub.data.codex

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class CodexCredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun `encrypted account persistence round trips and replaces previous snapshot`() {
        val file = folder.root.resolve("codex_accounts.enc")
        val store = CodexCredentialStore(file, json) { key }
        val original = CodexAccountState(listOf(account()), nextAccountIndex = 2)
        store.write(original)
        assertEquals(original, CodexCredentialStore(file, json) { key }.read())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("private-access"))
        store.write(original.copy(accounts = emptyList(), nextAccountIndex = 0))
        assertEquals(CodexAccountState(), store.read())
        assertFalse(folder.root.resolve("codex_accounts.enc.tmp").exists())
    }

    @Test
    fun `failed encryption leaves last committed credentials intact`() {
        val file = folder.root.resolve("codex_accounts.enc")
        val store = CodexCredentialStore(file, json) { key }
        val original = CodexAccountState(listOf(account()))
        store.write(original)
        val committed = file.readBytes()
        try {
            CodexCredentialStore(file, json) { SecretKeySpec(ByteArray(3), "AES") }.write(CodexAccountState())
            fail("Invalid encryption key must fail")
        } catch (_: java.security.InvalidKeyException) {
            assertArrayEquals(committed, file.readBytes())
            assertEquals(original, store.read())
        }
    }

    @Test
    fun `corrupt or unavailable encrypted credentials do not become usable accounts`() {
        val file = folder.root.resolve("codex_accounts.enc")
        val store = CodexCredentialStore(file, json) { key }
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(CodexAccountState(), store.read())
        store.write(CodexAccountState(listOf(account())))
        val wrongKey = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        assertEquals(CodexAccountState(), CodexCredentialStore(file, json) { wrongKey }.read())
    }

    private fun account() = CodexAccount(id = "user:account", name = "User", chatgptAccountId = "account",
        accessToken = "private-access", refreshToken = "private-refresh", expiresAt = Long.MAX_VALUE)
}
