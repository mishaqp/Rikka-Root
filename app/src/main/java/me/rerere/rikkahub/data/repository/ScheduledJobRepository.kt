package me.rerere.rikkahub.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.CronRunPolicy
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.utils.JsonInstant
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class CronAction(val tool: String, val args: JsonObject)
@Serializable
data class ScheduledJobPayload(
    val name: String,
    val prompt: String? = null,
    val actions: List<CronAction> = emptyList(),
    val conversationConfig: ConversationConfig? = null,
    val workspaceCwd: String? = null,
    val allowedToolNames: Set<String> = emptySet(),
)

interface CronPayloadCipher {
    fun seal(jobId: String, plaintext: String): String
    fun open(jobId: String, encrypted: String): String
}

/** Authenticated ciphertext can be backed up; the non-exportable key cannot. */
class AesGcmCronPayloadCipher(private val key: (create: Boolean) -> SecretKey) : CronPayloadCipher {
    override fun seal(jobId: String, plaintext: String): String {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536) { "Задание слишком велико." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(jobId.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(bytes))
    }
    override fun open(jobId: String, encrypted: String): String {
        require(encrypted.startsWith("v1:") && encrypted.length <= 100000) { "Задание недоступно." }
        val bytes = Base64.getDecoder().decode(encrypted.removePrefix("v1:"))
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(jobId.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }
}

class AndroidCronPayloadCipher : CronPayloadCipher {
    private val cipher = AesGcmCronPayloadCipher { create -> synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) entry.secretKey else {
            check(create) { "Ключ задания отсутствует после восстановления." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    } }
    override fun seal(jobId: String, plaintext: String) = cipher.seal(jobId, plaintext)
    override fun open(jobId: String, encrypted: String) = cipher.open(jobId, encrypted)
    companion object {
        private const val KEY_ALIAS = "rikka.internal.cron.payload.v1"
        private val KEY_LOCK = Any()
    }
}

class ScheduledJobRepository(private val db: AppDatabase, private val cipher: CronPayloadCipher) {
    private val dao get() = db.scheduledJobDao()
    suspend fun getById(id: String): ScheduledJobEntity? = dao.getById(id)
    suspend fun getOwned(id: String, owner: String): ScheduledJobEntity? = dao.getById(id)?.takeIf { it.ownerAssistantId == owner && !it.deleted }
    suspend fun listOwned(owner: String): List<ScheduledJobEntity> = dao.listOwned(owner)
    suspend fun active(): List<ScheduledJobEntity> = dao.active()
    private fun binding(job: ScheduledJobEntity) = "${job.id}/${job.ownerAssistantId}/${job.creatorConversationId}"
    fun payload(job: ScheduledJobEntity): ScheduledJobPayload = JsonInstant.decodeFromString(cipher.open(binding(job), job.payloadCiphertext))
    suspend fun create(job: ScheduledJobEntity, payload: ScheduledJobPayload): ScheduledJobEntity {
        val encrypted = job.copy(payloadCiphertext = cipher.seal(binding(job), JsonInstant.encodeToString(payload)))
        db.withTransaction {
            require(dao.listOwned(job.ownerAssistantId).size < 100) { "Достигнут предел 100 заданий." }
            dao.insert(encrypted)
        }
        return encrypted
    }
    suspend fun setEnabled(id: String, owner: String, enabled: Boolean): ScheduledJobEntity? = db.withTransaction {
        if (dao.setEnabled(id, owner, enabled) == 0) null else dao.getById(id)
    }
    suspend fun invalidateSchedule(id: String, expectedRevision: Long): ScheduledJobEntity? = db.withTransaction {
        val job = dao.getById(id)?.takeIf { it.enabled && !it.deleted && it.revision == expectedRevision } ?: return@withTransaction null
        val invalidated = CronRunPolicy.invalidateSchedule(job)
        if (dao.invalidateSchedule(id, expectedRevision, invalidated.revision) == 1) invalidated else null
    }
    suspend fun deleteOwned(id: String, owner: String): Boolean = dao.deleteOwned(id, owner) == 1
    suspend fun setNext(id: String, revision: Long, next: Long?): Boolean = dao.setNext(id, revision, next) == 1
}
