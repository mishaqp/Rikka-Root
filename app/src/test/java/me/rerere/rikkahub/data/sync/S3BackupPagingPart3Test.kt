package me.rerere.rikkahub.data.sync

import android.app.Application
import io.mockk.mockk
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.sync.s3.S3Config
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class S3BackupPagingPart3Test {
    private fun page(keys: List<String>, token: String? = null) = ("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><KeyCount>${keys.size}</KeyCount><IsTruncated>${token != null}</IsTruncated>" +
        (token?.let { "<NextContinuationToken>$it</NextContinuationToken>" } ?: "") + keys.joinToString("") { "<Contents><Key>rikkahub_backups/$it</Key><Size>12</Size><LastModified>2026-10-18T00:00:00Z</LastModified></Contents>" } + "</ListBucketResult>").encodeToByteArray()
    private fun config(origin: String) = S3Config(endpoint = origin, bucket = "bucket", region = "test", accessKeyId = "DUMMY_ACCESS", secretAccessKey = "DUMMY_SECRET")
    private fun sync(server: BackupNetworkFixture) = S3Sync(mockk(), RuntimeEnvironment.getApplication(), server.client)

    @Test(timeout = 20000) fun newestArchiveAfterFirstThousandIsListedAndOpaqueTokenRoundTrips() = runBlocking {
        val old = (1..1000).map { "backup_20250101_${it.toString().padStart(6, '0')}.zip" }
        val token = "token+/= space"
        BackupNetworkFixture { _, index -> 200 to if (index == 1) page(old, token) else page(listOf("backup_20261018_999999.zip")) }.use { server ->
            val result = sync(server).listBackupFiles(config(server.origin))
            assertEquals(1001, result.size)
            assertTrue(result.toString().take(300), result.any { it.key.endsWith("backup_20261018_999999.zip") })
            assertEquals(2, server.requests.size)
            val query = server.requests[1].rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertEquals(token, query["continuation-token"])
            assertTrue(server.requests.all { it.authorization?.startsWith("AWS4-HMAC-SHA256 ") == true })
        }
    }

    @Test(timeout = 20000) fun repeatedContinuationTokenFailsWithoutInfiniteRequestsOrPartialSuccess() = runBlocking {
        BackupNetworkFixture { _, _ -> 200 to page(listOf("backup_old.zip"), "same-token") }.use { server ->
            val error = runCatching { withTimeout(5000) { sync(server).listBackupFiles(config(server.origin)) } }.exceptionOrNull()
            assertNotNull("A repeated token must fail rather than claim a complete list", error)
            assertFalse(error is TimeoutCancellationException)
            assertTrue(server.requests.size <= 3)
        }
    }

    @Test(timeout = 20000) fun laterPageErrorIsNotConvertedIntoSuccessfulFirstPage() = runBlocking {
        BackupNetworkFixture { _, index -> if (index == 1) 200 to page(listOf("backup_old.zip"), "next") else 503 to "unavailable".encodeToByteArray() }.use { server ->
            assertNotNull(runCatching { sync(server).listBackupFiles(config(server.origin)) }.exceptionOrNull())
            assertEquals(2, server.requests.size)
        }
    }

    @Test(timeout = 20000) fun completeOnePagePreservesEveryArchiveAndDoesNotFollowMissingToken() = runBlocking {
        BackupNetworkFixture { _, _ -> 200 to page(listOf("backup_one.zip", "backup_two.zip", "not-a-backup.txt")) }.use { server ->
            assertEquals(setOf("backup_one.zip", "backup_two.zip"), sync(server).listBackupFiles(config(server.origin)).map { it.displayName }.toSet())
            assertEquals(1, server.requests.size)
        }
    }

    @Test(timeout = 20000) fun parentCancellationMakesNoRequest() = runBlocking {
        BackupNetworkFixture { _, _ -> 200 to page(emptyList()) }.use { server ->
            val job = launch(start = CoroutineStart.LAZY) { sync(server).listBackupFiles(config(server.origin)) }
            job.cancel(); job.start(); job.join()
            assertTrue(job.isCancelled)
            assertTrue(server.requests.isEmpty())
        }
    }
}
