package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceProfileServiceTest {
    @Rule @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `python preparation installs packages and passes functional checks`() {
        var healthy = false
        val fixture = fixture { context ->
            when {
                context.command.contains("rikkahub-python-check") -> if (healthy) pythonReady() else failure()
                context.command.contains("python3-venv") -> {
                    healthy = true
                    success()
                }
                else -> error("Unexpected command: ${context.command}")
            }
        }
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.READY, state.status)
        assertEquals("Python 3.12.3", state.version)
        assertEquals("/usr/bin/python3", state.executablePath)
        assertTrue(fixture.runner.commands.single { it.contains("apt-get update") }.contains("--no-install-recommends"))
        assertFalse(fixture.runner.commands.any { it.contains("apt-get upgrade") || it.contains("dist-upgrade") })
    }

    @Test
    fun `healthy existing profile is reused without installation`() {
        val fixture = fixture { context ->
            if (context.command.contains("rikkahub-python-check")) pythonReady() else error("Installation was not expected")
        }
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.READY, state.status)
        assertEquals(1, fixture.runner.commands.size)
        assertFalse(fixture.runner.commands.any { it.contains("apt-get") })
    }

    @Test
    fun `installation error preserves exit code and diagnostic`() {
        val fixture = fixture { context ->
            when {
                context.command.contains("rikkahub-python-check") -> failure()
                context.command.contains("apt-get") -> WorkspaceCommandResult(42, "", "apt repository unavailable")
                else -> error("Unexpected command")
            }
        }
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.ERROR, state.status)
        assertEquals(42, state.exitCode)
        assertTrue(state.diagnostic.orEmpty().contains("apt repository unavailable"))
        assertNull(state.version)
    }

    @Test
    fun `failed functional test never becomes ready`() {
        val fixture = fixture { context ->
            when {
                context.command.contains("rikkahub-python-check") -> failure("venv pip failed")
                context.command.contains("apt-get") -> success()
                else -> error("Unexpected command")
            }
        }
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.ERROR, state.status)
        assertTrue(state.diagnostic.orEmpty().contains("venv pip failed"))
    }

    @Test
    fun `node uses pinned official archive architecture and verified hash`() {
        var healthy = false
        var downloadedArchive: File? = null
        val downloadedUrls = mutableListOf<String>()
        // A fake archive with its real digest avoids network while preserving the full verification path.
        val release = WorkspaceNodeRelease.PINNED.copy(
            sha256ByArchitecture = mapOf("arm64" to byteArrayOf(1, 2, 3).sha256())
        )
        val fixture = fixture(
            release = release,
            handler = { context ->
                when {
                    context.command.contains("rikkahub-node-check") -> if (healthy) nodeReady() else failure()
                    context.command == "uname -m" -> WorkspaceCommandResult(0, "aarch64\n", "")
                    context.command.contains("ca-certificates") -> success()
                    context.command.contains("tar -xJf") -> {
                        healthy = true
                        success()
                    }
                    else -> error("Unexpected command: ${context.command}")
                }
            },
            fetchText = { url ->
                assertEquals(release.checksumsUrl(), url)
                "${release.sha256ByArchitecture.getValue("arm64")}  ${release.archiveName("arm64")}\n"
            },
            download = { url, target ->
                downloadedUrls += url
                downloadedArchive = target
                target.writeBytes(byteArrayOf(1, 2, 3))
            },
        )
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.NODE)
        assertEquals(WorkspaceProfileStatus.READY, state.status)
        assertEquals(listOf(release.archiveUrl("arm64")), downloadedUrls)
        assertEquals("v24.22.1", state.version)
        assertEquals("11.19.1", state.packageManagerVersion)
        assertFalse(downloadedArchive!!.exists())
        assertTrue(fixture.runner.commands.any { it.contains("ca-certificates") && it.contains("xz-utils") })
    }

    @Test
    fun `node hash mismatch stops before apt and extraction`() {
        val release = WorkspaceNodeRelease.PINNED
        val fixture = fixture(
            handler = { context ->
                when {
                    context.command.contains("rikkahub-node-check") -> failure()
                    context.command == "uname -m" -> WorkspaceCommandResult(0, "x86_64\n", "")
                    else -> error("apt or extraction must not run")
                }
            },
            fetchText = { "${release.sha256ByArchitecture.getValue("x64")}  ${release.archiveName("x64")}\n" },
            download = { _, target -> target.writeText("wrong archive") },
        )
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.NODE)
        assertEquals(WorkspaceProfileStatus.ERROR, state.status)
        assertTrue(state.errorReason.orEmpty().contains("SHA-256"))
        assertFalse(fixture.runner.commands.any { it.contains("apt-get") || it.contains("tar -xJf") })
    }

    @Test
    fun `unsupported distro does not run apt or shell checks`() {
        val fixture = fixture(distro = "ID=alpine\nID_LIKE=unknown\n") { error("No shell command is allowed") }
        val state = fixture.service.prepare(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.UNSUPPORTED, state.status)
        assertTrue(fixture.runner.commands.isEmpty())
        assertTrue(state.errorReason.orEmpty().contains("не поддерживается"))
    }

    @Test
    fun `public verify is offline and does not reinstall a healthy profile`() {
        val fixture = fixture(
            handler = { context ->
                if (context.command.contains("rikkahub-python-check")) pythonReady() else error("No install expected")
            },
            fetchText = { error("Network must not be used") },
            download = { _, _ -> error("Network must not be used") },
        )
        val state = fixture.service.verify(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.READY, state.status)
        assertEquals(1, fixture.runner.commands.size)
    }

    @Test
    fun `debian derivative with ID_LIKE is not treated as supported distro`() {
        val fixture = fixture(distro = "ID=linuxmint\nID_LIKE=\"ubuntu debian\"\n") { error("No shell command is allowed") }
        val state = fixture.service.verify(ROOT, WorkspaceProfileKind.NODE)
        assertEquals(WorkspaceProfileStatus.UNSUPPORTED, state.status)
        assertTrue(fixture.runner.commands.isEmpty())
    }

    @Test
    fun `rootfs replacement during verify cannot preserve stale ready state`() {
        val fixture = fixture { context ->
            if (!context.command.contains("rikkahub-python-check")) error("Unexpected command")
            File(fixture.manager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE).writeText("new-rootfs")
            pythonReady()
        }
        val state = fixture.service.verify(ROOT, WorkspaceProfileKind.PYTHON)
        assertEquals(WorkspaceProfileStatus.ERROR, state.status)
        assertNull(state.version)
        assertTrue(state.errorReason.orEmpty().contains("Rootfs"))
    }

    @Test
    fun `rootfs replacement resets stale ready state`() {
        val fixture = fixture { context ->
            if (context.command.contains("rikkahub-python-check")) pythonReady() else error("Unexpected command")
        }
        assertEquals(WorkspaceProfileStatus.READY, fixture.service.verify(ROOT, WorkspaceProfileKind.PYTHON).status)
        File(fixture.manager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE).writeText("replacement")
        val state = fixture.service.states(ROOT).python
        assertEquals(WorkspaceProfileStatus.NOT_INSTALLED, state.status)
        assertNull(state.version)
    }

    @Test
    fun `interrupt during installation escapes instead of becoming ready`() {
        val fixture = fixture { context ->
            when {
                context.command.contains("rikkahub-python-check") -> failure()
                context.command.contains("apt-get") -> throw InterruptedException("owned operation cancelled")
                else -> error("Unexpected command")
            }
        }
        assertThrows(InterruptedException::class.java) {
            fixture.service.prepare(ROOT, WorkspaceProfileKind.PYTHON)
        }
        assertTrue(fixture.service.states(ROOT).python.status != WorkspaceProfileStatus.READY)
    }

    @Test
    fun `interrupted operation is not silently marked ready`() {
        lateinit var service: WorkspaceProfileService
        var interrupted: WorkspaceProfileState? = null
        val fixture = fixture { context ->
            when {
                context.command.contains("rikkahub-python-check") -> failure()
                context.command.contains("apt-get") -> {
                    interrupted = service.markInterrupted(ROOT, WorkspaceProfileKind.PYTHON)
                    throw InterruptedException("interrupted")
                }
                else -> error("Unexpected command")
            }
        }
        service = fixture.service
        assertThrows(InterruptedException::class.java) { service.prepare(ROOT, WorkspaceProfileKind.PYTHON) }
        assertEquals(WorkspaceProfileStatus.INTERRUPTED, interrupted?.status)
        assertNull(interrupted?.version)
    }

    private fun fixture(
        distro: String = "ID=ubuntu\nVERSION_ID=\"24.04\"\n",
        release: WorkspaceNodeRelease = WorkspaceNodeRelease.PINNED,
        fetchText: (String) -> String = { error("Network was not expected") },
        download: (String, File) -> Unit = { _, _ -> error("Network was not expected") },
        handler: (WorkspaceShellContext) -> WorkspaceCommandResult,
    ): Fixture {
        val base = temp.newFolder("workspaces-${System.nanoTime()}")
        val runner = RecordingRunner(handler)
        val manager = WorkspaceManager(baseDir = base, shellRunner = runner)
        manager.ensureWorkspace(ROOT)
        File(manager.linuxDir(ROOT), "bin/sh").apply { parentFile.mkdirs(); writeText("shell") }
        File(manager.linuxDir(ROOT), "etc/os-release").apply { parentFile.mkdirs(); writeText(distro) }
        val service = WorkspaceProfileService(manager, release, fetchText, download)
        return Fixture(manager, runner, service)
    }

    private data class Fixture(
        val manager: WorkspaceManager,
        val runner: RecordingRunner,
        val service: WorkspaceProfileService,
    )

    private class RecordingRunner(
        private val handler: (WorkspaceShellContext) -> WorkspaceCommandResult,
    ) : WorkspaceShellRunner {
        val commands = mutableListOf<String>()
        override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
            commands += context.command
            return handler(context)
        }
    }

    private companion object {
        const val ROOT = "profiles-test"
        fun success() = WorkspaceCommandResult(0, "", "")
        fun failure(message: String = "not installed") = WorkspaceCommandResult(1, "", message)
        fun pythonReady() = WorkspaceCommandResult(
            0,
            """
            RIKKA_PROFILE_VERSION=Python 3.12.3
            RIKKA_PACKAGE_MANAGER_VERSION=pip 24.0
            RIKKA_EXECUTABLE_PATH=/usr/bin/python3
            RIKKA_PACKAGE_MANAGER_PATH=/usr/bin/python3 -m pip
            """.trimIndent() + "\n",
            "",
        )
        fun nodeReady() = WorkspaceCommandResult(
            0,
            """
            RIKKA_PROFILE_VERSION=v24.22.1
            RIKKA_PACKAGE_MANAGER_VERSION=11.19.1
            RIKKA_EXECUTABLE_PATH=/opt/rikkahub/node-v24.22.1/bin/node
            RIKKA_PACKAGE_MANAGER_PATH=/opt/rikkahub/node-v24.22.1/bin/npm
            RIKKA_NODE_MAJOR=24
            """.trimIndent() + "\n",
            "",
        )
    }
}

private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }
