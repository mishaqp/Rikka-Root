// Adapted from ExTV/rikkahub-agent, local/KeystoreTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory

/** No database, SharedPreferences, backup entry, exported key bytes or logging. */
internal class AndroidToolKeyStore(private val context: Context) : ToolKeyStore {
    private val provider = "AndroidKeyStore"
    private fun load() = KeyStore.getInstance(provider).apply { load(null) }
    override fun contains(alias: String) = load().containsAlias(alias)
    override fun aliases(): List<String> = load().aliases().toList()
    override fun delete(alias: String) { load().deleteEntry(alias) }
    override fun entry(alias: String): ToolKeyMaterial? = when (val entry = load().getEntry(alias, null)) {
        is KeyStore.PrivateKeyEntry -> {
            val info = KeyFactory.getInstance(entry.privateKey.algorithm, provider).getKeySpec(entry.privateKey, KeyInfo::class.java)
            if (entry.privateKey.algorithm != KeyProperties.KEY_ALGORITHM_RSA || info.keySize != 2048) null
            else ToolKeyMaterial(metadata(ToolKeyType.RSA, info), entry.privateKey, entry.certificate.publicKey, null)
        }
        is KeyStore.SecretKeyEntry -> {
            val info = SecretKeyFactory.getInstance(entry.secretKey.algorithm, provider).getKeySpec(entry.secretKey, KeyInfo::class.java) as KeyInfo
            if (entry.secretKey.algorithm != KeyProperties.KEY_ALGORITHM_AES || info.keySize != 256) null
            else ToolKeyMaterial(metadata(ToolKeyType.AES, info), null, null, entry.secretKey)
        }
        else -> null
    }
    private fun metadata(type: ToolKeyType, info: KeyInfo): ToolKeyMetadata {
        val purposes = ToolKeyPurpose.entries.filter { info.purposes and bits(it) != 0 }.toSet()
        val hardware = if (Build.VERSION.SDK_INT >= 31) info.securityLevel in listOf(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_STRONGBOX)
            else { @Suppress("DEPRECATION") info.isInsideSecureHardware }
        return ToolKeyMetadata(type, purposes, hardware)
    }
    private fun bits(purpose: ToolKeyPurpose) = when (purpose) {
        ToolKeyPurpose.SIGN -> KeyProperties.PURPOSE_SIGN
        ToolKeyPurpose.VERIFY -> KeyProperties.PURPOSE_VERIFY
        ToolKeyPurpose.ENCRYPT -> KeyProperties.PURPOSE_ENCRYPT
        ToolKeyPurpose.DECRYPT -> KeyProperties.PURPOSE_DECRYPT
    }
    override fun generate(alias: String, spec: ToolKeySpec): ToolKeyMetadata {
        val builder = KeyGenParameterSpec.Builder(alias, spec.purposes.fold(0) { value, purpose -> value or bits(purpose) })
        when (spec.type) {
            ToolKeyType.RSA -> builder.setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            ToolKeyType.AES -> builder.setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true)
        }
        fun create() {
            when (spec.type) {
                ToolKeyType.RSA -> KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, provider).apply { initialize(builder.build()); generateKeyPair() }
                ToolKeyType.AES -> KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider).apply { init(builder.build()); generateKey() }
            }
        }
        if (Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            try { builder.setIsStrongBoxBacked(true); create() }
            catch (_: StrongBoxUnavailableException) { builder.setIsStrongBoxBacked(false); create() }
        } else create()
        return entry(alias)?.metadata ?: throw ToolKeyFailure("Android не подтвердил создание ключа.")
    }
}
