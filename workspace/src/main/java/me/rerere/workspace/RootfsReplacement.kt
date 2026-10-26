package me.rerere.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

data class RootfsInstallResult(
    val generation: String,
    val cleanupWarning: String? = null,
)

enum class RootfsEnvironmentState {
    PRESERVED,
    RESTORED,
    REPLACED,
    ROLLBACK_UNCONFIRMED,
}

class RootfsInstallException(
    message: String,
    val environmentState: RootfsEnvironmentState,
    cause: Throwable? = null,
    val cleanupWarning: String? = null,
) : IOException(message, cause)

class RootfsBusyException(message: String) : IllegalStateException(message)

enum class RootfsRecoveryStatus {
    NO_OPERATION,
    ROLLED_BACK,
    COMMITTED,
    CLEANUP_FAILED,
    ROLLBACK_CLEANUP_FAILED,
    ROLLBACK_UNCONFIRMED,
    BUSY,
}

data class RootfsRecoveryResult(
    val status: RootfsRecoveryStatus,
    val previousRootfsUsable: Boolean = false,
    val generation: String? = null,
    val error: String? = null,
)

internal enum class RootfsActivityKind(val diagnostic: String) {
    COMMAND("выполняется команда"),
    PROFILE("операция профиля Python/Node"),
    TERMINAL("открыт терминал"),
}

internal enum class RootfsFaultPoint {
    AFTER_DOWNLOAD,
    AFTER_EXTRACT,
    BEFORE_PATCH,
    AFTER_PATCH,
    BEFORE_CANDIDATE_PROBE,
    AFTER_CANDIDATE_PROBE,
    AFTER_CANDIDATE_READY_JOURNAL,
    BEFORE_SWITCH_JOURNAL,
    AFTER_SWITCH_JOURNAL,
    BEFORE_OLD_ROOTFS_MOVE,
    AFTER_OLD_ROOTFS_MOVE,
    AFTER_OLD_ROOTFS_MOVED_JOURNAL,
    BEFORE_CANDIDATE_MOVE,
    AFTER_CANDIDATE_MOVE,
    AFTER_CANDIDATE_MOVED_JOURNAL,
    BEFORE_GENERATION_WRITE,
    AFTER_GENERATION_WRITE,
    AFTER_GENERATION_JOURNAL,
    BEFORE_PROFILE_INVALIDATION,
    AFTER_PROFILE_INVALIDATION,
    AFTER_PROFILES_JOURNAL,
    BEFORE_INSTALLED_JOURNAL,
    AFTER_INSTALLED_JOURNAL,
    BEFORE_BACKUP_CLEANUP,
    AFTER_BACKUP_CLEANUP,
}

