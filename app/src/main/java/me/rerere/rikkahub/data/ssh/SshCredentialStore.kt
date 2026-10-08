package me.rerere.rikkahub.data.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class SshCredentials(val password: String? = null, val privateKey: String? = null, val passphrase: String? = null)

@Serializable
private data class SshCredentialState(val hosts: Map<String, SshCredentials> = emptyMap())

/** Existing CodexCredentialStore AES-GCM pattern; no SSH secret enters Room or backup files. */
class SshCredentialStore internal constructor(
    private val file: File,
    private val json: Json,
    private val keyProvider: () -> SecretKey,
) {
    constructor(context: Context, json: Json) : this(
        File(context.noBackupFilesDir, FILE_NAME), json, ::getOrCreateKey,
    )

    fun read(name: String): SshCredentials? = synchronized(LOCK) { readState().hosts[name] }
    fun write(name: String, credentials: SshCredentials) = synchronized(LOCK) {
        writeState(SshCredentialState(readState().hosts + (name to credentials)))
    }
    fun delete(name: String) = synchronized(LOCK) {
        if (file.exists()) writeState(SshCredentialState(readState().hosts - name))
    }

    private fun readState(): SshCredentialState {
        if (!file.exists()) return SshCredentialState()
        val bytes = file.readBytes()
        require(bytes.size >= IV_SIZE + TAG_LENGTH / 8) { "Хранилище учётных данных SSH повреждено." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_LENGTH, bytes.copyOfRange(0, IV_SIZE)))
        cipher.updateAAD(KEY_ALIAS.toByteArray(Charsets.UTF_8))
        return json.decodeFromString(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString())
    }

    private fun writeState(state: SshCredentialState) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(KEY_ALIAS.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(cipher.iv + encrypted)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        fun getOrCreateKey(): SecretKey = synchronized(LOCK) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setKeySize(256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        val LOCK = Any()
        const val FILE_NAME = "ssh_credentials.enc"
        const val KEY_ALIAS = "rikka.internal.ssh.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}
