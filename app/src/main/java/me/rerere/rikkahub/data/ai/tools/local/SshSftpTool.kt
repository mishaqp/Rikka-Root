// Ported from ExTV/rikkahub-agent, adapted for Rikka-Root permissions/security (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.SshHostRepository
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

private const val TAG_SFTP = "SshSftpTool"

private fun openSftp(session: Session): ChannelSftp {
    val channel = session.openChannel("sftp") as ChannelSftp
    channel.connect()
    return channel
}

/**
 * Open a connected SSH session to a saved host with the same probe → bind → cancel logic
 * SSH exec uses. Stashes the live Session in [sessionRef] so the outer cancellable wrapper
 * can disconnect it on coroutine cancellation. Returns the [block]'s envelope, or a
 * connect-error envelope if the handshake failed before [block] could run.
 */
private suspend fun withSavedHostSession(
    context: Context,
    repo: SshHostRepository,
    name: String,
    timeoutMs: Int,
    sessionRef: AtomicReference<Session?>,
    block: (Session) -> JsonObject,
): JsonObject {
    val h = repo.getByName(name)
        ?: return buildJsonObject { put("error", "Сохранённый хост не найден: $name. Проверьте name через list_ssh_hosts или сохраните хост в настройках SSH.") }
    val auth = SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
    if (!auth.isUsable()) {
        return buildJsonObject { put("error", "У сохранённого хоста нет доступного пароля или приватного ключа. Сохраните хост заново в настройках SSH.") }
    }
    // Same probe-then-bind path the exec tool uses. Without this, SFTP traffic is left to
    // Android's default-network selection, which on adaptive-routing devices routes the
    // app's outgoing connections away from WiFi LAN even though WiFi is up — meaning every
    // recent fix that gave SSH exec the WiFi-binding behaviour was bypassed for SFTP.
    val outcome = probeReachability(context, h.host, h.port)
    if (outcome.winningLabel == null && outcome.failures.isNotEmpty()) {
        return unreachableEnvelope(h.host, h.port, outcome)
    }
    val operationContext = currentCoroutineContext()
    return runInterruptible(Dispatchers.IO) {
        val jsch = newJSch(context)
        val session = try {
            openSshSession(jsch, h.host, h.port, h.user, auth, timeoutMs, network = outcome.winningNetwork)
        } catch (e: Throwable) {
            Log.w(TAG_SFTP, "ssh handshake failed (${e::class.java.simpleName})")
            return@runInterruptible wrapConnectError(h.host, e)
        }
        sessionRef.set(session)
        try {
            operationContext.ensureActive()
            block(session)
        } finally {
            sessionRef.set(null)
            try { session.disconnect() } catch (_: Throwable) {}
        }
    }
}

