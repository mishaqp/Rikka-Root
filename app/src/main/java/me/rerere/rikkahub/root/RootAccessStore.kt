package me.rerere.rikkahub.root

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RootCommandLogEntry(
    val id: String,
    val assistantId: String,
    val timestampMs: Long,
    val command: String,
    val exitCode: Int? = null,
    val status: String = "running",
    @SerialName("process_id") val processId: String? = null,
)

/**
 * Redacted audit history and web-content markers. Supply a directory under noBackupFilesDir.
 * The legacy permissions are a migration source for ToolApprovalPreferences; generation uses
 * its fresh DataStore grants and the trusted RootInvocation callback.
 */
class RootAccessStore(private val directory: File) {
    private val file = File(directory, "root-access.json")
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val initial = readState()
    private val permissionState = MutableStateFlow(initial.permissions)
    private val history = MutableStateFlow(initial.entries)
    private val webConversations = MutableStateFlow(initial.webContentConversations)
    val permissions: StateFlow<ToolPermissions> = permissionState.asStateFlow()
    val entries: StateFlow<List<RootCommandLogEntry>> = history.asStateFlow()
    val webContentConversations: StateFlow<Set<String>> = webConversations.asStateFlow()

    fun isWebTainted(conversationId: String?): Boolean = conversationId != null && conversationId in webConversations.value

    fun isAllowed(toolName: String, conversationId: String? = null): Boolean {
        val snapshot = permissions.value
        return !isWebTainted(conversationId) && (snapshot.autoApproveAll || toolName in snapshot.alwaysAllow)
    }

    suspend fun markWebContent(conversationId: String) = mutex.withLock {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        if (conversationId in webConversations.value) return@withLock
        // Fail closed immediately. A failed durable mark stops generation before any later tool.
        val next = webConversations.value + conversationId
        webConversations.value = next
        withContext(NonCancellable + Dispatchers.IO) {
            writeState(StoredRootAccess(permissionState.value, history.value, next))
        }
    }

    suspend fun setAutoApprove(enabled: Boolean) = changePermissions(revoking = !enabled) { it.copy(autoApproveAll = enabled) }

    suspend fun grantAlways(toolName: String) {
        require(toolName.isNotBlank() && toolName.length <= 256 && toolName.none { it.isISOControl() })
        changePermissions(revoking = false) { it.copy(alwaysAllow = it.alwaysAllow + toolName) }
    }

    suspend fun revoke(toolName: String) = changePermissions(revoking = true) { it.copy(alwaysAllow = it.alwaysAllow - toolName) }

    /** The chat shortcut restores approval for every side-effect tool, including persistent grants. */
    suspend fun disableAllAutomaticApprovals() = changePermissions(revoking = true) { ToolPermissions() }

