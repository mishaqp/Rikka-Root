package me.rerere.rikkahub.data.kimi

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class KimiCredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = folder.root.resolve("kimi_session.enc")
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun store() = KimiCredentialStore(file, Json { ignoreUnknownKeys = true }) { key }
    private fun account() = KimiAccount(region = KimiRegion.GLOBAL,
        tokens = KimiTokens("synthetic-access", "synthetic-refresh", Long.MAX_VALUE))

    @Test fun `encrypted session round trips and does not contain plaintext credentials`() {
        val account = account()
        store().write(account)
        assertEquals(account, store().read())
        val content = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(content.contains("synthetic-access"))
        assertFalse(content.contains("synthetic-refresh"))
        assertFalse(account.toString().contains("synthetic-access"))
    }

    @Test fun `failed atomic update preserves prior credentials`() {
        val initial = account()
        store().write(initial)
        folder.root.resolve("kimi_session.enc.tmp").mkdir()
        assertTrue(runCatching { store().write(initial.copy(name = "replacement")) }.isFailure)
        assertEquals(initial, store().read())
    }

    @Test fun `logout persists and wrong keystore key does not expose a session`() {
        store().write(account())
        val wrong = KimiCredentialStore(file, Json) { SecretKeySpec(ByteArray(32) { 42 }, "AES") }
        assertNull(wrong.read())
        store().write(null)
        assertNull(store().read())
    }
}
