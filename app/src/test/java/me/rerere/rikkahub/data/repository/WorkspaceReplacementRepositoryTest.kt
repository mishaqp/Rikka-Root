package me.rerere.rikkahub.data.repository

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RootfsEnvironmentState
import me.rerere.workspace.RootfsBusyException
import me.rerere.workspace.RootfsInstallException
import me.rerere.workspace.RootfsInstallResult
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsRecoveryResult
import me.rerere.workspace.RootfsRecoveryStatus
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellRunner
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.miniapps.MiniAppStore
import me.rerere.rikkahub.miniapps.publishMiniAppTool
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class WorkspaceReplacementRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `rejected concurrent install cannot restore a stale installing database status`() = runTest {
        val enteredInstall = CompletableDeferred<Unit>()
        val releaseInstall = CountDownLatch(1)
        val staleRestoreEntered = CompletableDeferred<Unit>()
        val releaseStaleRestore = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val installingWrites = AtomicInteger()
        val dao = MemoryWorkspaceDAO()
        dao.beforeShellUpdate = { status ->
            if (status == WorkspaceShellStatus.INSTALLING.name && installingWrites.incrementAndGet() == 3) {
                staleRestoreEntered.complete(Unit)
                releaseStaleRestore.await()
            }
        }
        val attempts = AtomicInteger()
        val installer = mockk<RootfsInstaller>()
        every { installer.generation(ROOT) } returns "old-generation"
        // Only blocking installation is replaced; repository concurrency and all DAO writes are real.
        every { installer.install(ROOT, any(), false, any()) } answers {
            if (attempts.incrementAndGet() == 1) {
                enteredInstall.complete(Unit)
                check(releaseInstall.await(10, TimeUnit.SECONDS))
                RootfsInstallResult("new-generation")
            } else {
                throw RootfsInstallException("busy", RootfsEnvironmentState.PRESERVED)
            }
        }
        val repository = WorkspaceRepository(dao, WorkspaceManager(temp.root), installer, mockk())
        val first = async { repository.installRootfs(ROOT, "https://example.invalid/rootfs.tar.gz") }
        var secondFailure: Throwable? = null
        try {
            enteredInstall.await()
            val second = launch {
                try {
                    repository.installRootfs(ROOT, "https://example.invalid/rootfs.tar.gz")
                } catch (error: Exception) {
                    secondFailure = error
                } finally {
                    secondFinished.complete(Unit)
                }
            }
            // The old implementation parks in stale restoration; an admission rejection finishes.
            select<Unit> {
                staleRestoreEntered.onAwait { }
                secondFinished.onAwait { }
            }
            releaseInstall.countDown()
            first.await()
            releaseStaleRestore.complete(Unit)
            second.join()
        } finally {
            releaseInstall.countDown()
            releaseStaleRestore.complete(Unit)
        }

        assertNotNull(secondFailure)
        assertEquals("Rejected work must not overwrite the committed status", WorkspaceShellStatus.READY.name, dao.getById(ROOT)?.shellStatus)
    }

    @Test
    fun `installation reads generation outside the caller thread`() = runTest {
        val caller = Thread.currentThread()
        val installer = mockk<RootfsInstaller>()
        every { installer.generation(ROOT) } answers {
            assertNotSame("The generation marker is filesystem IO", caller, Thread.currentThread())
            "old-generation"
        }
        every { installer.install(ROOT, any(), false, any()) } returns RootfsInstallResult("new-generation")
        val dao = MemoryWorkspaceDAO()
        val repository = WorkspaceRepository(dao, WorkspaceManager(temp.root), installer, mockk())

        val result = repository.installRootfs(ROOT, "https://example.invalid/rootfs.tar.gz")

        assertEquals("new-generation", result?.generation)
        assertEquals(WorkspaceShellStatus.READY.name, dao.getById(ROOT)?.shellStatus)
    }

    @Test
    fun `candidate probe uses the workspace shell compatibility mode`() = runTest {
        val modes = mutableListOf<Boolean>()
        val runner = object : WorkspaceShellRunner {
            override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                modes += context.shellCompatibilityMode
                return if (context.shellCompatibilityMode) {
                    WorkspaceCommandResult(0, "RIKKA_ROOTFS_CANDIDATE_OK\n", "")
                } else {
                    WorkspaceCommandResult(1, "", "this device requires PROOT_NO_SECCOMP")
                }
            }
        }
        val manager = WorkspaceManager(temp.root, shellRunner = runner)
        manager.ensureWorkspace(ROOT)
        File(manager.filesDir(ROOT), "project.txt").writeText("preserved project")
        val dao = MemoryWorkspaceDAO(compatibility = true)
        val repository = WorkspaceRepository(dao, manager, RootfsInstaller(manager), mockk())
        val archive = minimalRootfsArchive()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rootfs.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
        try {
            val result = repository.installRootfs(ROOT, "http://127.0.0.1:${server.address.port}/rootfs.tar.gz")

            assertNotNull(result)
            assertEquals(listOf(true), modes)
            assertEquals(WorkspaceShellStatus.READY.name, dao.getById(ROOT)?.shellStatus)
            assertEquals("preserved project", File(manager.filesDir(ROOT), "project.txt").readText())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cold integrity reconciles installing after the transaction journal is already gone`() = runTest {
        val manager = WorkspaceManager(temp.root)
        manager.ensureWorkspace(ROOT)
        File(manager.linuxDir(ROOT), "bin").mkdirs()
        File(manager.linuxDir(ROOT), "bin/sh").writeText("installed shell")
        File(manager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE).writeText("committed-generation")
        val dao = MemoryWorkspaceDAO(initialStatus = WorkspaceShellStatus.INSTALLING)
        val repository = WorkspaceRepository(dao, manager, RootfsInstaller(manager), mockk())
        // The journal can have been removed by successful cleanup or earlier profile admission.
        repository.profileStates(ROOT)

        repository.checkIntegrity()

        assertEquals(WorkspaceShellStatus.READY.name, dao.getById(ROOT)?.shellStatus)
        assertEquals("committed-generation", File(manager.workspaceDir(ROOT), WorkspaceManager.ROOTFS_GENERATION_FILE).readText())
    }

    @Test
    fun `cold integrity disables interrupted first installation without a usable rootfs`() = runTest {
        val manager = WorkspaceManager(temp.root)
        manager.ensureWorkspace(ROOT)
        val dao = MemoryWorkspaceDAO(initialStatus = WorkspaceShellStatus.INSTALLING)
        val repository = WorkspaceRepository(dao, manager, RootfsInstaller(manager), mockk())

        repository.checkIntegrity()

        assertEquals(WorkspaceShellStatus.DISABLED.name, dao.getById(ROOT)?.shellStatus)
    }

    @Test
    fun `integrity scan cannot clear the status of an owned live installation`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val manager = WorkspaceManager(temp.root)
        manager.ensureWorkspace(ROOT)
        File(manager.linuxDir(ROOT), "bin").mkdirs()
        File(manager.linuxDir(ROOT), "bin/sh").writeText("old usable shell")
        val dao = MemoryWorkspaceDAO()
        val installer = mockk<RootfsInstaller>()
        every { installer.generation(ROOT) } returns "old-generation"
        every { installer.recover(ROOT) } returns RootfsRecoveryResult(RootfsRecoveryStatus.NO_OPERATION, true)
        every { installer.install(ROOT, any(), false, any()) } answers {
            started.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            RootfsInstallResult("new-generation")
        }
        val repository = WorkspaceRepository(dao, manager, installer, mockk())
        val installing = async { repository.installRootfs(ROOT, "https://example.invalid/rootfs.tar.gz") }
        try {
            started.await()

            repository.checkIntegrity()

            assertEquals(WorkspaceShellStatus.INSTALLING.name, dao.getById(ROOT)?.shellStatus)
        } finally {
            release.countDown()
        }
        installing.await()
        assertEquals(WorkspaceShellStatus.READY.name, dao.getById(ROOT)?.shellStatus)
    }

    @Test
    fun `mini app source remains leased until publication has copied its rootfs files`() = runTest {
        val copying = CompletableDeferred<Unit>()
        val releaseCopy = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                copying.complete(Unit)
                worker.execute {
                    check(releaseCopy.await(10, TimeUnit.SECONDS))
                    block.run()
                }
            }
        }
        val manager = WorkspaceManager(temp.newFolder())
        manager.ensureWorkspace(ROOT)
        File(manager.linuxDir(ROOT), "bin").mkdirs()
        File(manager.linuxDir(ROOT), "bin/sh").writeText("old shell")
        val source = File(manager.linuxDir(ROOT), "opt/my-app").apply { mkdirs() }
        File(source, "moru-app.json").writeText("""{"id":"leased-rootfs","name":"Lease","entry":"index.html"}""")
        File(source, "index.html").writeText("<html>old index<script src='app.js'></script></html>")
        File(source, "app.js").writeText("old script")
        val store = MiniAppStore(temp.newFolder(), dispatcher)
        val repository = WorkspaceRepository(MemoryWorkspaceDAO(), manager, RootfsInstaller(manager), mockk())
        val tool = publishMiniAppTool(ROOT, repository, null, store = { store },
            check = { buildJsonObject { put("loaded", true) } }, needsApproval = { false })
        val published = async { tool.execute(buildJsonObject { put("path", "/opt/my-app") }) }
        try {
            copying.await()
            val failure = runCatching {
                RootfsInstaller(manager).install(ROOT, "http://127.0.0.1:1/unused.tar.gz")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("Publication must block replacement until its source copy finishes", RootfsBusyException::class.java, failure?.cause?.javaClass)
        } finally {
            releaseCopy.countDown()
            published.await()
            worker.shutdown()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
        val result = Json.parseToJsonElement((published.await().single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonPrimitive(true), result["ok"])
        val installed = checkNotNull(store.byId("leased-rootfs"))
        assertEquals("old script", File(installed.directory, "app/app.js").readText())
    }

    private fun minimalRootfsArchive(): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            for ((name, contents) in listOf("bin/sh" to "candidate shell\n", "usr/bin/env" to "candidate env\n", "etc/os-release" to "ID=debian\n")) {
                val bytes = contents.toByteArray()
                val header = ByteArray(512)
                name.toByteArray().copyInto(header)
                "0000755\u0000".toByteArray().copyInto(header, 100)
                bytes.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
                header[156] = '0'.code.toByte()
                header.fill(' '.code.toByte(), 148, 156)
                val checksum = header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 "
                checksum.toByteArray().copyInto(header, 148)
                gzip.write(header)
                gzip.write(bytes)
                gzip.write(ByteArray((512 - bytes.size % 512) % 512))
            }
            gzip.write(ByteArray(1024))
        }
        return output.toByteArray()
    }

    private class MemoryWorkspaceDAO(
        compatibility: Boolean = false,
        initialStatus: WorkspaceShellStatus = WorkspaceShellStatus.READY,
    ) : WorkspaceDAO {
        private val workspaces = MutableStateFlow(listOf(
            WorkspaceEntity(ROOT, ROOT, ROOT, initialStatus.name, 0, 0, shellCompatibilityMode = compatibility),
        ))
        var beforeShellUpdate: suspend (String) -> Unit = {}
        override fun listFlow(): Flow<List<WorkspaceEntity>> = workspaces
        override suspend fun getById(id: String) = workspaces.value.firstOrNull { it.id == id }
        override suspend fun getAll() = workspaces.value
        override suspend fun upsert(workspace: WorkspaceEntity) {
            workspaces.update { list -> list.filterNot { it.id == workspace.id } + workspace }
        }
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int {
            beforeShellUpdate(shellStatus)
            workspaces.update { list -> list.map { if (it.id == id) it.copy(shellStatus = shellStatus, updatedAt = updatedAt) else it } }
            return 1
        }
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int {
            workspaces.update { list -> list.map { if (it.id == id) it.copy(shellCompatibilityMode = enabled, updatedAt = updatedAt) else it } }
            return 1
        }
        override suspend fun deleteById(id: String): Int {
            workspaces.update { list -> list.filterNot { it.id == id } }
            return 1
        }
    }

    companion object {
        private const val ROOT = "replacement-workspace"
    }
}
