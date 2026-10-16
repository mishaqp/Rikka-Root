package me.rerere.rikkahub.service

import androidx.annotation.WorkerThread
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.uuid.Uuid

@Serializable
private data class StoredQueuedMessage(
    @SerialName("id") val id: Uuid,
    @SerialName("parts") val parts: List<UIMessagePart>,
    @SerialName("answer") val answer: Boolean,
)

@Serializable
private data class StoredMessageQueue(
    @SerialName("conversationId") val conversationId: Uuid,
    @SerialName("messages") val messages: List<StoredQueuedMessage>,
)

/** Only authenticated ciphertext reaches the app-private, no-backup directory. */
class MessageQueueStore(private val directoryProvider: () -> File, private val cipher: CronPayloadCipher) {
    constructor(directory: File, cipher: CronPayloadCipher) : this({ directory }, cipher)

    private val directory by lazy(directoryProvider)
    private val queues = mutableMapOf<Uuid, List<StoredQueuedMessage>>()
    private var loaded = false
    @Volatile private var attachmentUrls: Set<String> = emptySet()
    val cachedAttachmentUrls: Set<String> get() = attachmentUrls
    @Volatile var hasUnreadableSnapshots: Boolean = false
        private set

    /** Load every pending queue before allowing attachment cleanup in unopened conversations. */
    @WorkerThread
    @Synchronized
    fun readAll() {
        if (loaded) return
        try {
            val found = mutableMapOf<Uuid, List<StoredQueuedMessage>>()
            if (directory.exists()) {
                if (!directory.isDirectory) throw IOException()
                val files = directory.listFiles() ?: throw IOException()
                for (file in files.filter { it.name.endsWith(".queue") }) {
                    val id = Uuid.parse(file.name.removeSuffix(".queue"))
                    found[id] = readFile(file, id)
                }
            }
            queues.putAll(found)
            refreshAttachmentUrls()
            hasUnreadableSnapshots = false
            loaded = true
        } catch (_: Exception) {
            hasUnreadableSnapshots = true
            // Do not overwrite an unreadable queue or expose JSON, a pathname or a key error.
            throw IOException("Не удалось восстановить очередь сообщений. Данные сохранены; повторите запуск приложения.")
        }
    }

    @WorkerThread
    @Synchronized
    internal fun read(id: Uuid): List<QueuedMessage> {
        readAll()
        return queues[id].orEmpty().map { QueuedMessage(id = it.id, parts = it.parts, answer = it.answer) }
    }

    @WorkerThread
    @Synchronized
    internal fun write(id: Uuid, messages: List<QueuedMessage>) {
        readAll()
        try {
            val stored = messages.map { StoredQueuedMessage(it.id, it.parts, it.answer) }
            val file = File(directory, "$id.queue")
            if (stored.isEmpty()) {
                Files.deleteIfExists(file.toPath())
                queues.remove(id)
            } else {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                val encoded = Base64.getEncoder().encodeToString(
                    JsonInstant.encodeToString(StoredMessageQueue(id, stored)).toByteArray(Charsets.UTF_8))
                val chunks = encoded.chunked(CHUNK_CHARS)
                if (chunks.size > MAX_CHUNKS) throw IOException()
                val nonce = Uuid.random().toString()
                val ciphertext = chunks.mapIndexed { index, chunk ->
                    cipher.seal(binding(id, nonce, chunks.size, index), chunk)
                }.joinToString("\n", prefix = "v1 $nonce ${chunks.size}\n")
                val bytes = ciphertext.toByteArray(Charsets.US_ASCII)
                if (bytes.size > MAX_FILE_BYTES) throw IOException()
                val staging = File.createTempFile("pending-", ".tmp", directory)
                try {
                    FileOutputStream(staging).use { output -> output.write(bytes); output.fd.sync() }
                    Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { staging.delete() }
                queues[id] = stored
            }
            refreshAttachmentUrls()
        } catch (_: Exception) {
            throw IOException("Не удалось сохранить сообщение. Проверьте свободное место и повторите отправку.")
        }
    }

    private fun readFile(file: File, id: Uuid): List<StoredQueuedMessage> {
        require(file.isFile && file.length() in 1..MAX_FILE_BYTES.toLong())
        val lines = file.readLines(Charsets.US_ASCII)
        val header = lines.first().split(' ')
        require(header.size == 3 && header.first() == "v1")
        val nonce = Uuid.parse(header[1]).toString()
        val count = header[2].toInt()
        require(count in 1..MAX_CHUNKS && lines.size == count + 1)
        val encoded = lines.drop(1).mapIndexed { index, ciphertext ->
            cipher.open(binding(id, nonce, count, index), ciphertext)
        }.joinToString("")
        val queue = JsonInstant.decodeFromString<StoredMessageQueue>(
            Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
        require(queue.conversationId == id && queue.messages.map { it.id }.distinct().size == queue.messages.size)
        return queue.messages
    }

    private fun refreshAttachmentUrls() {
        attachmentUrls = queues.values.flatten().flatMap { it.parts }.localFileUrls()
    }

    private fun binding(id: Uuid, nonce: String, count: Int, index: Int) = "message-queue/$id/$nonce/$count/$index"

    companion object {
        private const val CHUNK_CHARS = 32_768
        private const val MAX_CHUNKS = 2_048
        private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    }
}
