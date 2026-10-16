// Adapted from Rikka-Root CodexCredentialStore: AndroidKeyStore encryption and noBackup storage.
package me.rerere.rikkahub.data.ai.mcp.control

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.net.URI
import kotlin.uuid.Uuid
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only opaque references enter Settings/Room/backups; header bytes are encrypted in noBackup. */
class McpControlSecretStore internal constructor(
    private val directory: File,
    private val keyProvider: () -> SecretKey,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "mcp_control_credentials"), ::getOrCreateKey)

    @Synchronized
    fun put(value: String): String {
        require(value.isNotEmpty()) { "Значение секретного заголовка пустое." }
        require(value.length <= 65536) { "Секретный заголовок слишком большой." }
        require(!value.contains('\r') && !value.contains('\n')) { "Заголовок не должен содержать переносы строк." }
        return writeStored(UUID.randomUUID().toString(), value)
    }

    /** Trusted settings/migration paths may claim a legacy unbound reference once. */
    fun bind(reference: String, serverId: Uuid, url: String): String = synchronized(ownershipLock) {
        // Restored references can be unavailable on this device. Preserve them for the
        // settings UI; scoped resolution still fails closed before any request is sent.
        if (!referenceFile(reference).isFile) return@synchronized reference
        val stored = readStored(reference)
        val owner = ownerOf(serverId, url)
        val bound = unpack(stored)
        if (bound == null) {
            writeStored(reference.removePrefix(REFERENCE_PREFIX), BOUND_PREFIX + owner + "\n" + stored)
        } else {
            require(bound.first == owner) { "Секрет MCP принадлежит другому серверу или адресу. Введите его заново в настройках MCP." }
        }
        reference
    }

    fun put(value: String, serverId: Uuid, url: String): String = bind(put(value), serverId, url)

    /** Model-supplied references must already belong to this exact server and origin. */
    fun resolve(reference: String, serverId: Uuid, url: String): String = synchronized(ownershipLock) {
        val bound = unpack(readStored(reference))
        require(bound != null && bound.first == ownerOf(serverId, url)) {
            "Секрет MCP принадлежит другому серверу или адресу. Введите его заново в настройках MCP."
        }
        bound.second
    }

    /** Internal trusted access, retained for legacy migration and vault maintenance. */
    fun resolve(reference: String): String = readStored(reference).let { unpack(it)?.second ?: it }

    /** Only trusted migration may fork an ownerless value; bound foreign values never qualify. */
    internal fun legacyValue(reference: String): String? = synchronized(ownershipLock) {
        if (!referenceFile(reference).isFile) return@synchronized null
        readStored(reference).takeIf { unpack(it) == null }
    }

    private fun referenceFile(reference: String): File {
        require(isReference(reference)) { "Некорректная ссылка на секрет MCP." }
        return File(directory, reference.removePrefix(REFERENCE_PREFIX) + ".enc")
    }

    private fun unpack(stored: String): Pair<String, String>? {
        if (!stored.startsWith(BOUND_PREFIX)) return null
        val parts = stored.removePrefix(BOUND_PREFIX).split('\n', limit = 3)
        check(parts.size == 3) { "Повреждена привязка секрета MCP." }
        return (parts[0] + "\n" + parts[1]) to parts[2]
    }

    private fun ownerOf(serverId: Uuid, url: String): String = "$serverId\n${mcpCredentialOrigin(url)}"

    private fun writeStored(id: String, value: String): String {
        val destination = File(directory, "$id.enc")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать защищённое хранилище MCP." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
        val plaintext = value.toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val temporary = File(directory, "$id.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(cipher.iv + encrypted)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        return REFERENCE_PREFIX + id
    }

    private fun readStored(reference: String): String {
        val file = referenceFile(reference)
        check(file.isFile) { "Секрет MCP отсутствует на этом устройстве. Введите его в настройках MCP." }
        val encrypted = file.readBytes()
        check(encrypted.size in (IV_SIZE + 1)..MAX_ENCRYPTED_SIZE) { "Повреждено защищённое хранилище MCP." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, encrypted.copyOfRange(0, IV_SIZE)))
        }
        val plaintext = cipher.doFinal(encrypted.copyOfRange(IV_SIZE, encrypted.size))
        return try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
    }

    companion object {
        const val REFERENCE_PREFIX = "rikka-keystore:mcp:"
        private const val KEY_ALIAS = "rikka_root_mcp_control_headers"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val MAX_ENCRYPTED_SIZE = 263000
        private const val BOUND_PREFIX = "\u0000rikka-mcp-owner-v1\n"
        private val ownershipLock = Any()

        fun isReference(value: String): Boolean = value.startsWith(REFERENCE_PREFIX) &&
            value.removePrefix(REFERENCE_PREFIX).matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

        @Synchronized
        private fun getOrCreateKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        }
    }
}

/** Origin includes scheme and effective port so a downgrade cannot reuse credentials. */
internal fun mcpCredentialOrigin(url: String): String {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Некорректный адрес сервера MCP." }
    val host = requireNotNull(uri.host) { "Некорректный адрес сервера MCP." }.lowercase()
    val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
    return "$scheme://$host:$port"
}
