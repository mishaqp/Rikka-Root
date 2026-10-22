package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WebDavBackupHrefPart3Test {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(): Context { val root = temporary.newFolder(); return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
    } }
    private fun config(origin: String) = WebDavConfig(url = origin, path = "dav/backups", username = "DUMMY_USER", password = "DUMMY_PASS", items = emptyList())
    private fun item(href: String, label: String = "backup_human_label.zip") = WebDavBackupItem(href, label, 12, Instant.EPOCH)
    private fun sync(context: Context, server: BackupNetworkFixture): WebDavSync {
        val store = mockk<SettingsStore>(); every { store.settingsFlowRaw } returns flowOf(Settings())
        return WebDavSync(BackupManager(context, mockk(), store, JsonInstant, mockk()), context, server.client)
    }
    private fun archive(): ByteArray = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("settings.json")); zip.write(JsonInstant.encodeToString(Settings()).encodeToByteArray()); zip.closeEntry()
    } }.toByteArray()

    @Test(timeout = 20000) fun deleteUsesListedHrefAndNeverDeletesAnotherObjectNamedByDisplayLabel() = runBlocking {
        BackupNetworkFixture { _, _ -> 200 to "ok".encodeToByteArray() }.use { server ->
            sync(context(), server).deleteBackupFile(config(server.origin), item("/dav/backups/backup_actual.zip"))
            assertEquals(listOf("/dav/backups/backup_actual.zip"), server.requests.map { it.rawPath })
            assertEquals(listOf("DELETE"), server.requests.map { it.method })
            assertTrue(server.requests.single().authorization?.startsWith("Basic ") == true)
        }
    }

    @Test(timeout = 20000) fun restoreUsesEncodedHrefAndRandomOwnedCacheName() = runBlocking {
        val context = context()
        BackupNetworkFixture { _, _ -> 200 to archive() }.use { server ->
            sync(context, server).restore(config(server.origin), item(server.origin + "/dav/backups/backup_odd%20name%23x.zip", "backup_other.zip"))
            assertEquals("/dav/backups/backup_odd%20name%23x.zip", server.requests.single().rawPath)
            assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").isFile)
            assertTrue("download cache must be removed after staging", context.cacheDir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test(timeout = 20000) fun differentOriginHrefIsRejectedBeforeAnyCredentialRequest() = runBlocking {
        BackupNetworkFixture { _, _ -> 200 to "ok".encodeToByteArray() }.use { trusted ->
            BackupNetworkFixture { _, _ -> 200 to "evil".encodeToByteArray() }.use { foreign ->
                for (restore in listOf(false, true)) {
                    val error = runCatching {
                        val service = sync(context(), trusted)
                        val selected = item(foreign.origin + "/dav/backups/backup_a.zip")
                        if (restore) service.restore(config(trusted.origin), selected) else service.deleteBackupFile(config(trusted.origin), selected)
                    }.exceptionOrNull()
                    assertTrue("Neither origin may be contacted for an invalid href", trusted.requests.isEmpty() && foreign.requests.isEmpty())
                    assertNotNull(error)
                }
            }
        }
    }

    @Test(timeout = 20000) fun rootEscapeAndEncodedDotSegmentsAreRejectedBeforeRequest() = runBlocking {
        for (href in listOf("/dav/outside/backup_a.zip", "/dav/backups/../backup_a.zip", "/dav/backups/%2e%2e/backup_a.zip", "/dav/backups2/backup_a.zip")) {
            BackupNetworkFixture { _, _ -> 200 to "ok".encodeToByteArray() }.use { server ->
                val error = runCatching { sync(context(), server).deleteBackupFile(config(server.origin), item(href)) }.exceptionOrNull()
                assertTrue("Rejected href must not start authenticated IO: $href", server.requests.isEmpty())
                assertNotNull(error)
            }
        }
    }

    @Test(timeout = 20000) fun ordinarySameOriginBackupPreservesCredentialsAndExactPath() = runBlocking {
        BackupNetworkFixture { _, _ -> 200 to "ok".encodeToByteArray() }.use { server ->
            for (href in listOf("/dav/backups/backup_a.zip", server.origin + "/dav/backups/backup_a.zip", "backup_a.zip")) {
                sync(context(), server).deleteBackupFile(config(server.origin), item(href, "backup_a.zip"))
            }
            assertEquals(listOf("/dav/backups/backup_a.zip", "/dav/backups/backup_a.zip", "/dav/backups/backup_a.zip"), server.requests.map { it.rawPath })
            assertTrue(server.requests.all { it.authorization != null })
        }
    }
}
