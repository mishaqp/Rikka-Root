package me.rerere.rikkahub.data.ssh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class SshToolSecretSanitizerTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun sanitizer() = SshToolSecretSanitizer(SshCredentialStore(folder.root.resolve("ssh_credentials.enc"), Json) { key })

    @Test fun `tool history stores opaque refs that resolve only using encrypted vault`() {
        val sanitizer = sanitizer()
        val args = """{"host":"server","user":"me","command":"whoami","password":"private-password","private_key":"private-key","passphrase":"private-passphrase"}"""
        val persisted = sanitizer.sanitizeForPersistence("ssh_exec", args)
        listOf("private-password", "private-key", "private-passphrase").forEach { assertFalse(persisted.contains(it)) }
        assertTrue(persisted.contains("rikka-ssh-secret:"))
        assertEquals(Json.parseToJsonElement(args), sanitizer().resolveArgs("ssh_exec", Json.parseToJsonElement(persisted)))
        assertEquals(persisted, sanitizer.sanitizeForPersistence("ssh_exec", args))
        assertEquals(persisted, sanitizer.sanitizeForPersistence("ssh_exec", persisted))
    }

    @Test fun `partial or invalid secret arguments cannot persist plaintext`() {
        val sanitizer = sanitizer()
        listOf("""{"password":"private-password""", """{"password":{"value":"private-password"}}""").forEach {
            val persisted = sanitizer.sanitizeForPersistence("save_ssh_host", it)
            assertFalse(persisted.contains("private-password"))
            assertTrue(persisted.contains("_credentials_removed"))
        }
    }

    @Test fun `unavailable vault credentials return a clear message without exposing payload`() {
        val sanitizer = sanitizer()
        val persisted = sanitizer.sanitizeForPersistence("save_ssh_host", """{"password":"private-password"}""")
        folder.root.resolve("ssh_credentials.enc").delete()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            sanitizer.resolveArgs("save_ssh_host", Json.parseToJsonElement(persisted))
        }
        assertTrue(failure.message.orEmpty().contains("недоступны"))
        assertFalse(failure.message.orEmpty().contains("private-password"))
    }

    @Test fun `other tool arguments remain untouched`() {
        val args = """{"password":"ordinary-data"}"""
        assertEquals(args, sanitizer().sanitizeForPersistence("unrelated_tool", args))
    }

    @Test fun `invalid reference errors cannot echo raw credential contents`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            sanitizer().resolveArgs("ssh_exec", Json.parseToJsonElement("""{"password":"rikka-ssh-secret:private-password:password"}"""))
        }
        assertFalse(failure.message.orEmpty().contains("private-password"))
    }

    @Test fun `mixed opaque and new credentials still resolve to original values`() {
        val sanitizer = sanitizer()
        val first = Json.parseToJsonElement(sanitizer.sanitizeForPersistence("ssh_exec", """{"password":"private-password"}""")) as JsonObject
        val mixed = JsonObject(first.toMutableMap().apply { put("passphrase", JsonPrimitive("new-passphrase")) })
        val persisted = sanitizer.sanitizeForPersistence("ssh_exec", mixed.toString())
        assertEquals(Json.parseToJsonElement("""{"password":"private-password","passphrase":"new-passphrase"}"""),
            sanitizer.resolveArgs("ssh_exec", Json.parseToJsonElement(persisted)))
    }
}
