package me.rerere.rikkahub.data.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class SshHostRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val dao = MetadataDao()
    private val store by lazy { SshCredentialStore(folder.root.resolve("ssh_credentials.enc"), Json) { key } }
    private fun host() = SshHostEntity("server", "example", 22, "me", createdAtMs = 1).apply {
        password = "private-password"
        privateKey = "private-key"
        passphrase = "private-passphrase"
    }

    @Test fun `repository sends only metadata to Room and decrypts on lookup`() = runBlocking {
        val repo = SshHostRepository(dao, store)
        repo.upsert(host())
        val persisted = dao.rows.getValue("server")
        assertNull(persisted.password)
        assertNull(persisted.privateKey)
        assertNull(persisted.passphrase)
        assertTrue(persisted.hasPassword)
        assertTrue(persisted.hasPrivateKey)
        assertNull(repo.getAll().single().password)
        val opened = repo.getByName("server")!!
        assertEquals("private-password", opened.password)
        assertEquals("private-key", opened.privateKey)
        assertEquals("private-passphrase", opened.passphrase)
    }

    @Test fun `deleting saved host removes credentials`() = runBlocking {
        val repo = SshHostRepository(dao, store)
        repo.upsert(host())
        val metadata = dao.rows.getValue("server").copy()
        repo.deleteByName("server")
        assertNull(repo.getByName("server"))
        dao.rows["server"] = metadata
        try { repo.getByName("server"); fail("Deleted credentials must not remain usable") }
        catch (_: IllegalStateException) { }
    }

    @Test fun `old database metadata never receives credentials for a different endpoint`() = runBlocking {
        val repo = SshHostRepository(dao, store)
        repo.upsert(host())
        val backup = dao.rows.getValue("server").copy()
        repo.upsert(host().copy(host = "other-server", user = "other-user").apply { password = "other-password" })
        dao.rows["server"] = backup
        val restored = repo.getByName("server")!!
        assertEquals("example", restored.host)
        assertEquals("private-password", restored.password)
        assertNotEquals("other-password", restored.password)
    }

    @Test fun `concurrent alias replacement returns a matching endpoint and credential pair`() = runBlocking {
        val repo = SshHostRepository(dao, store)
        repo.upsert(host())
        val saving = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        dao.beforeUpsert = { saving.complete(Unit); proceed.await() }
        val replacement = async { repo.upsert(host().copy(host = "other-server").apply { password = "other-password" }) }
        saving.await()
        val reading = async { repo.getByName("server")!! }
        yield()
        proceed.complete(Unit)
        replacement.await()
        val opened = reading.await()
        assertTrue((opened.host to opened.password) in setOf("example" to "private-password", "other-server" to "other-password"))
    }

    @Test fun `failed Room save preserves committed credentials for same or different endpoint`() = runBlocking {
        val repo = SshHostRepository(dao, store)
        repo.upsert(host())
        dao.beforeUpsert = { error("Room save failed") }
        for (endpoint in listOf("example", "other-server")) {
            try {
                repo.upsert(host().copy(host = endpoint).apply { password = "replacement-password" })
                fail("Room failure must propagate")
            } catch (_: IllegalStateException) { }
            val committed = repo.getByName("server")!!
            assertEquals("example", committed.host)
            assertEquals("private-password", committed.password)
        }
    }

    @Test fun `a saved host name cannot replace or delete an immutable tool reference`() = runBlocking {
        val refKey = "tool:00000000-0000-0000-0000-000000000001"
        store.write(refKey, SshCredentials(password = "reference-password"))
        val repo = SshHostRepository(dao, store)
        repo.upsert(host().copy(name = refKey).apply { password = "host-password" })
        assertEquals("reference-password", store.read(refKey)?.password)
        repo.deleteByName(refKey)
        assertEquals("reference-password", store.read(refKey)?.password)
    }

    @Test fun `restored metadata without device credentials explains required reconfiguration`() = runBlocking {
        val repo = SshHostRepository(dao, store)
        repo.upsert(host())
        folder.root.resolve("ssh_credentials.enc").delete()
        try {
            repo.getByName("server")
            fail("Missing credentials must not silently become usable")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("восстановления"))
            assertFalse(failure.message.orEmpty().contains("private-password"))
        }
    }

    private class MetadataDao : SshHostDao {
        val rows = mutableMapOf<String, SshHostEntity>()
        var beforeUpsert: suspend () -> Unit = {}
        override suspend fun getAll() = rows.values.map { it.copy() }.sortedBy { it.name }
        override suspend fun getByName(name: String) = rows[name]?.copy()
        override suspend fun upsert(host: SshHostEntity) { beforeUpsert(); rows[host.name] = host }
        override suspend fun delete(host: SshHostEntity) { rows.remove(host.name) }
        override suspend fun deleteByName(name: String) { rows.remove(name) }
    }
}