internal open class RootfsTransactionIO {
    open fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    open fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(target.parentFile)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    open fun deleteTree(target: File) {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    open fun deleteFile(target: File) {
        Files.deleteIfExists(target.toPath())
    }

    private fun syncDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

internal class RootfsReplacement(
    private val manager: WorkspaceManager,
    private val io: RootfsTransactionIO = RootfsTransactionIO(),
    private val fault: (RootfsFaultPoint) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun install(
        root: String,
        archiveExtension: String,
        prepareCandidate: (staging: File, archive: File) -> Unit,
    ): RootfsInstallResult {
        manager.ensureWorkspace(root)
        try {
            return manager.withRootfsReplacement(root) {
                withFileLock(root) { paths ->
                    val recovered = recoverLocked(paths)
                    when (recovered.status) {
                        RootfsRecoveryStatus.NO_OPERATION,
                        RootfsRecoveryStatus.ROLLED_BACK,
                        RootfsRecoveryStatus.COMMITTED -> Unit

                        RootfsRecoveryStatus.CLEANUP_FAILED -> throw RootfsInstallException(
                            recovered.error ?: "Прежняя установка завершена, но её очистка не подтверждена.",
                            RootfsEnvironmentState.REPLACED,
                        )

                        RootfsRecoveryStatus.ROLLBACK_CLEANUP_FAILED -> throw RootfsInstallException(
                            recovered.error ?: "Прежняя среда восстановлена, но очистка после отката не подтверждена.",
                            RootfsEnvironmentState.RESTORED,
                        )

                        RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED -> throw RootfsInstallException(
                            recovered.error ?: "Данные прежней операции сохранены; автоматическое восстановление не подтверждено.",
                            RootfsEnvironmentState.ROLLBACK_UNCONFIRMED,
                        )

                        RootfsRecoveryStatus.BUSY -> throw RootfsBusyException("Rootfs уже заменяется другой операцией.")
                    }
                    Transaction(paths, archiveExtension).execute(prepareCandidate)
                }
            }
        } catch (error: RootfsInstallException) {
            throw error
        } catch (error: RootfsBusyException) {
            throw RootfsInstallException(
                "Замена Rootfs отклонена: ${error.message} Прежняя среда не изменялась.",
                RootfsEnvironmentState.PRESERVED,
                error,
            )
        } catch (error: Exception) {
            throw RootfsInstallException(
                "Установка Rootfs не началась или остановлена до переключения: ${error.message ?: error.javaClass.simpleName}. " +
                    "Прежняя среда не изменялась.",
                RootfsEnvironmentState.PRESERVED,
                error,
            )
        }
    }

    fun recover(root: String): RootfsRecoveryResult {
        val paths = OperationPaths(manager, root)
        if (!Files.exists(paths.directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.NO_OPERATION,
                previousRootfsUsable = manager.hasRootfs(root),
                generation = readGeneration(paths),
            )
        }
        return try {
            manager.withRootfsReplacement(root) {
                withFileLock(root) { recoverLocked(paths) }
            }
        } catch (_: RootfsBusyException) {
            RootfsRecoveryResult(RootfsRecoveryStatus.BUSY)
        } catch (error: Exception) {
            RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                error = "Восстановление Rootfs не подтверждено: ${error.message ?: error.javaClass.simpleName}. Данные не удалялись.",
            )
        }
    }

    private fun recoverLocked(paths: OperationPaths): RootfsRecoveryResult {
        val journal = try {
            readJournal(paths)
        } catch (error: Exception) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                error = "Журнал Rootfs не читается: ${error.message ?: error.javaClass.simpleName}. Данные операции сохранены.",
            )
        } ?: return recoverWithoutJournal(paths)
        if (journal.root != paths.root || journal.version != JOURNAL_VERSION) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                error = "Журнал Rootfs имеет неподдерживаемый формат. Каталоги сохранены без изменений.",
            )
        }
        return when (journal.phase) {
            RootfsOperationPhase.INSTALLED,
            RootfsOperationPhase.CLEANUP_PENDING -> recoverCommitted(paths, journal)

            RootfsOperationPhase.ROLLED_BACK -> recoverRolledBack(paths, journal)

            RootfsOperationPhase.PREPARING,
            RootfsOperationPhase.CANDIDATE_READY,
            RootfsOperationPhase.SWITCHING,
            RootfsOperationPhase.OLD_ROOTFS_MOVED,
            RootfsOperationPhase.CANDIDATE_MOVED,
            RootfsOperationPhase.GENERATION_WRITTEN,
            RootfsOperationPhase.PROFILES_INVALIDATED -> rollbackAfterInterruption(paths, journal)
        }
    }

    private fun recoverWithoutJournal(paths: OperationPaths): RootfsRecoveryResult {
        val leftovers = paths.directory.listFiles()
            ?.filter { it.name != LOCK_FILE && entryExists(it) }
            .orEmpty()
        if (leftovers.isNotEmpty()) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                error = "Обнаружены данные замены Rootfs без читаемого журнала. Они сохранены; автоматическая очистка запрещена.",
            )
        }
        return RootfsRecoveryResult(
            status = RootfsRecoveryStatus.NO_OPERATION,
            previousRootfsUsable = manager.hasRootfs(paths.root),
            generation = readGeneration(paths),
        )
    }

    private fun rollbackAfterInterruption(
        paths: OperationPaths,
        journal: RootfsOperationJournal,
    ): RootfsRecoveryResult {
        val transaction = Transaction(paths, journal.archiveExtension, journal)
        val rollback = try {
            transaction.rollback(journal)
        } catch (error: Exception) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                previousRootfsUsable = journal.previousRootfsUsable,
                generation = journal.previousGeneration,
                error = "Откат Rootfs не подтверждён: ${error.message ?: error.javaClass.simpleName}. " +
                    "Backup, staging и текущий Rootfs сохранены.",
            )
        }
        if (rollback.cleanupWarning != null) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_CLEANUP_FAILED,
                previousRootfsUsable = journal.previousRootfsUsable,
                generation = journal.previousGeneration,
                error = "Прежняя среда восстановлена, но очистка после отката не завершена: ${rollback.cleanupWarning}",
            )
        }
        return RootfsRecoveryResult(
            status = RootfsRecoveryStatus.ROLLED_BACK,
            previousRootfsUsable = journal.previousRootfsUsable,
            generation = journal.previousGeneration,
        )
    }

    private fun recoverRolledBack(
        paths: OperationPaths,
        journal: RootfsOperationJournal,
    ): RootfsRecoveryResult {
        val transaction = Transaction(paths, journal.archiveExtension, journal)
        val verification = try {
            transaction.verifyRestored(journal)
            null
        } catch (error: Exception) {
            error
        }
        if (verification != null) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                previousRootfsUsable = journal.previousRootfsUsable,
                generation = journal.previousGeneration,
                error = "Восстановленная среда не прошла проверку: ${verification.message}. Данные сохранены.",
            )
        }
        val cleanup = try {
            transaction.cleanupAfterRollback(journal)
            null
        } catch (error: Exception) {
            error
        }
        if (cleanup != null) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_CLEANUP_FAILED,
                previousRootfsUsable = journal.previousRootfsUsable,
                generation = journal.previousGeneration,
                error = "Прежняя среда проверена, но очистка после отката не завершена: ${cleanup.message}. Данные сохранены.",
            )
        }
        return RootfsRecoveryResult(
            status = RootfsRecoveryStatus.ROLLED_BACK,
            previousRootfsUsable = journal.previousRootfsUsable,
            generation = journal.previousGeneration,
        )
    }

    private fun recoverCommitted(
        paths: OperationPaths,
        journal: RootfsOperationJournal,
    ): RootfsRecoveryResult {
        val verification = try {
            check(manager.hasRootfs(paths.root)) { "Подтверждённый новый Rootfs отсутствует или повреждён" }
            check(readGeneration(paths) == journal.newGeneration) {
                "Generation marker не соответствует подтверждённой установке"
            }
            if (journal.newRootfsIdentity != null) {
                check(directoryIdentity(paths.linux) == journal.newRootfsIdentity) {
                    "Активный каталог не совпадает с подтверждённым новым Rootfs"
                }
            }
            null
        } catch (error: Exception) {
            error
        }
        if (verification != null) {
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
                previousRootfsUsable = journal.previousRootfsUsable,
                generation = journal.newGeneration,
                error = "Подтверждённая установка не прошла проверку восстановления: ${verification.message}. Backup сохранён.",
            )
        }
        val cleanup = try {
            // Profile invalidation precedes INSTALLED. Repeating it here could erase a newer valid probe.
            cleanupOperationDirectories(paths)
            io.deleteFile(paths.journal)
            null
        } catch (error: Exception) {
            error
        }
        if (cleanup != null) {
            try {
                writeJournal(paths, journal.copy(phase = RootfsOperationPhase.CLEANUP_PENDING))
            } catch (_: Exception) {
                // The original journal still names the same commit point and preserves the backup.
            }
            return RootfsRecoveryResult(
                status = RootfsRecoveryStatus.CLEANUP_FAILED,
                previousRootfsUsable = journal.previousRootfsUsable,
                generation = journal.newGeneration,
                error = "Новая среда установлена и проверена, но очистка backup не завершена: ${cleanup.message}. " +
                    "Новый Rootfs не откатывался.",
            )
        }
        return RootfsRecoveryResult(
            status = RootfsRecoveryStatus.COMMITTED,
            previousRootfsUsable = journal.previousRootfsUsable,
            generation = journal.newGeneration,
        )
    }

    private inner class Transaction(
        private val paths: OperationPaths,
        private val archiveExtension: String,
        private var journal: RootfsOperationJournal? = null,
    ) {
        fun execute(prepareCandidate: (staging: File, archive: File) -> Unit): RootfsInstallResult {
            val initial = RootfsOperationJournal(
                root = paths.root,
                archiveExtension = archiveExtension,
                previousRootfsExisted = entryExists(paths.linux),
                previousRootfsUsable = manager.hasRootfs(paths.root),
                previousRootfsIdentity = directoryIdentity(paths.linux),
                previousGeneration = readGeneration(paths),
                previousProfiles = readMetadata(File(paths.workspace, WORKSPACE_PROFILES_STATE_FILE)),
                previousProfilesTemporary = readMetadata(File(paths.workspace, "$WORKSPACE_PROFILES_STATE_FILE.tmp")),
            )
            journal = initial
            try {
                write(initial)
                io.deleteTree(paths.staging)
                io.deleteTree(paths.failedCandidate)
                io.deleteFile(paths.archive)
                check(paths.staging.mkdirs() || paths.staging.isDirectory) { "Не удалось создать staging Rootfs" }
                prepareCandidate(paths.staging, paths.archive)
                checkInterrupted()
                check(!Files.isSymbolicLink(paths.staging.toPath())) { "Staging Rootfs не должен быть ссылкой" }
                check(paths.staging.canonicalFile.parentFile == paths.directory.canonicalFile) {
                    "Staging Rootfs выходит за пределы каталога установщика"
                }
                check(WorkspaceManager.isRootfsCandidate(paths.staging)) {
                    "Кандидат не содержит необходимые /bin/sh и /usr/bin/env"
                }

                update(RootfsOperationPhase.CANDIDATE_READY)
                fault(RootfsFaultPoint.AFTER_CANDIDATE_READY_JOURNAL)
                fault(RootfsFaultPoint.BEFORE_SWITCH_JOURNAL)
                update(RootfsOperationPhase.SWITCHING)
                fault(RootfsFaultPoint.AFTER_SWITCH_JOURNAL)

                fault(RootfsFaultPoint.BEFORE_OLD_ROOTFS_MOVE)
                checkInterrupted()
                check(!entryExists(paths.backup)) { "Backup Rootfs уже существует; переключение остановлено" }
                if (entryExists(paths.linux)) {
                    io.move(paths.linux, paths.backup)
                }
                fault(RootfsFaultPoint.AFTER_OLD_ROOTFS_MOVE)
                update(RootfsOperationPhase.OLD_ROOTFS_MOVED)
                fault(RootfsFaultPoint.AFTER_OLD_ROOTFS_MOVED_JOURNAL)

                fault(RootfsFaultPoint.BEFORE_CANDIDATE_MOVE)
                checkInterrupted()
                io.move(paths.staging, paths.linux)
                fault(RootfsFaultPoint.AFTER_CANDIDATE_MOVE)
                update(RootfsOperationPhase.CANDIDATE_MOVED)
                fault(RootfsFaultPoint.AFTER_CANDIDATE_MOVED_JOURNAL)

                fault(RootfsFaultPoint.BEFORE_GENERATION_WRITE)
                checkInterrupted()
                io.writeAtomic(paths.generation, current().newGeneration)
                fault(RootfsFaultPoint.AFTER_GENERATION_WRITE)
                update(RootfsOperationPhase.GENERATION_WRITTEN)
                fault(RootfsFaultPoint.AFTER_GENERATION_JOURNAL)

                fault(RootfsFaultPoint.BEFORE_PROFILE_INVALIDATION)
                invalidateProfiles(paths)
                fault(RootfsFaultPoint.AFTER_PROFILE_INVALIDATION)
                update(RootfsOperationPhase.PROFILES_INVALIDATED)
                fault(RootfsFaultPoint.AFTER_PROFILES_JOURNAL)

                check(manager.hasRootfs(paths.root)) { "Новый Rootfs исчез при переключении" }
                check(readGeneration(paths) == current().newGeneration) { "Новый generation marker не подтверждён" }
                fault(RootfsFaultPoint.BEFORE_INSTALLED_JOURNAL)
                update(RootfsOperationPhase.INSTALLED)
            } catch (error: Exception) {
                val failedJournal = journal ?: initial
                val rollback = try {
                    rollback(failedJournal)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                    throw RootfsInstallException(
                        "Установка Rootfs не подтверждена и откат не завершён: ${error.message ?: error.javaClass.simpleName}. " +
                            "Backup, staging и текущий Rootfs сохранены; прежняя среда не объявляется восстановленной.",
                        RootfsEnvironmentState.ROLLBACK_UNCONFIRMED,
                        error,
                    )
                }
                val state = if (rollback.changed) RootfsEnvironmentState.RESTORED else RootfsEnvironmentState.PRESERVED
                val stateText = if (rollback.changed) {
                    "Прежняя среда и метаданные восстановлены и проверены."
                } else {
                    "Прежняя среда и метаданные не изменялись."
                }
                val cleanupText = rollback.cleanupWarning?.let { " Очистка после отката не завершена: $it" }.orEmpty()
                throw RootfsInstallException(
                    "Установка Rootfs не выполнена: ${error.message ?: error.javaClass.simpleName}. $stateText$cleanupText",
                    state,
                    error,
                    rollback.cleanupWarning,
                )
            }

            val committed = current()
            val cleanupFailure = try {
                fault(RootfsFaultPoint.AFTER_INSTALLED_JOURNAL)
                fault(RootfsFaultPoint.BEFORE_BACKUP_CLEANUP)
                cleanupOperationDirectories(paths)
                fault(RootfsFaultPoint.AFTER_BACKUP_CLEANUP)
                io.deleteFile(paths.journal)
                null
            } catch (error: Exception) {
                error
            }
            if (cleanupFailure != null) {
                val warning = "Новая среда установлена и проверена, но очистка backup не завершена: " +
                    "${cleanupFailure.message ?: cleanupFailure.javaClass.simpleName}. Откат не выполнялся; восстановление повторит только локальную очистку."
                try {
                    update(RootfsOperationPhase.CLEANUP_PENDING)
                } catch (_: Exception) {
                    // INSTALLED is already durable; the next local recovery retries the same cleanup.
                }
                return RootfsInstallResult(committed.newGeneration, warning)
            }
            return RootfsInstallResult(committed.newGeneration)
        }

        fun rollback(failedJournal: RootfsOperationJournal): RollbackReport {
            val changed = failedJournal.phase.ordinal >= RootfsOperationPhase.OLD_ROOTFS_MOVED.ordinal ||
                directoryIdentity(paths.linux) != failedJournal.previousRootfsIdentity
            restoreLayout(failedJournal)
            restoreMetadata(failedJournal)
            verifyRestored(failedJournal)
            val cleanupWarning = try {
                writeJournal(paths, failedJournal.copy(phase = RootfsOperationPhase.ROLLED_BACK))
                cleanupAfterRollback(failedJournal)
                null
            } catch (error: Exception) {
                error.message ?: error.javaClass.simpleName
            }
            return RollbackReport(changed, cleanupWarning)
        }

        fun verifyRestored(failedJournal: RootfsOperationJournal) {
            if (failedJournal.previousRootfsExisted) {
                check(entryExists(paths.linux)) { "Прежний Rootfs не найден после отката" }
                check(directoryIdentity(paths.linux) == failedJournal.previousRootfsIdentity) {
                    "Каталог прежнего Rootfs не подтверждён после отката"
                }
                check(manager.hasRootfs(paths.root) == failedJournal.previousRootfsUsable) {
                    "Структура прежнего Rootfs не соответствует исходному состоянию"
                }
            } else {
                check(!entryExists(paths.linux)) { "Новый Rootfs остался на месте хотя прежней среды не было" }
            }
            check(readGeneration(paths) == failedJournal.previousGeneration) {
                "Прежний generation marker не восстановлен"
            }
            check(readMetadata(File(paths.workspace, WORKSPACE_PROFILES_STATE_FILE)) == failedJournal.previousProfiles) {
                "Метаданные профилей не восстановлены"
            }
            check(readMetadata(File(paths.workspace, "$WORKSPACE_PROFILES_STATE_FILE.tmp")) == failedJournal.previousProfilesTemporary) {
                "Временные метаданные профилей не восстановлены"
            }
        }

        fun cleanupAfterRollback(failedJournal: RootfsOperationJournal) {
            verifyRestored(failedJournal)
            cleanupOperationDirectories(paths)
            io.deleteFile(paths.journal)
        }

        private fun restoreLayout(failedJournal: RootfsOperationJournal) {
            if (failedJournal.previousRootfsExisted) {
                if (entryExists(paths.backup)) {
                    check(directoryIdentity(paths.backup) == failedJournal.previousRootfsIdentity) {
                        "Backup не совпадает с прежним Rootfs; автоматический откат остановлен"
                    }
                    if (entryExists(paths.linux)) moveCandidateAside()
                    io.move(paths.backup, paths.linux)
                } else {
                    check(entryExists(paths.linux)) { "Прежний Rootfs отсутствует и backup недоступен" }
                    check(directoryIdentity(paths.linux) == failedJournal.previousRootfsIdentity) {
                        "Backup отсутствует, а активный Rootfs не совпадает с прежним"
                    }
                }
            } else {
                check(!entryExists(paths.backup)) { "Backup появился хотя прежнего Rootfs не было" }
                if (entryExists(paths.linux)) moveCandidateAside()
            }
        }

        private fun moveCandidateAside() {
            val target = if (!entryExists(paths.staging)) paths.staging else paths.failedCandidate
            check(!entryExists(target)) { "Некуда сохранить неподтверждённый кандидат при откате" }
            io.move(paths.linux, target)
        }

        private fun restoreMetadata(failedJournal: RootfsOperationJournal) {
            writeOrDelete(paths.generation, failedJournal.previousGeneration)
            writeOrDelete(File(paths.workspace, WORKSPACE_PROFILES_STATE_FILE), failedJournal.previousProfiles)
            writeOrDelete(
                File(paths.workspace, "$WORKSPACE_PROFILES_STATE_FILE.tmp"),
                failedJournal.previousProfilesTemporary,
            )
        }

        private fun writeOrDelete(target: File, value: String?) {
            if (readMetadata(target) == value) return
            if (value == null) io.deleteFile(target) else io.writeAtomic(target, value)
        }

        private fun update(phase: RootfsOperationPhase) {
            val previous = current()
            val candidateIdentity = if (phase == RootfsOperationPhase.CANDIDATE_MOVED) {
                directoryIdentity(paths.linux)
            } else {
                previous.newRootfsIdentity
            }
            val updated = previous.copy(
                phase = phase,
                newRootfsIdentity = candidateIdentity,
                updatedAt = System.currentTimeMillis(),
            )
            write(updated)
        }

        private fun write(value: RootfsOperationJournal) {
            writeJournal(paths, value)
            journal = value
        }

        private fun current(): RootfsOperationJournal = journal ?: error("Журнал Rootfs ещё не создан")
    }

    private fun invalidateProfiles(paths: OperationPaths) {
        io.deleteFile(File(paths.workspace, WORKSPACE_PROFILES_STATE_FILE))
        io.deleteFile(File(paths.workspace, "$WORKSPACE_PROFILES_STATE_FILE.tmp"))
    }

    private fun cleanupOperationDirectories(paths: OperationPaths) {
        io.deleteTree(paths.backup)
        io.deleteTree(paths.staging)
        io.deleteTree(paths.failedCandidate)
        io.deleteFile(paths.archive)
        io.deleteTree(paths.prootTemporary)
    }

    private fun <T> withFileLock(root: String, block: (OperationPaths) -> T): T {
        val paths = OperationPaths(manager, root)
        paths.ensureOwnedDirectory()
        ensureLockFile(paths.lock)
        return FileChannel.open(paths.lock.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw RootfsBusyException("операция для этой рабочей папки уже выполняется")
            lock.use { block(paths) }
        }
    }

    private fun readJournal(paths: OperationPaths): RootfsOperationJournal? {
        if (!entryExists(paths.journal)) return null
        check(!Files.isSymbolicLink(paths.journal.toPath())) { "Журнал Rootfs не должен быть ссылкой" }
        check(paths.journal.isFile && paths.journal.length() <= MAX_JOURNAL_BYTES) { "Журнал Rootfs повреждён или слишком велик" }
        val value = paths.journal.readText()
        return runCatching { json.decodeFromString<RootfsOperationJournal>(value) }.getOrElse {
            throw IOException("Журнал Rootfs не читается; данные операции сохранены", it)
        }
    }

    private fun writeJournal(paths: OperationPaths, value: RootfsOperationJournal) {
        io.writeAtomic(paths.journal, json.encodeToString(RootfsOperationJournal.serializer(), value))
    }

    private fun readGeneration(paths: OperationPaths): String? = readMetadata(paths.generation)

    private fun readMetadata(file: File): String? {
        if (!entryExists(file)) return null
        check(!Files.isSymbolicLink(file.toPath())) { "Метаданные Rootfs не должны быть ссылкой: ${file.name}" }
        check(file.isFile && file.length() <= MAX_METADATA_BYTES) { "Метаданные Rootfs повреждены: ${file.name}" }
        return file.readText()
    }

    private fun ensureLockFile(file: File) {
        check(!Files.isSymbolicLink(file.toPath())) { "Lock-файл Rootfs не должен быть ссылкой" }
        if (!entryExists(file)) {
            runCatching { Files.createFile(file.toPath()) }
        }
        check(file.isFile) { "Не удалось создать lock-файл Rootfs" }
    }

    private fun directoryIdentity(file: File): String? {
        if (!entryExists(file)) return null
        return runCatching {
            val path = file.toPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val link = if (Files.isSymbolicLink(path)) Files.readSymbolicLink(path).toString() else ""
            listOf(
                attributes.fileKey()?.toString().orEmpty(),
                attributes.creationTime().toString(),
                attributes.isDirectory,
                attributes.isSymbolicLink,
                link,
            ).joinToString(":")
        }.getOrElse { throw IOException("Не удалось получить идентичность Rootfs: ${file.name}", it) }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Rootfs install cancelled")
    }

    private inner class OperationPaths(
        manager: WorkspaceManager,
        val root: String,
    ) {
        val workspace: File = manager.workspaceDir(root)
        val linux: File = manager.linuxDir(root)
        val directory: File = File(workspace, INSTALLER_DIRECTORY)
        val journal: File = File(directory, JOURNAL_FILE)
        val lock: File = File(directory, LOCK_FILE)
        val staging: File = File(directory, STAGING_DIRECTORY)
        val backup: File = File(directory, BACKUP_DIRECTORY)
        val failedCandidate: File = File(directory, FAILED_CANDIDATE_DIRECTORY)
        val prootTemporary: File = File(directory, PROOT_TEMP_DIRECTORY)
        val archive: File = File(directory, ARCHIVE_FILE)
        val generation: File = File(workspace, WorkspaceManager.ROOTFS_GENERATION_FILE)

        fun ensureOwnedDirectory() {
            check(!Files.isSymbolicLink(workspace.toPath())) { "Каталог рабочей папки не должен быть ссылкой" }
            check(!Files.isSymbolicLink(directory.toPath())) { "Каталог установщика Rootfs не должен быть ссылкой" }
            check(directory.mkdirs() || directory.isDirectory) { "Не удалось создать каталог установщика Rootfs" }
            val canonicalWorkspace = workspace.canonicalFile
            val canonicalDirectory = directory.canonicalFile
            check(canonicalDirectory.parentFile == canonicalWorkspace) {
                "Каталог установщика Rootfs выходит за пределы рабочей папки"
            }
            listOf(staging, backup, failedCandidate, prootTemporary, archive, journal, lock).forEach { child ->
                check(child.parentFile == directory) { "Некорректный путь операции Rootfs" }
            }
        }
    }

    private data class RollbackReport(
        val changed: Boolean,
        val cleanupWarning: String?,
    )

    @Serializable
    private data class RootfsOperationJournal(
        val root: String,
        val archiveExtension: String,
        val version: Int = JOURNAL_VERSION,
        val operationId: String = UUID.randomUUID().toString(),
        val phase: RootfsOperationPhase = RootfsOperationPhase.PREPARING,
        val previousRootfsExisted: Boolean,
        val previousRootfsUsable: Boolean,
        val previousRootfsIdentity: String?,
        val previousGeneration: String?,
        val previousProfiles: String?,
        val previousProfilesTemporary: String?,
        val newRootfsIdentity: String? = null,
        val newGeneration: String = UUID.randomUUID().toString(),
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = createdAt,
    )

    @Serializable
    private enum class RootfsOperationPhase {
        PREPARING,
        CANDIDATE_READY,
        SWITCHING,
        OLD_ROOTFS_MOVED,
        CANDIDATE_MOVED,
        GENERATION_WRITTEN,
        PROFILES_INVALIDATED,
        INSTALLED,
        CLEANUP_PENDING,
        ROLLED_BACK,
    }

    companion object {
        internal const val INSTALLER_DIRECTORY = ".rootfs-installer"
        internal const val STAGING_DIRECTORY = "staging"
        internal const val BACKUP_DIRECTORY = "backup"
        internal const val FAILED_CANDIDATE_DIRECTORY = "failed-candidate"
        internal const val PROOT_TEMP_DIRECTORY = "proot-tmp"
        private const val JOURNAL_FILE = "operation.json"
        private const val LOCK_FILE = "operation.lock"
        private const val ARCHIVE_FILE = "rootfs-download"
        private const val JOURNAL_VERSION = 1
        private const val MAX_JOURNAL_BYTES = 4 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    }
}

private fun entryExists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
