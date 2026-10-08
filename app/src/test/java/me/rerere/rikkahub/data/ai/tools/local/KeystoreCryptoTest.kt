package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.KeyGenerator

class KeystoreCryptoTest {
    @Test fun binarySecretsAreReadableAfterARealEncryptionRoundTrip() {
        val crypto=KeystoreCrypto(MemoryStore())
        crypto.generate("binary","aes_256_gcm",listOf("encrypt","decrypt"))
        val sealed=crypto.encrypt("binary",byteArrayOf(0,1,2,3))
        val plain=crypto.decrypt("binary",sealed.ciphertext,sealed.iv)
        try { assertEquals("AAECAw==",formatProtectedSecret(plain)) } finally { plain.fill(0) }
        assertEquals("Секрет\nвторая строка",formatProtectedSecret("Секрет\nвторая строка".toByteArray()))
        assertEquals("/w==",formatProtectedSecret(byteArrayOf(-1)))
    }
    private class MemoryStore : ToolKeyStore {
        val entries = mutableMapOf<String, ToolKeyMaterial>()
        var generated: ToolKeySpec? = null
        override fun contains(alias: String) = alias in entries
        override fun aliases() = entries.keys.toList()
        override fun entry(alias: String) = entries[alias]
        override fun delete(alias: String) { entries.remove(alias) }
        override fun generate(alias: String, spec: ToolKeySpec): ToolKeyMetadata {
            generated = spec
            val meta = ToolKeyMetadata(spec.type, spec.purposes, false)
            entries[alias] = when (spec.type) {
                ToolKeyType.RSA -> {
                    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                    ToolKeyMaterial(meta, pair.private, pair.public, null)
                }
                ToolKeyType.AES -> ToolKeyMaterial(meta, null, null, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            }
            return meta
        }
    }

    @Test fun requestedPurposesArePreservedAndOverwriteNeverDestroysTheOldKey() {
        val store = MemoryStore(); val crypto = KeystoreCrypto(store)
        crypto.generate("one", "aes_256_gcm", listOf("encrypt"))
        assertEquals(setOf(ToolKeyPurpose.ENCRYPT), store.generated!!.purposes)
        val material = store.entries.values.single()
        assertThrows(ToolKeyFailure::class.java) { crypto.generate("one", "aes_256_gcm", listOf("encrypt", "decrypt")) }
        assertSame(material, store.entries.values.single())
        val sealed = crypto.encrypt("one", byteArrayOf(1, 2))
        assertThrows(ToolKeyFailure::class.java) { crypto.decrypt("one", sealed.ciphertext, sealed.iv) }
        assertThrows(IllegalArgumentException::class.java) { validateToolKeySpec("rsa_2048", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { validateToolKeySpec("rsa_2048", listOf("sign", "decrypt")) }
        assertThrows(IllegalArgumentException::class.java) { validateToolKeySpec("aes_256_gcm", listOf("encrypt", "encrypt")) }
    }

    @Test fun rsaSignaturesVerifyAndRejectTamperedData() {
        val crypto = KeystoreCrypto(MemoryStore())
        crypto.generate("rsa", "rsa_2048", listOf("sign", "verify"))
        val data = "публичные данные".toByteArray()
        val signature = crypto.sign("rsa", data)
        assertTrue(crypto.verify("rsa", data, signature))
        assertFalse(crypto.verify("rsa", byteArrayOf(1), signature))
        signature[4] = (signature[4].toInt() xor 1).toByte()
        assertFalse(crypto.verify("rsa", data, signature))
    }

    @Test fun aesUsesFreshIvAuthenticatesCiphertextAndRoundTripsRealBytes() {
        val crypto = KeystoreCrypto(MemoryStore())
        crypto.generate("aes", "aes_256_gcm", listOf("encrypt", "decrypt"))
        val plain = byteArrayOf(0, 1, 2, -1, 10)
        val first = crypto.encrypt("aes", plain); val second = crypto.encrypt("aes", plain)
        assertEquals(12, first.iv.size); assertFalse(first.iv.contentEquals(second.iv))
        assertArrayEquals(plain, crypto.decrypt("aes", first.ciphertext, first.iv))
        first.ciphertext[0] = (first.ciphertext[0].toInt() xor 1).toByte()
        assertThrows(ToolKeyFailure::class.java) { crypto.decrypt("aes", first.ciphertext, first.iv) }
        assertThrows(IllegalArgumentException::class.java) { crypto.decrypt("aes", second.ciphertext, ByteArray(8)) }
    }

    @Test fun listAndDeleteCannotReachOtherApplicationKeysEvenWithTheirAlias() {
        val store = MemoryStore()
        store.generate("codex_oauth_key", validateToolKeySpec("aes_256_gcm", listOf("encrypt", "decrypt")))
        val crypto = KeystoreCrypto(store)
        assertTrue(crypto.list().isEmpty())
        assertThrows(ToolKeyFailure::class.java) { crypto.delete("codex_oauth_key") }
        assertTrue(store.contains("codex_oauth_key"))
        crypto.generate("public", "aes_256_gcm", listOf("encrypt", "decrypt"))
        assertEquals(listOf("public"), crypto.list().map { it.alias })
        crypto.delete("public")
        assertTrue(crypto.list().isEmpty()); assertTrue(store.contains("codex_oauth_key"))
    }

    @Test fun aliasesAndBase64AreStrictAndBoundedBeforeAllocatingDecodedInput() {
        listOf("", "../x", "x y", "x/", "я", "a".repeat(65)).forEach {
            assertThrows(IllegalArgumentException::class.java) { validateToolKeyAlias(it) }
        }
        assertEquals("a_-1", validateToolKeyAlias("a_-1"))
        assertArrayEquals(byteArrayOf(1, 2), decodeToolBase64("AQI=", 2, "data"))
        listOf("!!!!", "AQ I=", "A".repeat(100)).forEach {
            assertThrows(IllegalArgumentException::class.java) { decodeToolBase64(it, 2, "data") }
        }
        assertThrows(IllegalArgumentException::class.java) { decodeToolBase64(Base64.getEncoder().encodeToString(ByteArray(65537)), 65536, "data") }
        assertThrows(IllegalArgumentException::class.java) { validateToolIv(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { validateToolIv(ByteArray(13)) }
    }
}
