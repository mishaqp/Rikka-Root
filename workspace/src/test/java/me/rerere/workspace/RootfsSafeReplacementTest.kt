package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class RootfsSafeReplacementTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var server: HttpServer? = null

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `patcher failure keeps previous rootfs generation profiles and projects`() {
        val fixture = workspaceFixture()
        val archive = tarGz(
            TarEntry("bin/", type = '5'),
            TarEntry("bin/sh", content = "#!/bin/sh\n".toByteArray(), mode = 493),
            TarEntry("usr/bin/", type = '5'),
            TarEntry("usr/bin/env", content = "#!/bin/sh\n".toByteArray(), mode = 493),
            TarEntry("etc/", type = '5'),
            TarEntry("etc/resolv.conf/", type = '5'),
            TarEntry("etc/resolv.conf/child", content = "not a file".toByteArray()),
        )
        val url = serve("broken-patcher.tar.gz", archive)

        val failure = runCatching { fixture.installer.install(ROOT, url) }.exceptionOrNull()

        assertNotNull("The invalid patch target must fail the installation", failure)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `candidate without shell is rejected before previous rootfs is changed`() {
        val fixture = workspaceFixture()
        val url = serve(
            "missing-shell.tar.gz",
            tarGz(
                TarEntry("etc/", type = '5'),
                TarEntry("etc/os-release", content = "ID=debian\n".toByteArray()),
            ),
        )

        val failure = runCatching { fixture.installer.install(ROOT, url) }.exceptionOrNull()

        assertNotNull("A directory tree without /bin/sh is not a Rootfs candidate", failure)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `successful replacement commits new generation invalidates profiles and removes backup`() {
        val fixture = workspaceFixture()
        val previousGeneration = fixture.installer.generation(ROOT)

        val result = fixture.installer.install(ROOT, serve("valid.tar.gz", validArchive()))

        assertNotEquals(previousGeneration, result.generation)
        assertNull(result.cleanupWarning)
        assertEquals(result.generation, fixture.installer.generation(ROOT))
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertFalse(File(fixture.manager.linuxDir(ROOT), "old-environment.txt").exists())
        assertFalse(File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).exists())
        assertFalse(File(fixture.manager.workspaceDir(ROOT), "$PROFILES_FILE.tmp").exists())
        assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
        assertFalse(installerPath(fixture, "operation.json").exists())
        assertEquals(PROJECT_CONTENT, File(fixture.manager.filesDir(ROOT), "project/.venv/state.txt").readText())
        assertEquals("unchanged source\n", File(fixture.manager.filesDir(ROOT), "project/source.txt").readText())
    }

    @Test
    fun `http download failure preserves previous environment`() {
        val fixture = workspaceFixture()
        val path = "/unavailable.tar.gz"
        server!!.createContext(path) { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }

        val failure = installFailure(fixture, "http://127.0.0.1:${server!!.address.port}$path")

        assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `malformed archive failure preserves previous environment`() {
        val fixture = workspaceFixture()
        val url = serve("broken.tar.gz", byteArrayOf(1, 2, 3, 4, 5))

        val failure = installFailure(fixture, url)

        assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `functional candidate probe failure preserves previous environment`() {
        val rejectingRunner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult =
                WorkspaceCommandResult(exitCode = 1, stdout = "", stderr = "candidate probe failed")
        }
        val fixture = workspaceFixture(shellRunner = rejectingRunner)

        val failure = installFailure(fixture, serve("probe-failure.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `candidate without env is rejected before switch`() {
        val fixture = workspaceFixture()
        val archive = tarGz(
            TarEntry("bin/", type = '5'),
            TarEntry("bin/sh", content = NEW_SHELL.toByteArray(), mode = 493),
        )

        installFailure(fixture, serve("missing-env.tar.gz", archive))

        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `old rootfs rename failure leaves previous environment untouched`() {
        val io = object : RootfsTransactionIO() {
            override fun move(source: File, target: File) {
                if (source.name == "linux" && target.name == RootfsReplacement.BACKUP_DIRECTORY) {
                    throw IOException("injected old Rootfs rename failure")
                }
                super.move(source, target)
            }
        }
        val fixture = workspaceFixture(io = io)

        val failure = installFailure(fixture, serve("rename-failure.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `journal write failure during switch restores previous environment`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                if (target.name == "operation.json" && text.contains("SWITCHING")) {
                    throw IOException("injected journal failure")
                }
                super.writeAtomic(target, text)
            }
        }
        val fixture = workspaceFixture(io = io)

        installFailure(fixture, serve("journal-failure.tar.gz", validArchive()))

        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `generation write failure after candidate move restores all previous metadata`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                if (target.name == WorkspaceManager.ROOTFS_GENERATION_FILE) {
                    throw IOException("injected generation failure")
                }
                super.writeAtomic(target, text)
            }
        }
        val fixture = workspaceFixture(io = io)

        val failure = installFailure(fixture, serve("generation-failure.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.RESTORED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `profile invalidation failure restores exact previous profile bytes`() {
        val io = object : RootfsTransactionIO() {
            override fun deleteFile(target: File) {
                if (target.name == PROFILES_FILE) throw IOException("injected profile metadata failure")
                super.deleteFile(target)
            }
        }
        val fixture = workspaceFixture(io = io)

        val failure = installFailure(fixture, serve("profiles-failure.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.RESTORED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `cleanup failure after commit keeps new environment and recovers locally`() {
        val io = object : RootfsTransactionIO() {
            override fun deleteTree(target: File) {
                if (target.name == RootfsReplacement.BACKUP_DIRECTORY) {
                    throw IOException("injected backup cleanup failure")
                }
                super.deleteTree(target)
            }
        }
        val fixture = workspaceFixture(io = io)

        val result = fixture.installer.install(ROOT, serve("cleanup-failure.tar.gz", validArchive()))

        assertNotNull(result.cleanupWarning)
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertTrue(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).isDirectory)
        val recovered = RootfsInstaller(fixture.manager).recover(ROOT)
        assertEquals(RootfsRecoveryStatus.COMMITTED, recovered.status)
        assertEquals(result.generation, recovered.generation)
        assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
    }

    @Test
    fun `unconfirmed rollback preserves backup candidate and reports failure honestly`() {
        val io = object : RootfsTransactionIO() {
            override fun move(source: File, target: File) {
                if (source.name == RootfsReplacement.BACKUP_DIRECTORY && target.name == "linux") {
                    throw IOException("injected rollback rename failure")
                }
                super.move(source, target)
            }
        }
        val fixture = workspaceFixture(
            io = io,
            fault = { point ->
                if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVE) throw IOException("injected switch failure")
            },
        )

        val failure = installFailure(fixture, serve("rollback-failure.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.ROLLBACK_UNCONFIRMED, failure.environmentState)
        assertEquals(OLD_ROOTFS_CONTENT, File(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY), "old-environment.txt").readText())
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertTrue(installerPath(fixture, "operation.json").isFile)
        val recovered = RootfsInstaller(fixture.manager).recover(ROOT)
        assertEquals(RootfsRecoveryStatus.ROLLED_BACK, recovered.status)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `managed symlink cannot escape candidate staging`() {
        val fixture = workspaceFixture()
        val external = tmp.newFile("external-hosts-${System.nanoTime()}")
        external.writeText("outside must survive\n")
        val archive = tarGz(
            TarEntry("bin/", type = '5'),
            TarEntry("bin/sh", content = NEW_SHELL.toByteArray(), mode = 493),
            TarEntry("usr/bin/", type = '5'),
            TarEntry("usr/bin/env", content = "#!/bin/sh\n".toByteArray(), mode = 493),
            TarEntry("etc/", type = '5'),
            TarEntry("etc/hosts", type = '2', linkName = external.absolutePath),
        )

        installFailure(fixture, serve("managed-symlink.tar.gz", archive))

        assertEquals("outside must survive\n", external.readText())
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `installer directory symlink is rejected without touching target`() {
        val fixture = workspaceFixture()
        val external = tmp.newFolder("external-installer-${System.nanoTime()}")
        val marker = File(external, "marker.txt").apply { writeText("outside must survive\n") }
        val installerDirectory = File(
            fixture.manager.workspaceDir(ROOT),
            RootfsReplacement.INSTALLER_DIRECTORY,
        )
        Files.createSymbolicLink(installerDirectory.toPath(), external.toPath())

        installFailure(fixture, "http://127.0.0.1:1/unused.tar.gz")

        assertEquals("outside must survive\n", marker.readText())
        assertTrue(Files.isSymbolicLink(installerDirectory.toPath()))
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `workspace directory symlink is rejected before creating files outside its base`() {
        val base = tmp.newFolder()
        val external = tmp.newFolder()
        File(external, "marker").writeText("must survive")
        val manager = WorkspaceManager(base)
        val alias = File(base, ROOT)
        Files.createSymbolicLink(alias.toPath(), external.toPath())

        val failure = runCatching {
            RootfsInstaller(manager).install(ROOT, "http://127.0.0.1:1/unused.tar.gz")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(listOf("marker"), external.listFiles().orEmpty().map { it.name }.sorted())
        assertEquals("must survive", File(external, "marker").readText())
        assertTrue(Files.isSymbolicLink(alias.toPath()))
    }

    @Test
    fun `dot workspace roots cannot address the base or its parent`() {
        val base = tmp.newFolder()
        val manager = WorkspaceManager(base)

        listOf(".", "..").forEach { root ->
            val failure = runCatching { manager.ensureWorkspace(root) }.exceptionOrNull()
            assertTrue("Invalid root $root must be rejected", failure is IllegalArgumentException)
        }

        assertTrue(base.listFiles().orEmpty().isEmpty())
        assertFalse(File(tmp.root, "linux").exists())
        assertFalse(File(tmp.root, "files").exists())
    }

    @Test
    fun `durable installed journal is committed even when its write reports an error`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                super.writeAtomic(target, text)
                if (target.name == "operation.json" && text.contains("\"phase\":\"INSTALLED\"")) {
                    throw IOException("injected ambiguous result after durable INSTALLED journal")
                }
            }
        }
        val fixture = workspaceFixture(io = io)

        val result = fixture.installer.install(ROOT, serve("ambiguous-installed.tar.gz", validArchive()))

        assertNull(result.cleanupWarning)
        assertEquals(result.generation, fixture.installer.generation(ROOT))
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
        assertFalse(File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).exists())
    }

    @Test
    fun `unreadable commit acknowledgement preserves new rootfs and backup for recovery`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                super.writeAtomic(target, text)
                if (target.name == "operation.json" && text.contains("\"phase\":\"INSTALLED\"")) {
                    target.writeText("unreadable acknowledgement")
                    throw IOException("commit acknowledgement cannot be read")
                }
            }
        }
        val fixture = workspaceFixture(io = io)

        val failure = installFailure(fixture, serve("unreadable-commit.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.ROLLBACK_UNCONFIRMED, failure.environmentState)
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertEquals(
            OLD_ROOTFS_CONTENT,
            File(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY), "old-environment.txt").readText(),
        )
        assertEquals(RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED, RootfsInstaller(fixture.manager).recover(ROOT).status)
        assertEquals(PROJECT_CONTENT, File(fixture.manager.filesDir(ROOT), "project/.venv/state.txt").readText())
    }

    @Test
    fun `failed installed write before commit restores previous environment`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                if (target.name == "operation.json" && text.contains("\"phase\":\"INSTALLED\"")) {
                    throw IOException("commit was not written")
                }
                super.writeAtomic(target, text)
            }
        }
        val fixture = workspaceFixture(io = io)

        val failure = installFailure(fixture, serve("failed-commit.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.RESTORED, failure.environmentState)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `cleanup preserves an unexpected backup directory after commit`() {
        lateinit var fixture: Fixture
        lateinit var retainedBackup: File
        fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.BEFORE_BACKUP_CLEANUP) {
                val backup = installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY)
                retainedBackup = File(fixture.base, "retained-old-rootfs")
                Files.move(backup.toPath(), retainedBackup.toPath())
                backup.mkdirs()
                File(backup, "unexpected-data").writeText("must not be deleted")
            }
        })

        val result = fixture.installer.install(ROOT, serve("unexpected-backup.tar.gz", validArchive()))

        assertNotNull("Unowned backup must be reported without undoing the commit", result.cleanupWarning)
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertEquals("must not be deleted", File(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY), "unexpected-data").readText())
        assertEquals(OLD_ROOTFS_CONTENT, File(retainedBackup, "old-environment.txt").readText())
        assertEquals(RootfsRecoveryStatus.CLEANUP_FAILED, RootfsInstaller(fixture.manager).recover(ROOT).status)
        assertEquals("must not be deleted", File(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY), "unexpected-data").readText())
    }

    @Test
    fun `cleanup warning permits normal commands and profiles while background work is running`() {
        lateinit var fixture: Fixture
        fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.BEFORE_BACKUP_CLEANUP) {
                val backup = installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY)
                Files.move(backup.toPath(), File(fixture.base, "retained-backup").toPath())
                backup.mkdirs()
                File(backup, "unexpected-data").writeText("must survive")
            }
        })
        val installed = fixture.installer.install(ROOT, serve("usable-after-warning.tar.gz", validArchive()))
        assertNotNull(installed.cleanupWarning)
        val process = fixture.manager.startBackgroundCommand(ROOT, "sleep 30")
        try {
            val result = fixture.manager.executeCommand(ROOT, "printf 'NEXT_OK\\n'")

            assertEquals(0, result.exitCode)
            assertEquals("NEXT_OK\n", result.stdout)
            assertEquals(WorkspaceProfileStatus.NOT_INSTALLED, WorkspaceProfileService(fixture.manager).states(ROOT).python.status)
            assertTrue(fixture.manager.backgroundOutput(ROOT, process.id)?.running == true)
            assertEquals("must survive", File(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY), "unexpected-data").readText())
        } finally {
            fixture.manager.stopBackgroundProcess(ROOT, process.id)
        }
    }

    @Test
    fun `completed rollback cleanup accepts newer legitimate profile metadata`() {
        val io = object : RootfsTransactionIO() {
            override fun deleteTree(target: File) {
                val journal = File(target.parentFile, "operation.json")
                if (target.name == RootfsReplacement.STAGING_DIRECTORY &&
                    journal.isFile && journal.readText().contains("\"phase\":\"ROLLED_BACK\"")
                ) throw IOException("persistent staging cleanup failure")
                super.deleteTree(target)
            }
        }
        val fixture = workspaceFixture(io = io, fault = { point ->
            if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVE) throw IOException("trigger rollback")
        })
        val before = WorkspaceProfileService(fixture.manager).states(ROOT)
        val beforeText = Json.encodeToString(WorkspaceProfilesSnapshot.serializer(), before)
        File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).writeText(beforeText)
        val failed = installFailure(fixture, serve("rollback-cleanup.tar.gz", validArchive()))
        assertEquals(RootfsEnvironmentState.RESTORED, failed.environmentState)
        assertNotNull(failed.cleanupWarning)
        // This is a legitimate later profile write, after the durable rollback acknowledgement.
        val latest = before.with(WorkspaceProfileState(
            WorkspaceProfileKind.PYTHON, WorkspaceProfileStatus.READY, version = "Python 3.12.3", updatedAt = 123,
        ))
        val latestText = Json.encodeToString(WorkspaceProfilesSnapshot.serializer(), latest)
        File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).writeText(latestText)

        val recovered = RootfsInstaller(fixture.manager, RootfsPatcher(), io, {}).recover(ROOT)

        assertEquals(RootfsRecoveryStatus.ROLLBACK_CLEANUP_FAILED, recovered.status)
        assertEquals(latestText, File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).readText())
        assertEquals("old shell\n", File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        val result = fixture.manager.executeCommand(ROOT, "printf 'NEXT_OK\\n'")
        assertEquals(0, result.exitCode)
        assertEquals("NEXT_OK\n", result.stdout)
        assertEquals(latestText, File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).readText())
    }

    @Test
    fun `missing rollback acknowledgement remains unconfirmed even when old files were restored`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                if (target.name == "operation.json" && text.contains("\"phase\":\"ROLLED_BACK\"")) {
                    throw IOException("rollback acknowledgement could not be saved")
                }
                super.writeAtomic(target, text)
            }
        }
        val fixture = workspaceFixture(io = io, fault = { point ->
            if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVE) throw IOException("trigger rollback")
        })

        val failure = installFailure(fixture, serve("unacknowledged-rollback.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.ROLLBACK_UNCONFIRMED, failure.environmentState)
        fixture.assertPreviousEnvironment()
        assertEquals(
            RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED,
            RootfsInstaller(fixture.manager, RootfsPatcher(), io, {}).recover(ROOT).status,
        )
        val settled = RootfsInstaller(fixture.manager).recover(ROOT)
        assertEquals(RootfsRecoveryStatus.ROLLED_BACK, settled.status)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `durable rollback acknowledgement is accepted when its write reports an error`() {
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                super.writeAtomic(target, text)
                if (target.name == "operation.json" && text.contains("\"phase\":\"ROLLED_BACK\"")) {
                    throw IOException("rollback acknowledgement already saved")
                }
            }
        }
        val fixture = workspaceFixture(io = io, fault = { point ->
            if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVE) throw IOException("trigger rollback")
        })

        val failure = installFailure(fixture, serve("acknowledged-rollback.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.RESTORED, failure.environmentState)
        assertNull(failure.cleanupWarning)
        fixture.assertPreviousEnvironment()
        assertFalse(installerPath(fixture, "operation.json").exists())
    }

    @Test
    fun `atomic journal remnants are removed before recovery forgets a valid transaction`() {
        val phases = listOf(
            RootfsFaultPoint.AFTER_CANDIDATE_MOVED_JOURNAL to RootfsRecoveryStatus.ROLLED_BACK,
            RootfsFaultPoint.AFTER_INSTALLED_JOURNAL to RootfsRecoveryStatus.COMMITTED,
        )
        phases.forEach { (point, expected) ->
            lateinit var fixture: Fixture
            lateinit var leftovers: List<File>
            fixture = workspaceFixture(fault = { current ->
                if (current == point) {
                    val suffix = ".tmp-12345678-1234-4234-8234-123456789abc"
                    leftovers = listOf(
                        installerPath(fixture, "operation.json$suffix"),
                        File(fixture.manager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE + suffix),
                        File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE + suffix),
                        File(fixture.manager.workspaceDir(ROOT), "$PROFILES_FILE.tmp$suffix"),
                    )
                    leftovers.forEach { it.writeText("partial atomic metadata") }
                    throw ThreadDeath()
                }
            })
            expectThreadDeath(point) {
                fixture.installer.install(ROOT, serve("atomic-remnant-${point.name}.tar.gz", validArchive()))
            }

            val recovered = RootfsInstaller(fixture.manager).recover(ROOT)

            assertEquals(expected, recovered.status)
            assertTrue("Known atomic temporaries survived $point", leftovers.none { it.exists() })
            assertFalse(installerPath(fixture, "operation.json").exists())
            assertEquals(RootfsRecoveryStatus.NO_OPERATION, RootfsInstaller(fixture.manager).recover(ROOT).status)
            if (expected == RootfsRecoveryStatus.ROLLED_BACK) fixture.assertPreviousEnvironment()
            else assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        }
    }

    @Test
    fun `startup recovery observes overlapping local recovery after it settles`() {
        val fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVED_JOURNAL) throw ThreadDeath()
        })
        expectThreadDeath(RootfsFaultPoint.AFTER_CANDIDATE_MOVED_JOURNAL) {
            fixture.installer.install(ROOT, serve("overlapping-recovery.tar.gz", validArchive()))
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        val firstResult = AtomicReference<RootfsRecoveryResult?>()
        val secondResult = AtomicReference<RootfsRecoveryResult?>()
        val blockingIO = object : RootfsTransactionIO() {
            override fun move(source: File, target: File) {
                if (source.name == RootfsReplacement.BACKUP_DIRECTORY && target.name == "linux") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                super.move(source, target)
            }
        }
        val first = Thread {
            firstResult.set(RootfsInstaller(fixture.manager, RootfsPatcher(), blockingIO, {}).recover(ROOT))
        }.apply { start() }
        var second: Thread? = null
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            second = Thread {
                secondEntered.countDown()
                secondResult.set(RootfsInstaller(fixture.manager).recover(ROOT))
                secondReturned.countDown()
            }.apply { start() }
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
            assertFalse("The one-shot startup scan must not discard an in-progress cold recovery", secondReturned.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            first.join(10_000)
            second?.join(10_000)
        }

        assertFalse(first.isAlive)
        assertFalse(checkNotNull(second).isAlive)
        assertEquals(RootfsRecoveryStatus.ROLLED_BACK, firstResult.get()?.status)
        assertTrue(secondResult.get()?.status in setOf(RootfsRecoveryStatus.ROLLED_BACK, RootfsRecoveryStatus.NO_OPERATION))
        assertEquals(OLD_GENERATION, secondResult.get()?.generation)
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `process death before first journal rename removes only its owned temporary file`() {
        lateinit var orphan: File
        val io = object : RootfsTransactionIO() {
            override fun writeAtomic(target: File, text: String) {
                if (target.name == "operation.json") {
                    orphan = File(target.parentFile, "operation.json.tmp-12345678-1234-4234-8234-123456789abc")
                    orphan.writeText("unfinished first journal")
                    throw ThreadDeath()
                }
                super.writeAtomic(target, text)
            }
        }
        val fixture = workspaceFixture(io = io)
        org.junit.Assert.assertThrows(ThreadDeath::class.java) {
            fixture.installer.install(ROOT, "http://127.0.0.1:1/must-not-download.tar.gz")
        }

        val recovered = RootfsInstaller(fixture.manager).recover(ROOT)

        assertEquals(RootfsRecoveryStatus.NO_OPERATION, recovered.status)
        assertFalse(orphan.exists())
        fixture.assertPreviousEnvironment()
        assertEquals(RootfsRecoveryStatus.NO_OPERATION, RootfsInstaller(fixture.manager).recover(ROOT).status)
    }

    @Test
    fun `orphan data without a journal is retained instead of guessing an operation`() {
        val fixture = workspaceFixture()
        val unknown = installerPath(fixture, "operation.json.tmp-not-an-owned-uuid")
        checkNotNull(unknown.parentFile).mkdirs()
        unknown.writeText("user data must survive")

        val recovered = RootfsInstaller(fixture.manager).recover(ROOT)

        assertEquals(RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED, recovered.status)
        assertEquals("user data must survive", unknown.readText())
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `recovery identity remains stable when android creation time falls back to modification time`() {
        // AOSP UnixFileAttributes returns lastModifiedTime() when birthtime is unsupported.
        // Keep real inode/type/link metadata and model only that documented platform difference.
        val io = object : RootfsTransactionIO() {
            override fun attributes(file: File): BasicFileAttributes {
                val actual = super.attributes(file)
                return object : BasicFileAttributes by actual {
                    override fun creationTime(): FileTime = actual.lastModifiedTime()
                }
            }
        }
        val fixture = workspaceFixture(io = io, fault = { point ->
            if (point == RootfsFaultPoint.BEFORE_BACKUP_CLEANUP) throw IOException("retry cleanup on next launch")
        })
        val result = fixture.installer.install(ROOT, serve("android-file-identity.tar.gz", validArchive()))
        assertNotNull(result.cleanupWarning)
        val linux = fixture.manager.linuxDir(ROOT)
        val previousModified = Files.getLastModifiedTime(linux.toPath()).toMillis()
        File(linux, "new-user-file").writeText("new environment remains usable")
        Files.setLastModifiedTime(linux.toPath(), FileTime.fromMillis(previousModified + 10_000))
        val restarted = RootfsInstaller(fixture.manager, RootfsPatcher(), io, {})

        val recovered = restarted.recover(ROOT)

        assertEquals(RootfsRecoveryStatus.COMMITTED, recovered.status)
        assertEquals(result.generation, recovered.generation)
        assertEquals("new environment remains usable", File(linux, "new-user-file").readText())
        assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
    }

    @Test
    fun `rollback preserves an unexpected active directory instead of deleting it`() {
        lateinit var fixture: Fixture
        lateinit var retainedCandidate: File
        fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVE) {
                retainedCandidate = File(fixture.base, "retained-candidate")
                Files.move(fixture.manager.linuxDir(ROOT).toPath(), retainedCandidate.toPath())
                fixture.manager.linuxDir(ROOT).mkdirs()
                File(fixture.manager.linuxDir(ROOT), "unexpected-data").writeText("must be retained")
                throw IOException("active directory changed unexpectedly")
            }
        })

        val failure = installFailure(fixture, serve("unexpected-active.tar.gz", validArchive()))

        assertEquals(RootfsEnvironmentState.ROLLBACK_UNCONFIRMED, failure.environmentState)
        assertEquals("must be retained", File(fixture.manager.linuxDir(ROOT), "unexpected-data").readText())
        assertEquals(NEW_SHELL, File(retainedCandidate, "bin/sh").readText())
        assertEquals(OLD_ROOTFS_CONTENT, File(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY), "old-environment.txt").readText())
        assertEquals(RootfsRecoveryStatus.ROLLBACK_UNCONFIRMED, RootfsInstaller(fixture.manager).recover(ROOT).status)
        assertEquals("must be retained", File(fixture.manager.linuxDir(ROOT), "unexpected-data").readText())
    }

    @Test
    fun `workspace initialization cannot recreate linux between replacement renames`() {
        lateinit var fixture: Fixture
        fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.AFTER_OLD_ROOTFS_MOVE) {
                val rejected = runCatching { fixture.manager.ensureWorkspace(ROOT) }.exceptionOrNull()
                assertTrue("Initialization must respect the replacement gate", rejected is RootfsBusyException)
                assertFalse(fixture.manager.linuxDir(ROOT).exists())
            }
        })

        fixture.installer.install(ROOT, serve("initialization-race.tar.gz", validArchive()))

        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
    }

    @Test
    fun `workspace deletion is rejected while replacement owns its files`() {
        lateinit var fixture: Fixture
        fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.AFTER_CANDIDATE_READY_JOURNAL) {
                val rejected = runCatching { fixture.manager.deleteWorkspace(ROOT) }.exceptionOrNull()
                assertTrue("Deletion must not remove an in-flight transaction", rejected is RootfsBusyException)
                fixture.assertPreviousEnvironment()
            }
        })

        fixture.installer.install(ROOT, serve("deletion-race.tar.gz", validArchive()))

        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        assertEquals(PROJECT_CONTENT, File(fixture.manager.filesDir(ROOT), "project/.venv/state.txt").readText())
    }

    @Test
    fun `profile access restores interrupted rootfs before reading or rewriting readiness`() {
        val fixture = workspaceFixture(fault = { point ->
            if (point == RootfsFaultPoint.AFTER_PROFILE_INVALIDATION) throw ThreadDeath()
        })
        val service = WorkspaceProfileService(fixture.manager)
        val previous = service.states(ROOT).with(WorkspaceProfileState(
            kind = WorkspaceProfileKind.PYTHON,
            status = WorkspaceProfileStatus.READY,
            version = "Python 3.12.3",
            executablePath = "/usr/bin/python3",
        ))
        val previousBytes = Json.encodeToString(WorkspaceProfilesSnapshot.serializer(), previous) + "\n"
        File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).writeText(previousBytes)
        expectThreadDeath(RootfsFaultPoint.AFTER_PROFILE_INVALIDATION) {
            fixture.installer.install(ROOT, serve("profile-cold-recovery.tar.gz", validArchive()))
        }
        // A fresh app may open profiles before its asynchronous integrity scan.
        val restartedManager = WorkspaceManager(fixture.base, shellRunner = HostShellRunner())

        val snapshot = WorkspaceProfileService(restartedManager).states(ROOT)

        assertEquals(WorkspaceProfileStatus.READY, snapshot.python.status)
        assertEquals("Python 3.12.3", snapshot.python.version)
        assertEquals("old shell\n", File(restartedManager.linuxDir(ROOT), "bin/sh").readText())
        assertEquals(OLD_GENERATION, File(restartedManager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE).readText())
        assertEquals(previousBytes, File(restartedManager.workspaceDir(ROOT), PROFILES_FILE).readText())
        assertFalse(installerPath(fixture, "operation.json").exists())
    }

    @Test
    fun `command and terminal admission perform cold recovery before using rootfs`() {
        listOf("command", "terminal").forEach { access ->
            val fixture = workspaceFixture(fault = { point ->
                if (point == RootfsFaultPoint.AFTER_CANDIDATE_MOVE) throw ThreadDeath()
            })
            expectThreadDeath(RootfsFaultPoint.AFTER_CANDIDATE_MOVE) {
                fixture.installer.install(ROOT, serve("$access-cold-recovery.tar.gz", validArchive()))
            }
            val restartedManager = WorkspaceManager(fixture.base, shellRunner = HostShellRunner())

            if (access == "command") {
                val result = restartedManager.executeCommand(ROOT, "printf 'NEXT_OK\\n'")
                assertEquals(0, result.exitCode)
                assertEquals("NEXT_OK\n", result.stdout)
            } else {
                restartedManager.acquireTerminalLease(ROOT).use { }
            }

            fixture.assertPreviousEnvironment()
            assertFalse(installerPath(fixture, "operation.json").exists())
        }
    }

    @Test
    fun `unconfirmed cold recovery blocks commands and profile metadata changes`() {
        val fixture = workspaceFixture()
        val operation = installerPath(fixture, "operation.json")
        checkNotNull(operation.parentFile).mkdirs()
        operation.writeText("unreadable recovery journal")
        val previousBytes = File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).readText()
        val effects = File(fixture.manager.filesDir(ROOT), "must-not-run")

        val commandFailure = runCatching {
            fixture.manager.executeCommand(ROOT, "printf 'executed' > must-not-run")
        }.exceptionOrNull()
        val profileFailure = runCatching { WorkspaceProfileService(fixture.manager).states(ROOT) }.exceptionOrNull()

        assertTrue(commandFailure is RootfsInstallException)
        assertEquals(RootfsEnvironmentState.ROLLBACK_UNCONFIRMED, (commandFailure as RootfsInstallException).environmentState)
        assertTrue(profileFailure is RootfsInstallException)
        assertFalse(effects.exists())
        assertEquals(previousBytes, File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).readText())
        assertEquals("unreadable recovery journal", operation.readText())
        fixture.assertPreviousEnvironment()
    }

    @Test
    fun `process death before commit recovers previous environment locally and idempotently`() {
        val faultPoints = listOf(
            RootfsFaultPoint.AFTER_DOWNLOAD,
            RootfsFaultPoint.AFTER_EXTRACT,
            RootfsFaultPoint.BEFORE_PATCH,
            RootfsFaultPoint.AFTER_PATCH,
            RootfsFaultPoint.BEFORE_CANDIDATE_PROBE,
            RootfsFaultPoint.AFTER_CANDIDATE_PROBE,
            RootfsFaultPoint.AFTER_CANDIDATE_READY_JOURNAL,
            RootfsFaultPoint.AFTER_SWITCH_JOURNAL,
            RootfsFaultPoint.AFTER_OLD_ROOTFS_MOVE,
            RootfsFaultPoint.AFTER_OLD_ROOTFS_MOVED_JOURNAL,
            RootfsFaultPoint.AFTER_CANDIDATE_MOVE,
            RootfsFaultPoint.AFTER_CANDIDATE_MOVED_JOURNAL,
            RootfsFaultPoint.AFTER_GENERATION_WRITE,
            RootfsFaultPoint.AFTER_GENERATION_JOURNAL,
            RootfsFaultPoint.AFTER_PROFILE_INVALIDATION,
            RootfsFaultPoint.AFTER_PROFILES_JOURNAL,
            RootfsFaultPoint.BEFORE_INSTALLED_JOURNAL,
        )
        faultPoints.forEach { point ->
            val fixture = workspaceFixture(
                fault = { current -> if (current == point) throw ThreadDeath() },
            )
            val url = serve("death-before-${point.name}.tar.gz", validArchive())

            expectThreadDeath(point) { fixture.installer.install(ROOT, url) }

            assertTrue("Journal must survive $point", installerPath(fixture, "operation.json").isFile)
            val recovered = RootfsInstaller(fixture.manager).recover(ROOT)
            assertEquals("Cold recovery at $point", RootfsRecoveryStatus.ROLLED_BACK, recovered.status)
            assertEquals(OLD_GENERATION, recovered.generation)
            fixture.assertPreviousEnvironment()
            assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
            assertFalse(installerPath(fixture, RootfsReplacement.STAGING_DIRECTORY).exists())
            assertFalse(installerPath(fixture, "operation.json").exists())

            val repeated = RootfsInstaller(fixture.manager).recover(ROOT)
            assertEquals("Repeated recovery at $point", RootfsRecoveryStatus.NO_OPERATION, repeated.status)
            fixture.assertPreviousEnvironment()
        }
    }

    @Test
    fun `process death after commit keeps new environment and retries only local cleanup`() {
        val faultPoints = listOf(
            RootfsFaultPoint.AFTER_INSTALLED_JOURNAL,
            RootfsFaultPoint.BEFORE_BACKUP_CLEANUP,
            RootfsFaultPoint.AFTER_BACKUP_CLEANUP,
        )
        faultPoints.forEach { point ->
            val fixture = workspaceFixture(
                fault = { current -> if (current == point) throw ThreadDeath() },
            )
            val url = serve("death-after-${point.name}.tar.gz", validArchive())

            expectThreadDeath(point) { fixture.installer.install(ROOT, url) }

            val committedGeneration = fixture.installer.generation(ROOT)
            assertNotNull(committedGeneration)
            assertNotEquals(OLD_GENERATION, committedGeneration)
            assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
            assertFalse(File(fixture.manager.workspaceDir(ROOT), PROFILES_FILE).exists())
            val recovered = RootfsInstaller(fixture.manager).recover(ROOT)
            assertEquals("Cold recovery at $point", RootfsRecoveryStatus.COMMITTED, recovered.status)
            assertEquals(committedGeneration, recovered.generation)
            assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
            assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
            assertFalse(installerPath(fixture, "operation.json").exists())

            val repeated = RootfsInstaller(fixture.manager).recover(ROOT)
            assertEquals("Repeated recovery at $point", RootfsRecoveryStatus.NO_OPERATION, repeated.status)
            assertEquals(committedGeneration, repeated.generation)
            assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        }
    }

    @Test
    fun `interruption before old rootfs move preserves previous environment`() {
        val fixture = workspaceFixture(
            fault = { point ->
                if (point == RootfsFaultPoint.AFTER_SWITCH_JOURNAL) Thread.currentThread().interrupt()
            },
        )
        try {
            val failure = installFailure(fixture, serve("cancel-before-move.tar.gz", validArchive()))

            assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
            fixture.assertPreviousEnvironment()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interruption after old rootfs move restores previous environment and metadata`() {
        val fixture = workspaceFixture(
            fault = { point ->
                if (point == RootfsFaultPoint.AFTER_OLD_ROOTFS_MOVE) Thread.currentThread().interrupt()
            },
        )
        try {
            val failure = installFailure(fixture, serve("cancel-after-move.tar.gz", validArchive()))

            assertEquals(RootfsEnvironmentState.RESTORED, failure.environmentState)
            fixture.assertPreviousEnvironment()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interruption during post commit cleanup returns success with warning and recovers locally`() {
        val fixture = workspaceFixture(
            fault = { point ->
                if (point == RootfsFaultPoint.BEFORE_BACKUP_CLEANUP) {
                    throw InterruptedException("injected cleanup cancellation")
                }
            },
        )

        val result = fixture.installer.install(ROOT, serve("cancel-cleanup.tar.gz", validArchive()))

        assertNotNull(result.cleanupWarning)
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
        val recovered = RootfsInstaller(fixture.manager).recover(ROOT)
        assertEquals(RootfsRecoveryStatus.COMMITTED, recovered.status)
        assertEquals(result.generation, recovered.generation)
        assertFalse(installerPath(fixture, RootfsReplacement.BACKUP_DIRECTORY).exists())
    }

    @Test
    fun `concurrent replacement of same workspace is rejected before download or switch`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstResult = AtomicReference<RootfsInstallResult?>()
        val firstError = AtomicReference<Throwable?>()
        val fixture = workspaceFixture(
            fault = { point ->
                if (point == RootfsFaultPoint.AFTER_CANDIDATE_READY_JOURNAL) {
                    entered.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) throw IOException("test release timed out")
                }
            },
        )
        val first = Thread {
            try {
                firstResult.set(
                    fixture.installer.install(ROOT, serve("concurrent-first.tar.gz", validArchive())),
                )
            } catch (error: Throwable) {
                firstError.set(error)
            }
        }.apply { start() }
        try {
            assertTrue("First installation did not reach the switch gate", entered.await(10, TimeUnit.SECONDS))

            val secondFailure = runCatching {
                RootfsInstaller(fixture.manager).install(ROOT, "http://127.0.0.1:1/must-not-download.tar.gz")
            }.exceptionOrNull()

            assertNotNull(secondFailure)
            assertTrue("Expected RootfsInstallException, got $secondFailure", secondFailure is RootfsInstallException)
            assertTrue(
                "The second operation must report busy, got ${secondFailure?.message}",
                secondFailure?.message.orEmpty().contains("Замена Rootfs отклонена"),
            )
            assertEquals(
                RootfsEnvironmentState.PRESERVED,
                (secondFailure as RootfsInstallException).environmentState,
            )
            fixture.assertPreviousEnvironment()
        } finally {
            release.countDown()
        }
        first.join(10_000)
        assertFalse("First installation did not finish", first.isAlive)
        assertNull(firstError.get())
        assertNotNull(firstResult.get())
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
    }

    @Test
    fun `replacement does not block another workspace`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstResult = AtomicReference<RootfsInstallResult?>()
        val firstError = AtomicReference<Throwable?>()
        val fixture = workspaceFixture(
            fault = { point ->
                if (point == RootfsFaultPoint.AFTER_CANDIDATE_READY_JOURNAL) {
                    entered.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) throw IOException("test release timed out")
                }
            },
        )
        preparePreviousEnvironment(fixture.manager, OTHER_ROOT)
        val first = Thread {
            try {
                firstResult.set(
                    fixture.installer.install(ROOT, serve("independent-first.tar.gz", validArchive())),
                )
            } catch (error: Throwable) {
                firstError.set(error)
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            val secondResult = RootfsInstaller(fixture.manager).install(
                OTHER_ROOT,
                serve("independent-second.tar.gz", validArchive()),
            )

            assertNotNull(secondResult.generation)
            assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(OTHER_ROOT), "bin/sh").readText())
            assertEquals("old shell\n", File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
            assertEquals(PROJECT_CONTENT, File(fixture.manager.filesDir(OTHER_ROOT), "project/.venv/state.txt").readText())
        } finally {
            release.countDown()
        }
        first.join(10_000)
        assertFalse(first.isAlive)
        assertNull(firstError.get())
        assertNotNull(firstResult.get())
        assertEquals(NEW_SHELL, File(fixture.manager.linuxDir(ROOT), "bin/sh").readText())
    }

    @Test
    fun `replacement is rejected while foreground command is active`() {
        val commandStarted = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val commandError = AtomicReference<Throwable?>()
        val delegate = HostShellRunner()
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                if (context.command != "hold foreground") return delegate.execute(context)
                commandStarted.countDown()
                if (!releaseCommand.await(10, TimeUnit.SECONDS)) {
                    return WorkspaceCommandResult(exitCode = 1, stdout = "", stderr = "test timeout")
                }
                return WorkspaceCommandResult(exitCode = 0, stdout = "", stderr = "")
            }
        }
        val fixture = workspaceFixture(shellRunner = runner)
        val command = Thread {
            try {
                fixture.manager.executeCommand(ROOT, "hold foreground")
            } catch (error: Throwable) {
                commandError.set(error)
            }
        }.apply { start() }
        try {
            assertTrue(commandStarted.await(10, TimeUnit.SECONDS))

            val failure = installFailure(fixture, "http://127.0.0.1:1/must-not-download.tar.gz")

            assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
            assertTrue(failure.message.orEmpty().contains("выполняется команда"))
            fixture.assertPreviousEnvironment()
        } finally {
            releaseCommand.countDown()
        }
        command.join(10_000)
        assertFalse(command.isAlive)
        assertNull(commandError.get())
    }

    @Test
    fun `replacement is rejected while profile operation is active`() {
        val profileStarted = CountDownLatch(1)
        val releaseProfile = CountDownLatch(1)
        val profileError = AtomicReference<Throwable?>()
        val fixture = workspaceFixture()
        val profile = Thread {
            try {
                fixture.manager.withRootfsActivity(ROOT, RootfsActivityKind.PROFILE) {
                    profileStarted.countDown()
                    if (!releaseProfile.await(10, TimeUnit.SECONDS)) throw IOException("test release timed out")
                }
            } catch (error: Throwable) {
                profileError.set(error)
            }
        }.apply { start() }
        try {
            assertTrue(profileStarted.await(10, TimeUnit.SECONDS))

            val failure = installFailure(fixture, "http://127.0.0.1:1/must-not-download.tar.gz")

            assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
            assertTrue(failure.message.orEmpty().contains("операция профиля Python/Node"))
            fixture.assertPreviousEnvironment()
        } finally {
            releaseProfile.countDown()
        }
        profile.join(10_000)
        assertFalse(profile.isAlive)
        assertNull(profileError.get())
    }

    @Test
    fun `replacement is rejected until terminal lease is closed`() {
        val fixture = workspaceFixture()
        val lease = fixture.manager.acquireTerminalLease(ROOT)
        try {
            val failure = installFailure(fixture, "http://127.0.0.1:1/must-not-download.tar.gz")

            assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
            assertTrue(failure.message.orEmpty().contains("открыт терминал"))
            fixture.assertPreviousEnvironment()
        } finally {
            lease.close()
        }
    }

    @Test
    fun `replacement is rejected while managed background process is running`() {
        val fixture = workspaceFixture()
        val process = fixture.manager.startBackgroundCommand(ROOT, "sleep 30")
        try {
            assertTrue(fixture.manager.backgroundOutput(ROOT, process.id)?.running == true)

            val failure = installFailure(fixture, "http://127.0.0.1:1/must-not-download.tar.gz")

            assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
            assertTrue(failure.message.orEmpty().contains("выполняется фоновый процесс"))
            fixture.assertPreviousEnvironment()
        } finally {
            if (fixture.manager.backgroundOutput(ROOT, process.id)?.running == true) {
                fixture.manager.stopBackgroundProcess(ROOT, process.id)
            }
        }
    }

    @Test
    fun `rootfs file export prevents replacement until its stream is closed`() {
        val fixture = workspaceFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exportFailure = AtomicReference<Throwable?>()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val export = Thread {
            try {
                fixture.manager.exportFile(ROOT, "old-environment.txt", WorkspaceStorageArea.LINUX, output)
            } catch (error: Throwable) {
                exportFailure.set(error)
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            val failure = installFailure(fixture, serve("export-race.tar.gz", validArchive()))

            assertEquals(RootfsEnvironmentState.PRESERVED, failure.environmentState)
            assertTrue(failure.cause is RootfsBusyException)
            fixture.assertPreviousEnvironment()
        } finally {
            release.countDown()
            export.join(10_000)
        }
        assertFalse(export.isAlive)
        assertNull(exportFailure.get())
    }

    private fun expectThreadDeath(point: RootfsFaultPoint, block: () -> Unit) {
        try {
            block()
        } catch (_: ThreadDeath) {
            return
        }
        throw AssertionError("Expected ThreadDeath at $point")
    }

    private fun installFailure(fixture: Fixture, url: String): RootfsInstallException {
        val failure = runCatching { fixture.installer.install(ROOT, url) }.exceptionOrNull()
        assertNotNull("Installation must fail", failure)
        assertTrue("Expected RootfsInstallException, got $failure", failure is RootfsInstallException)
        return failure as RootfsInstallException
    }

    private fun installerPath(fixture: Fixture, child: String): File =
        File(File(fixture.manager.workspaceDir(ROOT), RootfsReplacement.INSTALLER_DIRECTORY), child)

    private fun validArchive(): ByteArray = tarGz(
        TarEntry("bin/", type = '5'),
        TarEntry("bin/sh", content = NEW_SHELL.toByteArray(), mode = 493),
        TarEntry("usr/bin/", type = '5'),
        TarEntry("usr/bin/env", content = "#!/bin/sh\n".toByteArray(), mode = 493),
        TarEntry("etc/", type = '5'),
        TarEntry("etc/os-release", content = "ID=debian\n".toByteArray()),
    )

    private fun workspaceFixture(
        shellRunner: WorkspaceShellRunner = HostShellRunner(),
        io: RootfsTransactionIO = RootfsTransactionIO(),
        fault: (RootfsFaultPoint) -> Unit = {},
    ): Fixture {
        val base = tmp.newFolder("workspaces-${System.nanoTime()}")
        val manager = WorkspaceManager(base, shellRunner = shellRunner)
        preparePreviousEnvironment(manager, ROOT)
        return Fixture(base, manager, RootfsInstaller(manager, RootfsPatcher(), io, fault))
    }

    private fun preparePreviousEnvironment(manager: WorkspaceManager, root: String) {
        manager.ensureWorkspace(root)
        File(manager.linuxDir(root), "bin").mkdirs()
        File(manager.linuxDir(root), "bin/sh").writeText("old shell\n")
        File(manager.linuxDir(root), "old-environment.txt").writeText(OLD_ROOTFS_CONTENT)
        File(manager.workspaceDir(root), WorkspaceManager.ROOTFS_GENERATION_FILE).writeText(OLD_GENERATION)
        File(manager.workspaceDir(root), PROFILES_FILE).writeText(OLD_PROFILES)
        File(manager.workspaceDir(root), "$PROFILES_FILE.tmp").writeText(OLD_PROFILES_TEMPORARY)
        File(manager.filesDir(root), "project/.venv").mkdirs()
        File(manager.filesDir(root), "project/.venv/state.txt").writeText(PROJECT_CONTENT)
        File(manager.filesDir(root), "project/source.txt").writeText("unchanged source\n")
    }

    private inner class Fixture(
        val base: File,
        val manager: WorkspaceManager,
        val installer: RootfsInstaller,
    ) {
        fun assertPreviousEnvironment() {
            assertTrue(manager.hasRootfs(ROOT))
            assertEquals(OLD_ROOTFS_CONTENT, File(manager.linuxDir(ROOT), "old-environment.txt").readText())
            assertEquals("old shell\n", File(manager.linuxDir(ROOT), "bin/sh").readText())
            assertEquals(
                OLD_GENERATION,
                File(manager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE).readText(),
            )
            assertEquals(OLD_PROFILES, File(manager.workspaceDir(ROOT), PROFILES_FILE).readText())
            assertEquals(OLD_PROFILES_TEMPORARY, File(manager.workspaceDir(ROOT), "$PROFILES_FILE.tmp").readText())
            assertEquals(PROJECT_CONTENT, File(manager.filesDir(ROOT), "project/.venv/state.txt").readText())
            assertEquals("unchanged source\n", File(manager.filesDir(ROOT), "project/source.txt").readText())
        }
    }

    private fun serve(name: String, bytes: ByteArray): String {
        val path = "/$name"
        server!!.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return "http://127.0.0.1:${server!!.address.port}$path"
    }

    private fun tarGz(vararg entries: TarEntry): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { entry ->
                gzip.write(tarHeader(entry))
                gzip.write(entry.content)
                gzip.write(ByteArray((TAR_BLOCK - entry.content.size % TAR_BLOCK) % TAR_BLOCK))
            }
            gzip.write(ByteArray(TAR_BLOCK * 2))
        }
        return output.toByteArray()
    }

    private fun tarHeader(entry: TarEntry): ByteArray {
        val header = ByteArray(TAR_BLOCK)
        header.writeString(0, 100, entry.name)
        header.writeOctal(100, 8, entry.mode.toLong())
        header.writeOctal(108, 8, 0)
        header.writeOctal(116, 8, 0)
        header.writeOctal(124, 12, entry.content.size.toLong())
        header.writeOctal(136, 12, 0)
        header[156] = entry.type.code.toByte()
        header.writeString(157, 100, entry.linkName)
        return header
    }

    private fun ByteArray.writeString(offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        bytes.copyInto(this, offset, 0, minOf(bytes.size, length))
    }

    private fun ByteArray.writeOctal(offset: Int, length: Int, value: Long) {
        writeString(offset, length, value.toString(8).padStart(length - 1, '0'))
    }

    private data class TarEntry(
        val name: String,
        val content: ByteArray = byteArrayOf(),
        val mode: Int = 420,
        val type: Char = '0',
        val linkName: String = "",
    )

    companion object {
        private const val ROOT = "workspace-one"
        private const val OTHER_ROOT = "workspace-two"
        private const val TAR_BLOCK = 512
        private const val PROFILES_FILE = ".workspace-profiles.json"
        private const val OLD_ROOTFS_CONTENT = "previous environment\n"
        private const val NEW_SHELL = "new shell\n"
        private const val OLD_GENERATION = "generation-before-replacement"
        private const val OLD_PROFILES = "{\"rootfsStamp\":\"previous\",\"python\":{\"kind\":\"PYTHON\",\"status\":\"READY\"}}"
        private const val OLD_PROFILES_TEMPORARY = "previous atomic profile state\n"
        private const val PROJECT_CONTENT = "project venv must survive\n"
    }
}
