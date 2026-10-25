package me.rerere.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

/** A development runtime owned by one workspace Rootfs, never by the Android host. */
enum class WorkspaceProfileKind { PYTHON, NODE }

enum class WorkspaceProfileStatus {
    NOT_INSTALLED,
    PREPARING,
    CHECKING,
    READY,
    ERROR,
    CANCELLED,
    INTERRUPTED,
    UNSUPPORTED,
}

@Serializable
data class WorkspaceProfileState(
    val kind: WorkspaceProfileKind,
    val status: WorkspaceProfileStatus = WorkspaceProfileStatus.NOT_INSTALLED,
    val version: String? = null,
    val packageManagerVersion: String? = null,
    val executablePath: String? = null,
    val packageManagerPath: String? = null,
    val exitCode: Int? = null,
    val diagnostic: String? = null,
    val errorReason: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class WorkspaceProfilesSnapshot(
    val rootfsStamp: String = "",
    val python: WorkspaceProfileState = WorkspaceProfileState(WorkspaceProfileKind.PYTHON),
    val node: WorkspaceProfileState = WorkspaceProfileState(WorkspaceProfileKind.NODE),
) {
    fun get(kind: WorkspaceProfileKind): WorkspaceProfileState = when (kind) {
        WorkspaceProfileKind.PYTHON -> python
        WorkspaceProfileKind.NODE -> node
    }

    fun with(state: WorkspaceProfileState): WorkspaceProfilesSnapshot = when (state.kind) {
        WorkspaceProfileKind.PYTHON -> copy(python = state)
        WorkspaceProfileKind.NODE -> copy(node = state)
    }

    companion object {
        fun empty(rootfsStamp: String = "") = WorkspaceProfilesSnapshot(rootfsStamp = rootfsStamp)
    }
}

/** Official, immutable Node.js fallback. Hash values are pinned per release architecture. */
data class WorkspaceNodeRelease(
    val version: String,
    val npmVersion: String,
    val sha256ByArchitecture: Map<String, String>,
) {
    fun archiveName(architecture: String) = "node-$version-linux-$architecture.tar.xz"
    fun archiveUrl(architecture: String) = "$NODE_DIST_ROOT/$version/${archiveName(architecture)}"
    fun checksumsUrl() = "$NODE_DIST_ROOT/$version/SHASUMS256.txt"
    fun installPath() = "/opt/rikkahub/node-$version"

    companion object {
        private const val NODE_DIST_ROOT = "https://nodejs.org/dist"
        val PINNED = WorkspaceNodeRelease(
            version = "v24.22.1",
            npmVersion = "11.19.1",
            sha256ByArchitecture = mapOf(
                "arm64" to "dbabf7a1cbf947e6059ffad2c27b80041db97dea1e553b62afdee7f13fca336e",
                "x64" to "6c172a9ab6718f7b801fb5a7bd486af2ee5e32758086a04b84fcce2aa77095e6",
            ),
        )
    }
}

private class WorkspaceProfileFailure(
    val reason: String,
    val resultExitCode: Int? = null,
    val resultDiagnostic: String? = null,
    val resultStatus: WorkspaceProfileStatus = WorkspaceProfileStatus.ERROR,
) : Exception(reason)

/**
 * Prepares and verifies app-owned Python/Node profiles inside one selected Rootfs.
 * Public operations are blocking by design; the app repository wraps them in runInterruptible(IO).
 */
class WorkspaceProfileService(
    private val manager: WorkspaceManager,
    private val nodeRelease: WorkspaceNodeRelease = WorkspaceNodeRelease.PINNED,
    private val fetchText: (String) -> String = ::fetchOfficialText,
    private val download: (String, File) -> Unit = ::downloadOfficialFile,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun states(root: String): WorkspaceProfilesSnapshot {
        val stamp = rootfsStamp(root)
        val file = stateFile(root)
        if (!file.isFile) return WorkspaceProfilesSnapshot.empty(stamp)
        val stored = runCatching {
            json.decodeFromString<WorkspaceProfilesSnapshot>(file.readText())
        }.getOrNull()
        if (stored != null && stored.rootfsStamp == stamp) return stored
        // A replaced, removed or restored Rootfs must never inherit a stale READY state.
        return WorkspaceProfilesSnapshot.empty(stamp).also { writeSnapshot(root, it) }
    }

    fun reset(root: String) {
        val file = stateFile(root)
        file.delete()
        File(file.parentFile, "$PROFILE_STATE_FILE.tmp").delete()
    }

    fun prepare(
        root: String,
        kind: WorkspaceProfileKind,
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceProfileState {
        saveState(root, initialState(root, kind, WorkspaceProfileStatus.PREPARING))
        val operationStamp = rootfsStamp(root)
        return try {
            ensureUsableSupportedRootfs(root)
            val existing = performFunctionalCheck(root, kind, shellCompatibilityMode)
            ensureRootfsUnchanged(root, operationStamp)
            if (existing.status == WorkspaceProfileStatus.READY) {
                return saveState(root, existing)
            }
            when (kind) {
                WorkspaceProfileKind.PYTHON -> preparePython(root, shellCompatibilityMode)
                WorkspaceProfileKind.NODE -> prepareNode(root, shellCompatibilityMode)
            }
            ensureRootfsUnchanged(root, operationStamp)
            val verified = performFunctionalCheck(root, kind, shellCompatibilityMode)
            ensureRootfsUnchanged(root, operationStamp)
            saveState(root, verified)
        } catch (failure: WorkspaceProfileFailure) {
            saveState(root, failure.state(kind))
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            saveState(
                root,
                WorkspaceProfileState(
                    kind = kind,
                    status = WorkspaceProfileStatus.ERROR,
                    errorReason = error.message ?: "Не удалось подготовить профиль.",
                    diagnostic = error::class.java.simpleName,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun verify(
        root: String,
        kind: WorkspaceProfileKind,
        shellCompatibilityMode: Boolean = false,
    ): WorkspaceProfileState {
        saveState(root, initialState(root, kind, WorkspaceProfileStatus.CHECKING))
        val operationStamp = rootfsStamp(root)
        return try {
            ensureUsableSupportedRootfs(root)
            val verified = performFunctionalCheck(root, kind, shellCompatibilityMode)
            ensureRootfsUnchanged(root, operationStamp)
            saveState(root, verified)
        } catch (failure: WorkspaceProfileFailure) {
            saveState(root, failure.state(kind))
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            saveState(
                root,
                WorkspaceProfileState(
                    kind = kind,
                    status = WorkspaceProfileStatus.ERROR,
                    errorReason = error.message ?: "Не удалось проверить профиль.",
                    diagnostic = error::class.java.simpleName,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun markCancelled(root: String, kind: WorkspaceProfileKind): WorkspaceProfileState {
        val current = states(root).get(kind)
        if (current.status !in ACTIVE_STATUSES) return current
        return saveState(
            root,
            clearedState(root, kind, WorkspaceProfileStatus.CANCELLED).copy(
                errorReason = "Операция остановлена пользователем.",
            ),
        )
    }

    fun markInterrupted(root: String, kind: WorkspaceProfileKind): WorkspaceProfileState? {
        val current = states(root).get(kind)
        if (current.status !in ACTIVE_STATUSES) return null
        return saveState(
            root,
            clearedState(root, kind, WorkspaceProfileStatus.INTERRUPTED).copy(
                errorReason = "Операция прервана. Установка не возобновляется автоматически; выполняется только повторная проверка.",
            ),
        )
    }

    fun markFailed(root: String, kind: WorkspaceProfileKind, reason: String): WorkspaceProfileState =
        saveState(
            root,
            clearedState(root, kind, WorkspaceProfileStatus.ERROR).copy(
                errorReason = reason,
                diagnostic = "Операция приложения завершилась с ошибкой.",
            ),
        )

    private fun preparePython(root: String, compatibility: Boolean) {
        executeChecked(
            root = root,
            command = PYTHON_INSTALL_COMMAND,
            compatibility = compatibility,
            timeoutMillis = INSTALL_TIMEOUT_MS,
            action = "установить Python",
        )
    }

    private fun prepareNode(root: String, compatibility: Boolean) {
        if (!NODE_VERSION_REGEX.matches(nodeRelease.version)) {
            throw WorkspaceProfileFailure("Некорректная закреплённая версия Node.js.")
        }
        val architecture = nodeArchitecture(root, compatibility)
        val expectedHash = nodeRelease.sha256ByArchitecture[architecture]
            ?: throw WorkspaceProfileFailure("Для архитектуры $architecture нет проверенной сборки Node.js.")
        val archiveName = nodeRelease.archiveName(architecture)
        val archive = File(manager.linuxDir(root), "tmp/rikkahub-$archiveName")
        try {
            // Download and authenticate before apt or extraction. No curl|bash installer is used.
            val checksums = fetchText(nodeRelease.checksumsUrl())
            val publishedHash = checksums.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.endsWith(" $archiveName") }
                ?.substringBeforeWhitespace()
                ?.lowercase(Locale.US)
            if (publishedHash != expectedHash.lowercase(Locale.US)) {
                throw WorkspaceProfileFailure("Официальный SHASUMS256.txt не подтверждает закреплённый SHA-256 Node.js.")
            }
            archive.parentFile?.mkdirs()
            download(nodeRelease.archiveUrl(architecture), archive)
            val actualHash = archive.sha256()
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw WorkspaceProfileFailure("SHA-256 архива Node.js не совпал. Установка остановлена до apt и распаковки.")
            }
            executeChecked(root, NODE_PREREQUISITES_COMMAND, compatibility, INSTALL_TIMEOUT_MS, "установить prerequisites Node.js")
            val command = NODE_INSTALL_COMMAND
                .replace("__NODE_VERSION__", nodeRelease.version)
                .replace("__NODE_ARCHIVE__", archiveName)
            executeChecked(root, command, compatibility, INSTALL_TIMEOUT_MS, "распаковать Node.js")
        } finally {
            archive.delete()
        }
    }

    private fun nodeArchitecture(root: String, compatibility: Boolean): String {
        val result = execute(root, "uname -m", compatibility, DETECTION_TIMEOUT_MS)
        if (!result.success) {
            throw WorkspaceProfileFailure("Не удалось определить архитектуру Rootfs.", result.exitCode, result.diagnostic())
        }
        return when (result.stdout.trim().lowercase(Locale.US)) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> throw WorkspaceProfileFailure("Архитектура ${result.stdout.trim()} пока не поддерживается официальной сборкой Node.js.")
        }
    }

    private fun performFunctionalCheck(
        root: String,
        kind: WorkspaceProfileKind,
        compatibility: Boolean,
    ): WorkspaceProfileState {
        val command = when (kind) {
            WorkspaceProfileKind.PYTHON -> PYTHON_VERIFY_COMMAND
            WorkspaceProfileKind.NODE -> NODE_VERIFY_COMMAND.replace("__NODE_INSTALL_PATH__", nodeRelease.installPath())
        }
        val result = execute(root, command, compatibility, VERIFY_TIMEOUT_MS)
        if (!result.success) {
            return WorkspaceProfileState(
                kind = kind,
                status = WorkspaceProfileStatus.ERROR,
                exitCode = result.exitCode,
                diagnostic = result.diagnostic(),
                errorReason = if (result.timedOut) "Функциональная проверка превысила ограничение по времени." else "Функциональная проверка не пройдена.",
                updatedAt = System.currentTimeMillis(),
            )
        }
        val values = result.stdout.lineSequence()
            .filter { it.startsWith("RIKKA_") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val version = values["RIKKA_PROFILE_VERSION"]
        val packageManagerVersion = values["RIKKA_PACKAGE_MANAGER_VERSION"]
        val executablePath = values["RIKKA_EXECUTABLE_PATH"]
        val packageManagerPath = values["RIKKA_PACKAGE_MANAGER_PATH"]
        if (listOf(version, packageManagerVersion, executablePath, packageManagerPath).any { it.isNullOrBlank() }) {
            return WorkspaceProfileState(
                kind = kind,
                status = WorkspaceProfileStatus.ERROR,
                exitCode = result.exitCode,
                diagnostic = result.diagnostic(),
                errorReason = "Проверка завершилась без полных фактических сведений о профиле.",
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (kind == WorkspaceProfileKind.NODE && executablePath?.startsWith("${nodeRelease.installPath()}/") == true &&
            (version != nodeRelease.version || packageManagerVersion != nodeRelease.npmVersion)
        ) {
            return WorkspaceProfileState(
                kind = kind,
                status = WorkspaceProfileStatus.ERROR,
                exitCode = result.exitCode,
                diagnostic = result.diagnostic(),
                errorReason = "Отдельная сборка Node.js не соответствует закреплённым версиям Node/npm.",
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (kind == WorkspaceProfileKind.NODE && values["RIKKA_NODE_MAJOR"]?.toIntOrNull() !in SUPPORTED_NODE_MAJORS) {
            return WorkspaceProfileState(
                kind = kind,
                status = WorkspaceProfileStatus.ERROR,
                exitCode = result.exitCode,
                diagnostic = result.diagnostic(),
                errorReason = "Поддерживаются только Node.js LTS major 22 и 24; будет использована официальная закреплённая сборка.",
                updatedAt = System.currentTimeMillis(),
            )
        }
        return WorkspaceProfileState(
            kind = kind,
            status = WorkspaceProfileStatus.READY,
            version = version,
            packageManagerVersion = packageManagerVersion,
            executablePath = executablePath,
            packageManagerPath = packageManagerPath,
            exitCode = 0,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun ensureUsableSupportedRootfs(root: String) {
        if (!manager.hasRootfs(root)) {
            throw WorkspaceProfileFailure("Rootfs отсутствует или повреждён. Сначала установите или восстановите Linux; автоматическая переустановка не выполняется.")
        }
        val values = sequenceOf("etc/os-release", "usr/lib/os-release")
            .map { File(manager.linuxDir(root), it) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { !it.startsWith("#") && it.indexOf('=') > 0 }
            ?.associate { line ->
                line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            }
        // ID_LIKE is deliberately insufficient: derivatives need their own verified package policy.
        if (values?.get("ID") !in SUPPORTED_DISTROS) {
            throw WorkspaceProfileFailure(
                reason = "Дистрибутив пока не поддерживается. Поддерживаются только точные ID=ubuntu и ID=debian; apt не запускался.",
                resultStatus = WorkspaceProfileStatus.UNSUPPORTED,
            )
        }
    }

    private fun ensureRootfsUnchanged(root: String, operationStamp: String) {
        if (rootfsStamp(root) != operationStamp) {
            throw WorkspaceProfileFailure("Rootfs был заменён или удалён во время операции. Устаревшее состояние «Готово» сброшено.")
        }
    }

    private fun executeChecked(
        root: String,
        command: String,
        compatibility: Boolean,
        timeoutMillis: Long,
        action: String,
    ) {
        val result = execute(root, command, compatibility, timeoutMillis)
        if (!result.success) {
            throw WorkspaceProfileFailure("Не удалось $action.", result.exitCode, result.diagnostic())
        }
    }

    private fun execute(
        root: String,
        command: String,
        compatibility: Boolean,
        timeoutMillis: Long,
    ): WorkspaceCommandResult = manager.executeCommand(
        root = root,
        command = command,
        timeoutMillis = timeoutMillis,
        shellCompatibilityMode = compatibility,
    )

    private fun initialState(root: String, kind: WorkspaceProfileKind, status: WorkspaceProfileStatus) =
        clearedState(root, kind, status)

    private fun clearedState(root: String, kind: WorkspaceProfileKind, status: WorkspaceProfileStatus) =
        WorkspaceProfileState(
            kind = kind,
            status = status,
            updatedAt = System.currentTimeMillis(),
        )

    private fun WorkspaceProfileFailure.state(kind: WorkspaceProfileKind) = WorkspaceProfileState(
        kind = kind,
        status = resultStatus,
        exitCode = resultExitCode,
        diagnostic = resultDiagnostic?.take(MAX_DIAGNOSTIC_CHARS),
        errorReason = reason,
        updatedAt = System.currentTimeMillis(),
    )

    private val WorkspaceCommandResult.success get() = exitCode == 0 && !timedOut

    private fun WorkspaceCommandResult.diagnostic(): String = buildString {
        if (stdout.isNotBlank()) append("stdout:\n").append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("stderr:\n").append(stderr)
        }
        if (truncated) append("\nВывод команды был усечён исполнителем.")
    }.ifBlank { "Команда завершилась без диагностического вывода." }.take(MAX_DIAGNOSTIC_CHARS)

    private fun saveState(root: String, state: WorkspaceProfileState): WorkspaceProfileState {
        val current = states(root)
        writeSnapshot(root, current.with(state).copy(rootfsStamp = rootfsStamp(root)))
        return state
    }

    private fun writeSnapshot(root: String, snapshot: WorkspaceProfilesSnapshot) {
        val target = stateFile(root)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$PROFILE_STATE_FILE.tmp")
        temporary.writeText(json.encodeToString(WorkspaceProfilesSnapshot.serializer(), snapshot))
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun stateFile(root: String) = File(manager.workspaceDir(root), PROFILE_STATE_FILE)

    private fun rootfsStamp(root: String): String {
        val workspace = manager.workspaceDir(root)
        val linux = manager.linuxDir(root)
        val generation = File(workspace, WorkspaceManager.ROOTFS_GENERATION_FILE)
            .takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val identity = listOf("bin/sh", "bin/bash", "etc/os-release", "usr/lib/os-release")
            .joinToString("|") { relative -> fileIdentity(File(linux, relative)) }
        return "${manager.hasRootfs(root)}:$generation:$identity"
    }

    private fun fileIdentity(file: File): String = runCatching {
        val path = file.toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val link = if (Files.isSymbolicLink(path)) Files.readSymbolicLink(path).toString() else ""
        listOf(attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey(), link).joinToString(":")
    }.getOrElse { "missing" }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Node.js download verification cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.substringBeforeWhitespace() = trim().split(Regex("\\s+")).first()

    companion object {
        private const val PROFILE_STATE_FILE = ".workspace-profiles.json"
        private const val INSTALL_TIMEOUT_MS = 20 * 60_000L
        private const val VERIFY_TIMEOUT_MS = 180_000L
        private const val DETECTION_TIMEOUT_MS = 30_000L
        private const val MAX_DIAGNOSTIC_CHARS = 12_000
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private val SUPPORTED_DISTROS = setOf("ubuntu", "debian")
        private val SUPPORTED_NODE_MAJORS = setOf(22, 24)
        private val NODE_VERSION_REGEX = Regex("v\\d+\\.\\d+\\.\\d+")
        private val ACTIVE_STATUSES = setOf(WorkspaceProfileStatus.PREPARING, WorkspaceProfileStatus.CHECKING)
    }
}

private fun fetchOfficialText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    connection.instanceFollowRedirects = true
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("Официальный источник Node.js вернул HTTP $code")
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Node.js metadata request cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > 1024 * 1024) error("Ответ официального источника Node.js слишком большой")
            }
        }
        return output.toString(Charsets.UTF_8.name())
    } finally {
        connection.disconnect()
    }
}

private fun downloadOfficialFile(url: String, target: File) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    connection.instanceFollowRedirects = true
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("Официальный архив Node.js вернул HTTP $code")
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Node.js download cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    } finally {
        connection.disconnect()
    }
}

private const val PYTHON_INSTALL_COMMAND = """
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get install -y --no-install-recommends python3 python3-venv python3-pip
"""

private const val NODE_PREREQUISITES_COMMAND = """
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get install -y --no-install-recommends ca-certificates xz-utils
"""

private const val PYTHON_VERIFY_COMMAND = """
set -eu
python_bin="$(command -v python3)"
python_version="$("$python_bin" --version 2>&1)"
system_pip_version="$("$python_bin" -m pip --version 2>&1)"
tmp_dir="$(mktemp -d /tmp/rikkahub-python-check.XXXXXX)"
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM
"$python_bin" - "$tmp_dir/json-utf8.json" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
value = {"message": "проверка", "items": [1, 2, 3]}
path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
loaded = json.loads(path.read_text(encoding="utf-8"))
assert loaded == value
PY
"$python_bin" -m venv "$tmp_dir/venv"
venv_python="$tmp_dir/venv/bin/python"
"$venv_python" --version >/dev/null 2>&1
"$venv_python" -m pip --version >/dev/null 2>&1
printf 'RIKKA_PROFILE_VERSION=%s\n' "$python_version"
printf 'RIKKA_PACKAGE_MANAGER_VERSION=%s\n' "$system_pip_version"
printf 'RIKKA_EXECUTABLE_PATH=%s\n' "$python_bin"
printf 'RIKKA_PACKAGE_MANAGER_PATH=%s -m pip\n' "$python_bin"
"""

private const val NODE_VERIFY_COMMAND = """
set -eu
if [ -x "__NODE_INSTALL_PATH__/bin/node" ]; then
    node_bin="__NODE_INSTALL_PATH__/bin/node"
else
    node_bin="$(command -v node)"
fi
node_dir="$(dirname "$node_bin")"
if [ -x "$node_dir/npm" ]; then
    npm_bin="$node_dir/npm"
else
    npm_bin="$(command -v npm)"
fi
node_version="$("$node_bin" --version)"
npm_version="$(PATH="$node_dir:$PATH" "$npm_bin" --version)"
tmp_dir="$(mktemp -d /tmp/rikkahub-node-check.XXXXXX)"
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM
"$node_bin" - "$tmp_dir/json-file.json" <<'JS'
const fs = require('fs');
const file = process.argv[2];
const value = { message: 'проверка', items: [1, 2, 3] };
fs.writeFileSync(file, JSON.stringify(value), 'utf8');
const loaded = JSON.parse(fs.readFileSync(file, 'utf8'));
if (JSON.stringify(loaded) !== JSON.stringify(value)) process.exit(1);
JS
node_major="$("$node_bin" -p 'Number(process.versions.node.split(".")[0])')"
printf 'RIKKA_PROFILE_VERSION=%s\n' "$node_version"
printf 'RIKKA_PACKAGE_MANAGER_VERSION=%s\n' "$npm_version"
printf 'RIKKA_EXECUTABLE_PATH=%s\n' "$node_bin"
printf 'RIKKA_PACKAGE_MANAGER_PATH=%s\n' "$npm_bin"
printf 'RIKKA_NODE_MAJOR=%s\n' "$node_major"
"""

private const val NODE_INSTALL_COMMAND = """
set -eu
version='__NODE_VERSION__'
archive='/tmp/rikkahub-__NODE_ARCHIVE__'
base='/opt/rikkahub'
target="$base/node-$version"
staging="$base/.node-$version.staging"
backup="$base/.node-$version.backup"
rm -rf "$staging" "$backup"
mkdir -p "$staging"
tar -xJf "$archive" -C "$staging"
if [ -d "$target" ]; then
    mv "$target" "$backup"
fi
if ! mv "$staging/node-$version" "$target"; then
    if [ -d "$backup" ]; then mv "$backup" "$target"; fi
    exit 1
fi
rm -rf "$staging" "$backup"
"""
