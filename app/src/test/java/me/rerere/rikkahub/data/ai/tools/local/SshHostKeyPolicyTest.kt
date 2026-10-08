package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContextWrapper
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshHostKeyPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    private fun key(): ByteArray = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024).let {
        try { it.publicKeyBlob } finally { it.dispose() }
    }

    @Test fun `first key is persisted and changed key is rejected across instances`() {
        val file = folder.root.resolve("known_hosts")
        val original = key()
        val changed = key()
        val first = AcceptNewHostKeyRepository(file)
        assertEquals(HostKeyRepository.OK, first.check("server", original))
        assertTrue(file.readText().contains("server ssh-rsa"))
        val reopened = AcceptNewHostKeyRepository(file)
        assertEquals(HostKeyRepository.OK, reopened.check("server", original))
        assertEquals(HostKeyRepository.CHANGED, reopened.check("server", changed))
        assertEquals(HostKeyRepository.OK, AcceptNewHostKeyRepository(file).check("server", original))
    }

    @Test fun `different SSH ports retain separate host identities`() {
        val file = folder.root.resolve("known_hosts")
        val repository = AcceptNewHostKeyRepository(file)
        val a = key()
        val b = key()
        assertEquals(HostKeyRepository.OK, repository.check("server", a))
        assertEquals(HostKeyRepository.OK, repository.check("[server]:2222", b))
        assertEquals(HostKeyRepository.CHANGED, repository.check("[server]:2222", a))
    }

    @Test fun `forget persists removal and permits a replacement initial key`() {
        val file = folder.root.resolve("known_hosts")
        val repository = AcceptNewHostKeyRepository(file)
        repository.check("server", key())
        repository.remove("server", null)
        assertFalse(file.readText().contains("server"))
        assertEquals(HostKeyRepository.OK, AcceptNewHostKeyRepository(file).check("server", key()))
    }

    @Test fun `unwritable host key store fails closed`() {
        val directory = folder.newFolder("directory")
        assertThrows(Exception::class.java) { AcceptNewHostKeyRepository(directory).check("server", key()) }
    }

    @Test fun `forget hostname removes keys on all its ports but not unrelated host`() {
        val context = object : ContextWrapper(null) { override fun getFilesDir() = folder.root }
        val repository = AcceptNewHostKeyRepository(knownHostsFile(context))
        repository.check("server", key())
        repository.check("[server]:2222", key())
        val other = key()
        repository.check("other", other)
        assertEquals(2, forgetHostKey(context, "server"))
        assertEquals(1, repository.hostKey.size)
        assertEquals(HostKeyRepository.OK, repository.check("other", other))
    }

    @Test fun `forget explicit endpoint retains other port on same host`() {
        val context = object : ContextWrapper(null) { override fun getFilesDir() = folder.root }
        val repository = AcceptNewHostKeyRepository(knownHostsFile(context))
        val original = key()
        repository.check("server", original)
        repository.check("[server]:2222", key())
        assertEquals(1, forgetHostKey(context, "[server]:2222"))
        assertEquals(1, repository.hostKey.size)
        assertEquals(HostKeyRepository.OK, repository.check("server", original))
    }
}