/** Upload a local file to a saved-host's remote path via SFTP. */
fun sshUploadTool(context: Context, repo: SshHostRepository, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "ssh_upload",
    description = """
        Upload a local file from the device to a remote path on a saved SSH host using SFTP.
        Both paths are absolute file paths. The remote directory must already exist.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Absolute path on the device") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote path (full filename)") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
            },
            required = listOf("name", "local_path", "remote_path")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: name")
        val localPath = p["local_path"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: local_path")
        val remotePath = p["remote_path"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: remote_path")
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        val localFile = requireNotNull((access.resolve(localPath) as? LocalFileSource.Local)?.file) {
            "SFTP работает с локальными файлами scratch, upload или workspace."
        }
        if (!localFile.exists() || !localFile.isFile) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "Локальный файл не найден: $localPath. Проверьте local_path и выберите существующий файл.") }.toString()
            ))
        }
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            val uploadContext = currentCoroutineContext()
            withSavedHostSession(context, repo, name, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                try {
                    uploadContext.ensureActive()
                    // The handshake can outlive a grant or a pathname. Revalidate
                    // at stream acquisition, within the interruptible session.
                    access.checkFile(localFile).inputStream().use { input -> sftp.put(input, remotePath) }
                    buildJsonObject {
                        put("success", true)
                        put("remote_path", remotePath)
                        put("bytes", access.checkFile(localFile).length())
                    }
                } catch (e: Throwable) {
                    sshFailureEnvelope(e, "Ошибка загрузки SFTP.",
                        "Проверьте local_path, существование удалённой папки remote_path и право записи на сервере, затем повторите ssh_upload.")
                } finally {
                    try { sftp.disconnect() } catch (_: Throwable) {}
                }
            }
        }
        listOf(UIMessagePart.Text(withSftpPaths(payload, name, localPath, remotePath).toString()))
    }
)

/** Download a remote file from a saved host to a local path via SFTP. */
fun sshDownloadTool(context: Context, repo: SshHostRepository, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "ssh_download",
    description = """
        Download a remote file from a saved SSH host to a local path on the device using SFTP.
        Both paths are absolute file paths. The local directory must already exist (or be the
        app's cache/files dir).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("remote_path", buildJsonObject { put("type", "string"); put("description", "Absolute remote file path") })
                put("local_path", buildJsonObject { put("type", "string"); put("description", "Absolute local path on the device") })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Total timeout, default 60, max 600") })
            },
            required = listOf("name", "remote_path", "local_path")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: name")
        val remotePath = p["remote_path"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: remote_path")
        val localPath = p["local_path"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: local_path")
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 600)
        val localFile = requireNotNull((access.resolve(localPath, write = true) as? LocalFileSource.Local)?.file) {
            "SFTP работает с локальными файлами scratch, upload или workspace."
        }
        // This reference belongs to one invocation. A local timeout discards the block's
        // result, but waits for its finally; publish only safe diagnostics after cleanup.
        val completedCleanupDetails = AtomicReference<JsonObject?>(null)
        val payload = runCancellableSshOp(timeoutSec * 1000L) { sessionRef ->
            val downloadContext = currentCoroutineContext()
            withSavedHostSession(context, repo, name, timeoutSec * 1000, sessionRef) { session ->
                val sftp = openSftp(session)
                var staging: TransferStaging? = null
                var published = false
                var cleanupFailed = false
                val result = try {
                    downloadContext.ensureActive()
                    val parent = access.checkFile(requireNotNull(localFile.absoluteFile.parentFile))
                    access.checkFile(localFile, write = true)
                    require(parent.mkdirs() || parent.isDirectory) { "Не удалось создать папку назначения SFTP." }
                    access.checkFile(parent)
                    access.checkFile(localFile, write = true)
                    val transfer = TransferStaging.create(context, targetParent = parent)
                    staging = transfer
                    transfer.openOutput().use { output -> sftp.get(remotePath, output) }
                    downloadContext.ensureActive()
                    // The final path changes only after a complete transfer. Never fall back to
                    // truncating/copying the destination when the filesystem cannot move atomically.
                    access.checkFile(parent)
                    val partial = transfer.checkedFile()
                    access.checkFile(localFile, write = true)
                    downloadContext.ensureActive()
                    Files.move(partial.toPath(), localFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    published = true
                    buildJsonObject {
                        put("success", true)
                        put("local_path", localFile.absolutePath)
                        put("bytes", access.checkFile(localFile).length())
                    }
                } catch (e: Throwable) {
                    sshFailureEnvelope(e, "Ошибка скачивания SFTP.",
                        "Проверьте remote_path, право чтения на сервере и доступ к local_path, затем повторите ssh_download.")
                } finally {
                    // Cache ownership is independent of the target grant. Never delete a
                    // replaced cache inode or follow a changed cache ancestor.
                    if (!published && staging?.cleanup() == false) cleanupFailed = true
                    try { sftp.disconnect() } catch (_: Throwable) {}
                    if (cleanupFailed) completedCleanupDetails.set(buildJsonObject {
                        put("cleanup_failed", true)
                        put("partial_path", staging!!.file.absolutePath)
                        put("partial_path_unverified", true)
                        put("cleanup_recovery", "Временный файл не удалён: путь или доступ изменились. Указан прежний путь; его текущее содержимое не подтверждено. Проверьте исходную папку перед повтором.")
                    })
                }
                result
            }
        }
        // The wrapper returns only after blocking I/O and cleanup finish. Parent cancellation
        // still throws before this point; it is never turned into a normal timeout result.
        val reported = JsonObject(payload + completedCleanupDetails.get().orEmpty())
        listOf(UIMessagePart.Text(withSftpPaths(reported, name, localPath, remotePath).toString()))
    }
)

private fun withSftpPaths(payload: JsonObject, name: String, localPath: String, remotePath: String): JsonObject =
    if (payload["error"] == null) payload else buildJsonObject {
        payload.forEach { (key, value) -> put(key, value) }
        put("name", name)
        put("local_path", localPath)
        put("remote_path", remotePath)
    }
