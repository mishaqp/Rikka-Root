// Adapted from ExTV/rikkahub-agent, local/KeystoreTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class ToolKeyType(val wire: String) { RSA("rsa_2048"), AES("aes_256_gcm") }
internal enum class ToolKeyPurpose(val wire: String) { SIGN("sign"), VERIFY("verify"), ENCRYPT("encrypt"), DECRYPT("decrypt") }
internal data class ToolKeySpec(val type: ToolKeyType, val purposes: Set<ToolKeyPurpose>)
internal data class ToolKeyMetadata(val type: ToolKeyType, val purposes: Set<ToolKeyPurpose>, val hardwareBacked: Boolean)
internal data class ToolKeyDescription(val alias: String, val metadata: ToolKeyMetadata)
// Native key handles only; production never calls getEncoded on private/secret keys.
internal class ToolKeyMaterial(val metadata: ToolKeyMetadata, val privateKey: PrivateKey?, val publicKey: PublicKey?, val secretKey: SecretKey?)
internal class ToolCiphertext(val ciphertext: ByteArray, val iv: ByteArray)
internal class ToolKeyFailure(message: String) : Exception(message)
internal interface ToolKeyStore {
    fun contains(alias: String): Boolean
    fun generate(alias: String, spec: ToolKeySpec): ToolKeyMetadata
    fun entry(alias: String): ToolKeyMaterial?
    fun delete(alias: String)
    fun aliases(): List<String>
}

internal fun validateToolKeyAlias(alias: String?): String {
    require(alias != null && Regex("[A-Za-z0-9_-]{1,64}").matches(alias)) { "alias должен содержать 1–64 латинских буквы, цифры, дефис или подчёркивание." }
    return alias
}
internal fun validateToolKeySpec(type: String?, purposes: List<String>): ToolKeySpec {
    val kind = ToolKeyType.entries.firstOrNull { it.wire == type }
        ?: throw IllegalArgumentException("type должен быть rsa_2048 или aes_256_gcm.")
    require(purposes.isNotEmpty() && purposes.size <= 2 && purposes.distinct().size == purposes.size) { "purposes должен содержать 1–2 разных назначения." }
    val allowed = if (kind == ToolKeyType.RSA) setOf("sign", "verify") else setOf("encrypt", "decrypt")
    require(purposes.all { it in allowed }) { "Назначения не соответствуют типу ключа." }
    return ToolKeySpec(kind, purposes.map { name -> ToolKeyPurpose.entries.single { it.wire == name } }.toSet())
}
internal fun decodeToolBase64(value: String?, maxBytes: Int, name: String): ByteArray {
    require(maxBytes in 1..65552)
    require(value != null && value.length <= ((maxBytes + 2) / 3) * 4 && Regex("[A-Za-z0-9+/]*={0,2}").matches(value)) { "$name должен быть Base64 размером не более $maxBytes байт." }
    val bytes = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$name содержит некорректный Base64.")
    }
    if (bytes.size > maxBytes) { bytes.fill(0); throw IllegalArgumentException("$name превышает $maxBytes байт.") }
    return bytes
}
internal fun validateToolIv(iv: ByteArray) { require(iv.size == 12) { "iv_b64 должен содержать ровно 12 байт." } }
/** Only for the protected user screen, never for a tool's chat result. */
internal fun formatProtectedSecret(bytes: ByteArray): String = try {
    val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    if (text.any { Character.isISOControl(it) && it !in "\n\r\t" }) Base64.getEncoder().encodeToString(bytes) else text
} catch (_: java.nio.charset.CharacterCodingException) { Base64.getEncoder().encodeToString(bytes) }

/** One app-wide engine serializes generate/delete with operations; a retry cannot replace a key. */
internal class KeystoreCrypto(private val store: ToolKeyStore) {
    private val namespace = "rikka_tool_v1_"
    private fun physical(alias: String) = namespace + validateToolKeyAlias(alias)
    private fun ownedAliases() = store.aliases().filter { it.startsWith(namespace) && it.removePrefix(namespace).matches(Regex("[A-Za-z0-9_-]{1,64}")) }

