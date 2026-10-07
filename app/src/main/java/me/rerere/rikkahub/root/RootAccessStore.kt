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
)

/**
 * Device-local permission and redacted audit history. Supply a directory under noBackupFilesDir;
 * permissions must never be included in assistant exports or imported settings.
 */
class RootAccessStore(private val directory: File) {
    private val file = File(directory, "root-access.json")
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val initial = readState()
    private val permissionState = MutableStateFlow(initial.permissions)
    private val history = MutableStateFlow(initial.entries)
    val permissions: StateFlow<ToolPermissions> = permissionState.asStateFlow()
    val entries: StateFlow<List<RootCommandLogEntry>> = history.asStateFlow()

    fun isAllowed(toolName: String): Boolean = permissions.value.autoApproveAll || toolName in permissions.value.alwaysAllow

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
                writeState(StoredRootAccess(next, history.value))
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
        )
        val next = (history.value + entry).takeLast(MAX_ENTRIES)
        withContext(Dispatchers.IO) { writeState(StoredRootAccess(permissionState.value, next)) }
        history.value = next
        entry.id
    }

    suspend fun finishCommand(id: String, exitCode: Int?, status: String) = mutex.withLock {
        if (history.value.none { it.id == id }) return@withLock
        val safeStatus = status.takeIf { it in STATUSES } ?: "failed"
        val next = history.value.map { if (it.id == id) it.copy(exitCode = exitCode, status = safeStatus) else it }
        // The actual outcome is known even when the final disk write fails.
        history.value = next
        withContext(Dispatchers.IO) { writeState(StoredRootAccess(permissionState.value, next)) }
    }

    private fun readState(): StoredRootAccess = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return@runCatching StoredRootAccess()
        val state = json.decodeFromString<StoredRootAccess>(file.readText())
        state.copy(entries = state.entries.takeLast(MAX_ENTRIES).map {
            it.copy(command = RootCommandRedactor.redact(it.command), status = it.status.takeIf { status -> status in STATUSES } ?: "failed")
        })
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
        const val MAX_ENTRIES = 200
        const val MAX_FILE_BYTES = 1_048_576L
        val STATUSES = setOf("running", "completed", "cancelled", "command_timeout", "root_approval_required",
            "su_not_available", "root_not_granted", "root_verification_timeout", "root_cleanup_unavailable", "exec_failed", "failed")
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
)
