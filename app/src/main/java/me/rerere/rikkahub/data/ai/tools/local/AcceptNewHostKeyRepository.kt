package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Required adaptation of Agent's accept-new policy: mwiede JSch 0.2.21 does not implement
 * the "accept-new" config value. Used with strict=yes, this repository accepts only a
 * previously-unseen endpoint, durably records its key, and rejects identity changes.
 */
internal class AcceptNewHostKeyRepository(private val file: File) : HostKeyRepository {
    private fun load(): HostKeyRepository {
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Не удалось открыть known_hosts." } }
        if (!file.exists()) check(file.createNewFile()) { "Не удалось создать known_hosts." }
        check(file.isFile) { "Хранилище ключей SSH недоступно." }
        // InputStream avoids KnownHosts.add writing the committed file before our atomic save.
        return JSch().apply { file.inputStream().use { setKnownHosts(it) } }.hostKeyRepository
    }

    override fun check(host: String, key: ByteArray): Int = synchronized(LOCK) {
        val repository = load()
        // KnownHosts.check falls back from [host]:port to bare host. Explicitly scope trust
        // to this endpoint so separate SSH servers on the same address retain separate keys.
        val keys = repository.hostKey.orEmpty().filter { endpointMatches(it.host, host) }
        val encoded = java.util.Base64.getEncoder().encodeToString(key)
        if (keys.any { it.key == encoded }) return@synchronized HostKeyRepository.OK
        // A new algorithm for a known endpoint is still a new identity, not first use.
        if (keys.isNotEmpty()) return@synchronized HostKeyRepository.CHANGED
        repository.add(HostKey(host, key), null)
        persist(repository)
        HostKeyRepository.OK
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = synchronized(LOCK) {
        val repository = load()
        check(repository.hostKey.orEmpty().any { endpointMatches(it.host, hostkey.host) && it.key == hostkey.key }) {
            "Ключ SSH не прошёл проверку accept-new."
        }
    }

    override fun remove(host: String, type: String?) = remove(host, type, null)
    override fun remove(host: String, type: String?, key: ByteArray?) = synchronized(LOCK) {
        val repository = load()
        repository.remove(host, type, key)
        persist(repository)
    }
    override fun getKnownHostsRepositoryID(): String = file.absolutePath
    override fun getHostKey(): Array<HostKey> = synchronized(LOCK) { load().hostKey ?: emptyArray() }
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = synchronized(LOCK) {
        load().hostKey.orEmpty().filter {
            (host == null || endpointMatches(it.host, host)) && (type == null || it.type == type)
        }.toTypedArray()
    }

    private fun persist(repository: HostKeyRepository) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val text = buildString {
                    repository.hostKey?.forEach { key ->
                        key.marker?.takeIf { it.isNotEmpty() }?.let {
                            append(if (it.startsWith('@')) it else "@$it").append(' ')
                        }
                        append(key.host).append(' ').append(key.type).append(' ').append(key.key).append('\n')
                    }
                }
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private companion object {
        val LOCK = Any()
        fun endpointMatches(stored: String, endpoint: String): Boolean =
            stored.split(',').any { it.equals(endpoint, ignoreCase = true) }
    }
}
