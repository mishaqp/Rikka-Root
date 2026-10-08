package me.rerere.rikkahub.data.ssh

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class SshCredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun `secrets round trip while encrypted file contains no plaintext`() {
        val file = folder.root.resolve("ssh_credentials.enc")
        val credentials = SshCredentials("private-password", "private-key-contents", "private-passphrase")
        val store = SshCredentialStore(file, json) { key }
        store.write("server", credentials)
        assertEquals(credentials, SshCredentialStore(file, json) { key }.read("server"))
        val bytes = file.readBytes().toString(Charsets.UTF_8)
        listOf("private-password", "private-key-contents", "private-passphrase").forEach {
            assertFalse(bytes.contains(it))
        }
        assertFalse(folder.root.resolve("ssh_credentials.enc.tmp").exists())
    }

    @Test fun `update and delete preserve other hosts`() {
        val file = folder.root.resolve("ssh_credentials.enc")
        val store = SshCredentialStore(file, json) { key }
        store.write("one", SshCredentials(password = "first"))
        store.write("two", SshCredentials(privateKey = "second"))
        store.write("one", SshCredentials(password = "replacement"))
        assertEquals("replacement", store.read("one")?.password)
        store.delete("one")
        assertNull(store.read("one"))
        assertEquals("second", store.read("two")?.privateKey)
    }

    @Test fun `wrong key and tampered ciphertext cannot expose or overwrite credentials`() {
        val file = folder.root.resolve("ssh_credentials.enc")
        val store = SshCredentialStore(file, json) { key }
        store.write("server", SshCredentials(password = "private-password"))
        val committed = file.readBytes()
        val wrong = SshCredentialStore(file, json) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        assertThrows(Exception::class.java) { wrong.read("server") }
        assertThrows(Exception::class.java) { wrong.write("another", SshCredentials(password = "new")) }
        assertArrayEquals(committed, file.readBytes())
        file.writeBytes(committed.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertThrows(Exception::class.java) { store.read("server") }
    }

    @Test fun `failed encryption keeps committed credentials`() {
        val file = folder.root.resolve("ssh_credentials.enc")
        val store = SshCredentialStore(file, json) { key }
        store.write("server", SshCredentials(password = "old"))
        val before = file.readBytes()
        val failing = SshCredentialStore(file, json) { SecretKeySpec(ByteArray(3), "AES") }
        assertThrows(Exception::class.java) { failing.write("server", SshCredentials(password = "new")) }
        assertArrayEquals(before, file.readBytes())
        assertEquals("old", store.read("server")?.password)
    }
}
