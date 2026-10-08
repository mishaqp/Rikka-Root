package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.data.ssh.SshCredentials
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Agent repository adapted for credentials outside Room/backups (AGPL v3). */
class SshHostRepository(private val dao: SshHostDao, private val credentials: SshCredentialStore,
    val toolSecrets: SshToolSecretSanitizer = SshToolSecretSanitizer(credentials)) {
    private val lock = Mutex()
    suspend fun getAll(): List<SshHostEntity> = lock.withLock { dao.getAll() }
    suspend fun getByName(name: String): SshHostEntity? = lock.withLock {
        val host = dao.getByName(name) ?: return@withLock null
        val secret = credentials.read(credentialBinding(host))
        check(secret != null || !host.hasPassword && !host.hasPrivateKey) {
            "Учётные данные SSH недоступны после восстановления. Сохраните хост заново."
        }
        host.apply {
            password = secret?.password
            privateKey = secret?.privateKey
            passphrase = secret?.passphrase
        }
    }
    suspend fun upsert(host: SshHostEntity) = lock.withLock {
        val binding = credentialBinding(host)
        val previous = credentials.read(binding)
        credentials.write(binding, SshCredentials(host.password, host.privateKey, host.passphrase))
        try {
            dao.upsert(host.copy(hasPassword = !host.password.isNullOrBlank(), hasPrivateKey = !host.privateKey.isNullOrBlank()))
        } catch (failure: Exception) {
            try {
                if (previous == null) credentials.delete(binding) else credentials.write(binding, previous)
            } catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
            throw failure
        }
    }
    suspend fun deleteByName(name: String) = lock.withLock {
        val metadata = dao.getByName(name)
        dao.deleteByName(name)
        metadata?.let { credentials.delete(credentialBinding(it)) }
    }

    /** Never send another endpoint's password after a concurrent save, crash or DB restore. */
    private fun credentialBinding(host: SshHostEntity): String {
        val tuple = Json.encodeToString(listOf(host.name, host.host, host.port.toString(), host.user))
        val digest = MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray(Charsets.UTF_8))
        return "host:" + digest.joinToString("") { "%02x".format(it) }
    }
}
