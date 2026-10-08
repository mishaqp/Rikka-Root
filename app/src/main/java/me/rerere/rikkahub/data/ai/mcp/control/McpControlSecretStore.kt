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
        val id = UUID.randomUUID().toString()
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
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        return REFERENCE_PREFIX + id
    }

    fun resolve(reference: String): String {
        require(isReference(reference)) { "Некорректная ссылка на секрет MCP." }
        val file = File(directory, reference.removePrefix(REFERENCE_PREFIX) + ".enc")
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
        private const val MAX_ENCRYPTED_SIZE = 262200

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