    private suspend fun changePermissions(revoking: Boolean, change: (ToolPermissions) -> ToolPermissions) = mutex.withLock {
        val next = change(permissionState.value)
        // Revocation takes effect before disk IO; a failed save must never restore permission.
        if (revoking) permissionState.value = next
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                writeState(StoredRootAccess(next, history.value, webConversations.value))
                permissionState.value = next
            } catch (error: IOException) {
                if (revoking) {
                    // No AtomicFile backup is kept that could resurrect the old enabled state.
                    runCatching { Files.deleteIfExists(file.toPath()) }
                    permissionState.value = ToolPermissions()
                }
                throw error
            }
        }
    }

    suspend fun beginCommand(assistantId: String, command: String): String = mutex.withLock {
        val entry = RootCommandLogEntry(
            id = UUID.randomUUID().toString(),
            assistantId = assistantId,
            timestampMs = System.currentTimeMillis(),
            command = RootCommandRedactor.redact(command),
            processId = PROCESS_TOKEN,
        )
        val next = (history.value + entry).takeLast(MAX_ENTRIES)
        withContext(Dispatchers.IO) { writeState(StoredRootAccess(permissionState.value, next, webConversations.value)) }
        history.value = next
        entry.id
    }

    suspend fun finishCommand(id: String, exitCode: Int?, status: String) = mutex.withLock {
        if (history.value.none { it.id == id }) return@withLock
        val safeStatus = status.takeIf { it in STATUSES } ?: "failed"
        val next = history.value.map { if (it.id == id) it.copy(exitCode = exitCode, status = safeStatus) else it }
        // The actual outcome is known even when the final disk write fails.
        history.value = next
        withContext(Dispatchers.IO) { writeState(StoredRootAccess(permissionState.value, next, webConversations.value)) }
    }

    private fun readState(): StoredRootAccess = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return@runCatching StoredRootAccess()
        val state = json.decodeFromString<StoredRootAccess>(file.readText())
        state.copy(entries = state.entries.takeLast(MAX_ENTRIES).map {
            val status = if (it.status == "running" && it.processId != PROCESS_TOKEN) "process_interrupted"
                else it.status.takeIf { status -> status in STATUSES } ?: "failed"
            it.copy(command = RootCommandRedactor.redact(it.command), status = status)
        }).also { recovered ->
            // Reading another instance in this process must not interrupt its real command.
            if (recovered != state) runCatching { writeState(recovered) }
        }
    }.getOrDefault(StoredRootAccess())

    private fun writeState(state: StoredRootAccess) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Root access storage is unavailable.")
        privatePermissions(directory, executable = true)
        val temporary = File.createTempFile("root-access-", ".tmp", directory)
        try {
            privatePermissions(temporary, executable = false)
            FileOutputStream(temporary).use {
                it.write(json.encodeToString(state).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Never expose source commands, parser input or arbitrary exception messages to logs.
            throw IOException("Root access state could not be saved.")
        } finally {
            temporary.delete()
        }
    }

    private fun privatePermissions(target: File, executable: Boolean) {
        val secured = target.setReadable(false, false) &&
            target.setWritable(false, false) &&
            target.setExecutable(false, false) &&
            target.setReadable(true, true) &&
            target.setWritable(true, true) &&
            (!executable || target.setExecutable(true, true))
        if (!secured) throw IOException("Root access storage permissions could not be secured.")
    }

    private companion object {
        val PROCESS_TOKEN = UUID.randomUUID().toString()
        const val MAX_ENTRIES = 200
        const val MAX_FILE_BYTES = 1_048_576L
        val STATUSES = setOf("process_interrupted", "running", "completed", "cancelled", "command_timeout", "root_approval_required",
            "su_not_available", "root_not_granted", "root_verification_timeout", "root_cleanup_unavailable", "root_interaction_required",
            "root_cleanup_unconfirmed", "root_execution_indeterminate", "exec_failed", "failed")
    }
}

@Serializable
data class ToolPermissions(
    val autoApproveAll: Boolean = false,
    val alwaysAllow: Set<String> = emptySet(),
)

@Serializable
private data class StoredRootAccess(
    // Old enabledAssistants is intentionally ignored: assistant consent is not global consent.
    val permissions: ToolPermissions = ToolPermissions(),
    val entries: List<RootCommandLogEntry> = emptyList(),
    val webContentConversations: Set<String> = emptySet(),
)

/** Stable journal codes remain machine-readable; only the visible label is translated. */
fun rootCommandStatusMessage(status: String): String = when (status) {
    "running" -> "Выполняется"
    "completed" -> "Завершено"
    "cancelled" -> "Остановлено пользователем"
    "process_interrupted" -> "Прервано: приложение было закрыто. Проверьте результат перед ручным запуском."
    "command_timeout" -> "Истекло время ожидания. Проверьте результат команды."
    "root_approval_required", "root_not_granted" -> "Доступ root не выдан. Разрешите его в менеджере root."
    "su_not_available" -> "Root недоступен на этом устройстве."
    "root_verification_timeout" -> "Не удалось дождаться ответа менеджера root. Повторите проверку доступа."
    "root_interaction_required" -> "Нет фоновой Root-сессии. Откройте настройки Root и нажмите «Открыть root-сессию»; команда не выполнена."
    "root_cleanup_unavailable" -> "Команда не выполнена: нет setsid для безопасной остановки. Проверьте системные команды root."
    "root_cleanup_unconfirmed" -> "Остановка команды не подтверждена. Проверьте процессы на устройстве перед повторным запуском."
    "root_execution_indeterminate" -> "Результат команды неизвестен. Проверьте устройство перед повторным запуском."
    else -> "Команда завершилась с ошибкой. Проверьте её вывод перед повторным запуском."
}
