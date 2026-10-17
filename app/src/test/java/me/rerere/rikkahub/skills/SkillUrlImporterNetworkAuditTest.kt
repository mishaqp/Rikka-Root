package me.rerere.rikkahub.skills

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.files.SkillMetadata
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Exercises the real importer and OkHttp body consumption, without a network request. */
class SkillUrlImporterNetworkAuditTest {
    @Test fun unknownLengthBodyIsRejectedWithoutReadingTheWholeStream() = runBlocking {
        assertBoundedRead(-1)
    }

    @Test fun aMisleadingContentLengthCannotBypassTheStreamingLimit() = runBlocking {
        assertBoundedRead(16)
    }

    @Test fun aValidBodyAtTheByteLimitIsReadAndClosedBeforeSaving() = runBlocking {
        val prefix = "---\nname: network-limit\ndescription: bounded stream\n---\n"
        val markdown = prefix + "x".repeat(SkillUrlImporter.MAX_BODY_BYTES - prefix.length)
        val closed = AtomicBoolean()
        val saver = TempDirSkillSaver()
        val checkingSaver = object : SkillSaver {
            override fun saveSkill(name: String, content: String): SkillMetadata? {
                assertTrue("The response must already be closed when persistence starts", closed.get())
                return saver.saveSkill(name, content)
            }
        }
        val source = tracked(Buffer().writeUtf8(markdown), closed)
        val client = client(source)
        try {
            val result = SkillUrlImporter(checkingSaver, client).importFromUrl(URL)
            assertTrue("Expected a successful actual file install: $result", result is SkillUrlImporter.Result.Ok)
            assertTrue("Response source must close after decoding", closed.get())
            assertTrue(saver.tmpDir.resolve("network-limit/SKILL.md").readText().contains("bounded stream"))
        } finally {
            saver.tmpDir.deleteRecursively()
            client.shutdown()
        }
    }

    @Test fun unsuccessfulHttpResponseIsClosedWithoutReadingItsBody() = runBlocking {
        val closed = AtomicBoolean()
        val reads = AtomicLong()
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads.incrementAndGet()
                throw AssertionError("HTTP failure body must not be consumed")
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        val saver = TempDirSkillSaver()
        val client = client(source, status = 503)
        try {
            val result = SkillUrlImporter(saver, client).importFromUrl(URL)
            assertTrue(result is SkillUrlImporter.Result.Err)
            assertEquals("fetch_failed", (result as SkillUrlImporter.Result.Err).code)
            assertTrue("HTTP error response must close", closed.get())
            assertEquals(0, reads.get())
            assertTrue(saver.tmpDir.listFiles().orEmpty().isEmpty())
        } finally {
            saver.tmpDir.deleteRecursively()
            client.shutdown()
        }
    }

    @Test fun anIOExceptionWhileStreamingClosesTheBodyAndDoesNotInstallAPartialSkill() = runBlocking {
        val closed = AtomicBoolean()
        var reads = 0
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (reads++ > 0) throw IOException("fixture interrupted response")
                val prefix = "---\nname: partial\ndescription: incomplete\n---\n"
                sink.writeUtf8(prefix)
                return prefix.length.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        val saver = TempDirSkillSaver()
        val client = client(source)
        try {
            val result = SkillUrlImporter(saver, client).importFromUrl(URL)
            assertTrue(result is SkillUrlImporter.Result.Err)
            assertTrue("An exceptional body read must still close the source", closed.get())
            assertTrue("A truncated skill must never reach the saver", saver.tmpDir.listFiles().orEmpty().isEmpty())
        } finally {
            saver.tmpDir.deleteRecursively()
            client.shutdown()
        }
    }

    @Test fun theRealCallHasABoundedDeadlineEvenWhenTheInjectedClientHasNone() = runBlocking {
        val source = Buffer().writeUtf8("---\nname: deadline\ndescription: call timeout\n---\nbody")
        var actualTimeout = 0L
        val saver = TempDirSkillSaver()
        val client = client(source) { actualTimeout = it }
        try {
            val result = SkillUrlImporter(saver, client).importFromUrl(URL)
            assertTrue(result is SkillUrlImporter.Result.Ok)
            assertEquals(0, client.callTimeoutMillis)
            assertEquals(TimeUnit.SECONDS.toNanos(60), actualTimeout)
        } finally {
            saver.tmpDir.deleteRecursively()
            client.shutdown()
        }
    }

    private suspend fun assertBoundedRead(declaredLength: Long) {
        val bytesRead = AtomicLong()
        val closed = AtomicBoolean()
        val totalBytes = SkillUrlImporter.MAX_BODY_BYTES * 8L
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = minOf(byteCount, totalBytes - bytesRead.get())
                if (count == 0L) return -1
                sink.writeUtf8("x".repeat(count.toInt()))
                bytesRead.addAndGet(count)
                return count
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        val saver = TempDirSkillSaver()
        val client = client(source, declaredLength = declaredLength)
        try {
            val result = SkillUrlImporter(saver, client).importFromUrl(URL)
            assertTrue("Oversized stream must fail: $result", result is SkillUrlImporter.Result.Err)
            assertTrue("The source must close even on byte limit rejection", closed.get())
            assertTrue("Read must stop at cap plus one Okio segment, got ${bytesRead.get()}",
                bytesRead.get() <= SkillUrlImporter.MAX_BODY_BYTES + 8192L)
            assertTrue("Must not consume the entire oversized stream", bytesRead.get() < totalBytes)
            assertTrue("An oversized body must not install a file", saver.tmpDir.listFiles().orEmpty().isEmpty())
        } finally {
            saver.tmpDir.deleteRecursively()
            client.shutdown()
        }
    }

    private fun tracked(source: Source, closed: AtomicBoolean) = object : ForwardingSource(source) {
        override fun close() { closed.set(true); super.close() }
    }.buffer()

    private fun client(
        source: BufferedSource,
        status: Int = 200,
        declaredLength: Long = -1,
        observeDeadline: (Long) -> Unit = {},
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        observeDeadline(chain.call().timeout().timeoutNanos())
        response(chain.request(), source, status, declaredLength)
    }.build()

    private fun response(request: Request, source: BufferedSource, status: Int, length: Long): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(object : ResponseBody() {
                override fun contentType() = "text/markdown".toMediaType()
                override fun contentLength() = length
                override fun source() = source
            }).build()

    private fun OkHttpClient.shutdown() {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    // A literal avoids the importer's preflight InetAddress lookup; the interceptor prevents TCP.
    companion object { private const val URL = "https://93.184.216.34/SKILL.md" }
}
