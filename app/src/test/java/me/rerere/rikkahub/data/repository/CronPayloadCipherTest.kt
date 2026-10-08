package me.rerere.rikkahub.data.repository

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class CronPayloadCipherTest {
    private val cipher = AesGcmCronPayloadCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    @Test fun sensitivePromptIsEncryptedWithRandomIv() {
        val a = cipher.seal("job", "ab password=secret prompt")
        val b = cipher.seal("job", "ab password=secret prompt")
        assertFalse(a.contains("secret"))
        assertFalse(a.contains("prompt"))
        assertNotEquals(a, b)
        assertEquals("ab password=secret prompt", cipher.open("job", a))
    }
    @Test fun payloadCannotBeMovedToAnotherOwnerJobOrModified() {
        val sealed = cipher.seal("job", "xx")
        assertTrue(runCatching { cipher.open("other", sealed) }.isFailure)
        val modified = sealed.dropLast(4) + "AAAA"
        assertTrue(runCatching { cipher.open("job", modified) }.isFailure)
    }
    @Test fun missingKeystoreKeyAfterRestoreDoesNotCreateAnotherKey() {
        val sealed = cipher.seal("job", "xx")
        val unavailable = AesGcmCronPayloadCipher { create ->
            assertFalse(create)
            error("missing key")
        }
        assertTrue(runCatching { unavailable.open("job", sealed) }.isFailure)
    }
}