    @Synchronized fun generate(alias: String, type: String, purposes: List<String>): ToolKeyDescription {
        val spec = validateToolKeySpec(type, purposes)
        val name = physical(alias)
        if (store.contains(name)) throw ToolKeyFailure("Ключ '$alias' уже существует. Перезапись уничтожила бы доступ к старым данным. Выберите другой alias или отдельно одобрите удаление старого ключа.")
        if (ownedAliases().size >= 128) throw ToolKeyFailure("Достигнут предел 128 ключей. Удалите ненужный ключ после отдельного одобрения.")
        return ToolKeyDescription(alias, store.generate(name, spec))
    }
    private fun key(alias: String, type: ToolKeyType, purpose: ToolKeyPurpose): ToolKeyMaterial {
        val key = store.entry(physical(alias)) ?: throw ToolKeyFailure("Ключ '$alias' не найден среди ключей этих инструментов.")
        if (key.metadata.type != type || purpose !in key.metadata.purposes) throw ToolKeyFailure("Ключ '$alias' не разрешает операцию ${purpose.wire} для этого типа ключа.")
        return key
    }
    @Synchronized fun check(alias: String, type: ToolKeyType, purpose: ToolKeyPurpose) { key(alias, type, purpose) }
    @Synchronized fun sign(alias: String, data: ByteArray): ByteArray {
        require(data.size <= 65536) { "Данные подписи превышают 64 КиБ." }
        val key = key(alias, ToolKeyType.RSA, ToolKeyPurpose.SIGN)
        return Signature.getInstance("SHA256withRSA").apply { initSign(key.privateKey ?: throw ToolKeyFailure("Закрытый RSA-ключ недоступен.")); update(data) }.sign()
    }
    @Synchronized fun verify(alias: String, data: ByteArray, signature: ByteArray): Boolean {
        require(data.size <= 65536) { "Данные подписи превышают 64 КиБ." }
        val key = key(alias, ToolKeyType.RSA, ToolKeyPurpose.VERIFY)
        if (signature.size != 256) return false
        return Signature.getInstance("SHA256withRSA").apply { initVerify(key.publicKey ?: throw ToolKeyFailure("Открытый RSA-ключ недоступен.")); update(data) }.verify(signature)
    }
    @Synchronized fun encrypt(alias: String, plaintext: ByteArray): ToolCiphertext {
        require(plaintext.size <= 65536) { "Данные шифрования превышают 64 КиБ." }
        val key = key(alias, ToolKeyType.AES, ToolKeyPurpose.ENCRYPT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key.secretKey ?: throw ToolKeyFailure("AES-ключ недоступен.")) }
        validateToolIv(cipher.iv)
        return ToolCiphertext(cipher.doFinal(plaintext), cipher.iv)
    }
    @Synchronized fun decrypt(alias: String, ciphertext: ByteArray, iv: ByteArray): ByteArray {
        validateToolIv(iv); require(ciphertext.size in 16..65552) { "Шифротекст должен содержать от 16 до 65552 байт, включая тег GCM." }
        val key = key(alias, ToolKeyType.AES, ToolKeyPurpose.DECRYPT)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key.secretKey ?: throw ToolKeyFailure("AES-ключ недоступен."), GCMParameterSpec(128, iv)) }.doFinal(ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            throw ToolKeyFailure("Целостность шифротекста не подтверждена. Проверьте ключ, IV и данные; результат не показан.")
        }
    }
    @Synchronized fun delete(alias: String) {
        val name = physical(alias)
        if (!store.contains(name)) throw ToolKeyFailure("Ключ '$alias' не найден среди ключей этих инструментов.")
        store.delete(name)
    }
    @Synchronized fun list(): List<ToolKeyDescription> = ownedAliases().sorted().mapNotNull { name ->
        store.entry(name)?.let { ToolKeyDescription(name.removePrefix(namespace), it.metadata) }
    }
}
