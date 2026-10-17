package me.rerere.rikkahub.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** New suspected regression: completion handlers must not wait for blocking reads to end. */
class SkillUrlImporterCancellationAuditTest {
    @Test(timeout = 15_000) fun cancellingAnImportCancelsTheCallBeforeTheBlockedBodyIsReleased() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                enteredRead.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "fixture body release timed out" }
                throw IOException("fixture interrupted body")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { callCancelled.countDown() }
        }).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/markdown".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build()
        val saver = TempDirSkillSaver()
        val pending = async(Dispatchers.Default) {
            SkillUrlImporter(saver, client).importFromUrl("https://93.184.216.34/SKILL.md")
        }
        try {
            assertTrue("The importer must be consuming the body", enteredRead.await(5, TimeUnit.SECONDS))
            pending.cancel()
            assertTrue("Cancellation must cancel OkHttp while the body read is still blocked",
                callCancelled.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { pending.join() }
            assertEquals("The cancelled coroutine must finish without releasing the blocked reader", 1L, releaseRead.count)
        } finally {
            releaseRead.countDown()
            pending.cancel()
            try { pending.await(); fail("Expected coroutine cancellation") } catch (_: CancellationException) { }
            assertTrue("Cancellation cleanup must close the body", closed.await(5, TimeUnit.SECONDS))
            assertTrue("A cancelled download must not install a skill", saver.tmpDir.listFiles().orEmpty().isEmpty())
            saver.tmpDir.deleteRecursively()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
