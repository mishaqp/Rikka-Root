package me.rerere.rikkahub.data.kimi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
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

/** Call only from the repository's IO dispatcher. Never included in app backups. */
internal class KimiCredentialStore private constructor(
    resolveFile: () -> File,
    private val json: Json,
    private val keyProvider: () -> SecretKey,
) {
    private val file by lazy(resolveFile)

    constructor(file: File, json: Json, keyProvider: () -> SecretKey) : this({ file }, json, keyProvider)
    constructor(context: Context, json: Json) : this(
        { File(context.noBackupFilesDir, "kimi_session.enc") }, json, ::key,
    )

    fun read(): KimiAccount? {
        if (!file.exists()) return null
        return runCatching {
            require(file.length() in 13..MAX_BYTES)
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val state = json.decodeFromString<KimiSavedSession>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString())
            require(state.version == 1)
            state.account
        }.getOrNull()
    }

    fun write(account: KimiAccount?) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(KimiSavedSession(account = account)).encodeToByteArray())
        require(encrypted.size <= MAX_BYTES)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(encrypted); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("rikkaroot_kimi_oauth", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("rikkaroot_kimi_oauth", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}

@Serializable
private data class KimiSavedSession(val version: Int = 1, val account: KimiAccount? = null)
