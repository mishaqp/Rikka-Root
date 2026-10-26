package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsEnvironmentState
import me.rerere.workspace.RootfsInstallException
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallResult
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsRecoveryStatus
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.BackgroundStatus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProfileKind
import me.rerere.workspace.WorkspaceProfileService
import me.rerere.workspace.WorkspaceProfileState
import me.rerere.workspace.WorkspaceProfilesSnapshot
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val profileService: WorkspaceProfileService = WorkspaceProfileService(manager),
) {
    private val toolApprovalMutex = Mutex()
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }

            val recovery = rootfsInstaller.recover(workspace.root)
            when (recovery.status) {
                RootfsRecoveryStatus.BUSY -> continue
                RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED -> {
                    Log.e(TAG, "Rootfs rollback is unconfirmed for workspace ${workspace.id}: ${recovery.error}")
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                    // Do not rewrite profile metadata while the active Rootfs identity is uncertain.
                    continue
                }
                RootfsRecoveryStatus.COMMITTED,
                RootfsRecoveryStatus.CLEANUP_FAILED ->
                    updateShellState(workspace.id, WorkspaceShellStatus.READY.name)
                RootfsRecoveryStatus.ROLLED_BACK,
                RootfsRecoveryStatus.ROLLBACK_CLEANUP_FAILED -> updateShellState(
                    workspace.id,
                    if (recovery.previousRootfsUsable && manager.hasRootfs(workspace.root)) {
                        WorkspaceShellStatus.READY.name
                    } else {
                        WorkspaceShellStatus.DISABLED.name
                    },
                )
                RootfsRecoveryStatus.NO_OPERATION -> Unit
            }
            if (recovery.error != null) {
                Log.w(TAG, "Rootfs local recovery warning for workspace ${workspace.id}: ${recovery.error}")
            }

            // Reading states also invalidates READY when Rootfs was replaced or removed.
            profileService.states(workspace.root)
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun profileStates(id: String): WorkspaceProfilesSnapshot {
        val workspace = dao.getById(id) ?: return WorkspaceProfilesSnapshot.empty()
        return withContext(Dispatchers.IO) { profileService.states(workspace.root) }
    }

    suspend fun prepareProfile(id: String, kind: WorkspaceProfileKind): WorkspaceProfileState {
        val workspace = profileWorkspace(id)
        return runInterruptible(Dispatchers.IO) {
            profileService.prepare(workspace.root, kind, workspace.shellCompatibilityMode)
        }
    }

    suspend fun verifyProfile(id: String, kind: WorkspaceProfileKind): WorkspaceProfileState {
        val workspace = profileWorkspace(id)
        return runInterruptible(Dispatchers.IO) {
            profileService.verify(workspace.root, kind, workspace.shellCompatibilityMode)
        }
    }

    suspend fun cancelProfile(id: String, kind: WorkspaceProfileKind): WorkspaceProfileState? {
        val workspace = dao.getById(id) ?: return null
        return withContext(Dispatchers.IO) { profileService.markCancelled(workspace.root, kind) }
    }

    suspend fun interruptProfile(id: String, kind: WorkspaceProfileKind): WorkspaceProfileState? {
        val workspace = dao.getById(id) ?: return null
        return withContext(Dispatchers.IO) { profileService.markInterrupted(workspace.root, kind) }
    }

    suspend fun failProfile(id: String, kind: WorkspaceProfileKind, reason: String): WorkspaceProfileState? {
        val workspace = dao.getById(id) ?: return null
        return withContext(Dispatchers.IO) { profileService.markFailed(workspace.root, kind, reason) }
    }

    private suspend fun profileWorkspace(id: String): WorkspaceEntity =
        dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")

    suspend fun create(name: String): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        dao.upsert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        dao.setShellCompatibilityMode(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean =
        updateWorkspaceToolApproval(dao, toolApprovalMutex, id, toolName, needsApproval)

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): RootfsInstallResult? {
        val workspace = dao.getById(id) ?: return null
        val previousGeneration = rootfsInstaller.generation(workspace.root)
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            val result = runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return result
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                settleCancelledInstall(workspace, previousGeneration)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable + Dispatchers.IO) {
                settleCancelledInstall(workspace, previousGeneration)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: RootfsInstallException) {
            when (e.environmentState) {
                RootfsEnvironmentState.PRESERVED,
                RootfsEnvironmentState.RESTORED -> restoreShellState(workspace)
                RootfsEnvironmentState.REPLACED ->
                    updateShellState(workspace, WorkspaceShellStatus.READY.name)
                RootfsEnvironmentState.ROLLBACK_UNCONFIRMED ->
                    updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            }
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    private suspend fun settleCancelledInstall(workspace: WorkspaceEntity, previousGeneration: String?) {
        val recovery = rootfsInstaller.recover(workspace.root)
        when (recovery.status) {
            RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED ->
                updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
            RootfsRecoveryStatus.COMMITTED,
            RootfsRecoveryStatus.CLEANUP_FAILED ->
                updateShellState(workspace.id, WorkspaceShellStatus.READY.name)
            else -> {
                val currentGeneration = rootfsInstaller.generation(workspace.root)
                if (currentGeneration != previousGeneration && manager.hasRootfs(workspace.root)) {
                    // Cancellation can arrive after the durable commit even if cleanup already finished.
                    updateShellState(workspace.id, WorkspaceShellStatus.READY.name)
                } else {
                    restoreShellState(workspace)
                }
            }
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun resolveFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.resolveFile(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** Resolve through the same mount table used by workspace tools and PRoot. */
    suspend fun resolveRootfsFile(id: String, path: String): java.io.File = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.resolveRootfsFile(workspace.root, path)
    }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /** Rootfs 内执行命令实际使用的 shell 路径 (/bin/bash 或 /bin/sh) */
    suspend fun rootfsShell(id: String): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsShell(workspace.root)
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFile(workspace.root, path, outputStream)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                workspace.root, command, cwd, timeoutMillis, stdin,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
    }

    suspend fun startBackground(id: String, command: String, cwd: String = ""): BackgroundStatus {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.startBackgroundCommand(workspace.root, command, cwd, workspace.shellCompatibilityMode)
        }
    }

    suspend fun backgroundProcesses(id: String): List<BackgroundStatus> {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        return withContext(Dispatchers.IO) { manager.backgroundProcesses(workspace.root) }
    }

    suspend fun backgroundOutput(id: String, processId: String): BackgroundStatus {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        return withContext(Dispatchers.IO) { manager.backgroundOutput(workspace.root, processId) ?: error("Процесс не найден в этой рабочей папке. Проверьте process_id через workspace_background_list.") }
    }

    suspend fun stopBackground(id: String, processId: String): Boolean {
        val workspace = dao.getById(id) ?: error("Рабочая папка не найдена. Выберите её заново.")
        return runInterruptible(Dispatchers.IO) { manager.stopBackgroundProcess(workspace.root, processId) }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        dao.deleteById(id)
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}

/** Serialize the complete read/merge/write so concurrent Always grants retain each other. */
internal suspend fun updateWorkspaceToolApproval(
    dao: WorkspaceDAO,
    mutex: Mutex,
    id: String,
    toolName: String,
    needsApproval: Boolean,
): Boolean = mutex.withLock {
    val workspace = dao.getById(id) ?: return@withLock false
    val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
    dao.upsert(workspace.copy(
        toolApprovals = JsonInstant.encodeToString(overrides),
        updatedAt = System.currentTimeMillis(),
    ))
    true
}
