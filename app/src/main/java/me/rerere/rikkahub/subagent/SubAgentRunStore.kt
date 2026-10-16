package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.repository.CronPayloadCipher
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

@Serializable
internal data class StoredSubAgentRun(val processId: String, val run: SubAgentRun)

/** App-private, no-backup snapshots. Result text is authenticated ciphertext under AndroidKeyStore. */
internal class SubAgentRunStore(private val directory: File, private val cipher: CronPayloadCipher) {
    fun readAll(): List<StoredSubAgentRun> = try {
        val files = directory.listFiles()
            ?: if (Files.notExists(directory.toPath())) emptyArray() else throw IOException("Папка истории субагентов недоступна.")
        files.filter { it.isFile && it.name.endsWith(".run") && it.length() <= 2_000_000 }
            .take(200)
            .map { file ->
                val lines = file.readLines()
                require(lines.firstOrNull() == "v1")
                val encoded = lines.drop(1).mapIndexed { index, line ->
                    cipher.open("subagent/${file.name}/$index", line)
                }.joinToString("")
                JsonInstant.decodeFromString<StoredSubAgentRun>(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
            }
    } catch (_: Exception) {
        // Deserialization/KeyStore details can include decrypted task contents; never attach their cause.
        throw IOException("Не удалось прочитать историю субагентов. Повторите запрос или перезапустите приложение.")
    }

    fun write(run: SubAgentRun, processId: String) {
        try {
            writeSnapshot(run, processId)
        } catch (_: Exception) {
            throw IOException("Не удалось сохранить результат субагента. Проверьте свободное место и скопируйте полученный результат из чата.")
        }
    }

    private fun writeSnapshot(run: SubAgentRun, processId: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось сохранить статус субагента. Проверьте свободное место." }
        val file = file(run.id)
        // ASCII chunks respect the existing cipher's byte limit without truncating Unicode results.
        val encoded = Base64.getEncoder().encodeToString(JsonInstant.encodeToString(StoredSubAgentRun(processId, run))
            .toByteArray(Charsets.UTF_8))
        val ciphertext = encoded.chunked(32_768).mapIndexed { index, part ->
            cipher.seal("subagent/${file.name}/$index", part)
        }.joinToString("\n", prefix = "v1\n")
        val temporary = File.createTempFile("subagent-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(ciphertext.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun delete(id: String) { Files.deleteIfExists(file(id).toPath()) }

    private fun file(id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$name.run")
    }
}
