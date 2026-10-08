package me.rerere.rikkahub.reliability

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalFileAccess
import me.rerere.rikkahub.data.ai.tools.local.listZipContentsTool
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class BugReportBuilderTest {
    private class ReportContext(private val root: File) : ContextWrapper(null) {
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "me.rerere.rikkahub"
    }
    private class LogcatProcess(text: String) : Process() {
        private val source = ByteArrayInputStream(text.toByteArray())
        override fun getInputStream(): InputStream = source
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }
    private class StalledLogcatProcess(private val stallWhileReading: Boolean) : Process() {
        val started = CountDownLatch(1)
        val destroyed = AtomicBoolean(false)
        val inputClosed = AtomicBoolean(false)
        val outputClosed = AtomicBoolean(false)
        val errorClosed = AtomicBoolean(false)
        private val released = CountDownLatch(1)
        private fun awaitRelease() {
            started.countDown()
            // Fail-safe also lets this regression fail against the old unbounded capture.
            released.await(8, TimeUnit.SECONDS)
        }
        private val input = object : InputStream() {
            override fun read(): Int {
                if (stallWhileReading) awaitRelease()
                return -1
            }
            override fun close() { inputClosed.set(true) }
        }
        private val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed.set(true); super.close() }
        }
        private val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed.set(true); super.close() }
        }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = error
        override fun getOutputStream(): OutputStream = output
        override fun waitFor(): Int {
            if (!stallWhileReading) awaitRelease()
            return 0
        }
        override fun exitValue(): Int = if (destroyed.get()) 0 else throw IllegalThreadStateException()
        override fun destroy() { destroyed.set(true); released.countDown() }
        fun assertCleanedUp() {
            assertTrue("logcat process must be destroyed", destroyed.get())
            assertTrue("stdout must be closed", inputClosed.get())
            assertTrue("stdin must be closed", outputClosed.get())
            assertTrue("stderr must be closed", errorClosed.get())
        }
    }
    private fun entries(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }
    private fun builder(context: Context, logs: String = "ReportMarker: diagnostic line", errors: List<Throwable> = emptyList()) =
        BugReportBuilder(context) { errors }.apply { logcatProcess = { LogcatProcess(logs) } }
    private fun access(context: Context, workspace: File) = LocalFileAccess(context,
        workspaceCwd = "/workspace/project", resolveWorkspacePath = { path ->
            File(workspace, path.removePrefix("/workspace").trimStart('/'))
        })

    @Test fun logcatAndCompleteErrorsAreInZipWithSecretsRedacted() = runBlocking {
        val root = Files.createTempDirectory("report-logcat").toFile()
        try {
            val logs = "ReportMarker: network failure sk-synthetic-secret\nBearer synthetic.jwt.secret\npassword=synthetic-password"
            val error = IllegalStateException("connection failed password=synthetic-password", IllegalArgumentException("upstream details")).apply {
                stackTrace = arrayOf(StackTraceElement("example.CustomProvider", "readResponse", "CustomProvider.kt", 27))
                addSuppressed(IllegalArgumentException("suppressed details"))
            }
            val report = entries(builder(ReportContext(root), logs, listOf(error)).build())
            assertEquals(setOf("meta.txt", "logcat.txt", "errors.txt", "README.txt"), report.keys)
            assertTrue(report.getValue("logcat.txt").contains("ReportMarker: network failure"))
            val diagnostics = report.getValue("errors.txt")
            assertTrue(diagnostics.contains("java.lang.IllegalStateException"))
            assertTrue(diagnostics.contains("connection failed"))
            assertTrue(diagnostics.contains("example.CustomProvider.readResponse(CustomProvider.kt:27)"))
            assertTrue(diagnostics.contains("upstream details"))
            assertTrue(diagnostics.contains("suppressed details"))
            val text = report.values.joinToString("\n")
            for (secret in listOf("sk-synthetic-secret", "synthetic.jwt.secret", "synthetic-password")) assertFalse(text.contains(secret))
            assertTrue(report.getValue("README.txt").contains("5000"))
            assertTrue(report.getValue("README.txt").contains("фрагменты"))
        } finally { root.deleteRecursively() }
    }

    @Test fun captureUsesOriginalLogcatFlagsAndCurrentProcessFilterWithoutSu() = runBlocking {
        val root = Files.createTempDirectory("report-command").toFile()
        try {
            var command: List<String> = emptyList()
            val reportBuilder = BugReportBuilder(ReportContext(root)).apply {
                logcatProcess = { command = it; LogcatProcess("ReportMarker") }
            }
            reportBuilder.build()
            assertEquals(listOf("logcat", "-d", "-t", "5000", "-v", "threadtime"), command.take(6))
            assertEquals("--pid=${android.os.Process.myPid()}", command.last())
            assertFalse(command.any { it == "su" })
        } finally { root.deleteRecursively() }
    }

    @Test fun captureFailureKeepsAgentDiagnosticAndReportRemainsReadable() = runBlocking {
        val root = Files.createTempDirectory("report-capture-failure").toFile()
        try {
            val reportBuilder = BugReportBuilder(ReportContext(root)).apply {
                logcatProcess = { throw IllegalStateException("logcat unavailable") }
            }
            val logs = entries(reportBuilder.build()).getValue("logcat.txt")
            assertTrue(logs.contains("logcat unavailable"))
        } finally { root.deleteRecursively() }
    }

    @Test fun captureFailureRedactsItsMessageAndCompleteStack() = runBlocking {
        val root = Files.createTempDirectory("report-capture-secret").toFile()
        try {
            val reportBuilder = BugReportBuilder(ReportContext(root)).apply {
                logcatProcess = {
                    throw IllegalStateException("logcat unavailable password=capture-password", IllegalArgumentException("Bearer capture.token.secret"))
                        .apply { addSuppressed(IllegalStateException("sk-capture-secret")) }
                }
            }
            val report = entries(reportBuilder.build())
            val logs = report.getValue("logcat.txt")
            assertTrue(logs.contains("logcat unavailable"))
            assertTrue(logs.contains("java.lang.IllegalStateException"))
            assertTrue(logs.contains("Caused by:"))
            for (secret in listOf("capture-password", "capture.token.secret", "sk-capture-secret"))
                assertFalse("Secret leaked in capture failure", report.values.joinToString("\n").contains(secret))
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentReportsHaveUniqueRootNamesAndDoNotOverwrite() = runBlocking {
        val root = Files.createTempDirectory("report-unique").toFile()
        try {
            val reportBuilder = builder(ReportContext(root))
            val reports = List(12) { async { reportBuilder.build() } }.awaitAll()
            assertEquals(reports.size, reports.map { it.absolutePath }.toSet().size)
            for (report in reports) {
                assertTrue(report.name.matches(Regex("rikka-root-report-\\d{8}-\\d{6}-\\d{3}\\.zip")))
                assertTrue(entries(report).getValue("logcat.txt").contains("ReportMarker"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun stalledLogcatHasBoundedCaptureAndAllStreamsAreClosed() = runBlocking {
        val root = Files.createTempDirectory("report-timeout").toFile()
        val process = StalledLogcatProcess(stallWhileReading = true)
        try {
            val reportBuilder = BugReportBuilder(ReportContext(root)).apply { logcatProcess = { process } }
            val start = System.nanoTime()
            val logs = entries(reportBuilder.build()).getValue("logcat.txt")
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("Capture must stop before the eight-second fail-safe", elapsedMillis < 7_000)
            assertTrue(logs.contains("таймаут", ignoreCase = true))
            process.assertCleanedUp()
        } finally { process.destroy(); root.deleteRecursively() }
    }

    @Test fun cancellingLogcatWaitClosesProcessAndRemovesIncompleteReport() = runBlocking {
        val root = Files.createTempDirectory("report-cancel").toFile()
        val process = StalledLogcatProcess(stallWhileReading = false)
        val reportBuilder = BugReportBuilder(ReportContext(root)).apply { logcatProcess = { process } }
        val job = launch { reportBuilder.build(); fail("Cancelled capture returned a report") }
        try {
            withContext(Dispatchers.IO) { assertTrue(process.started.await(2, TimeUnit.SECONDS)) }
            job.cancel()
            withTimeout(1_000) { job.join() }
            process.assertCleanedUp()
            assertEquals(0, File(root, "cache/bug_reports").listFiles()?.size ?: 0)
        } finally {
            process.destroy()
            job.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    @Test fun toolReturnsOriginalAgentFieldsAndWorkspaceCopyReadableByArchiveTool() = runBlocking {
        val root = Files.createTempDirectory("report-workspace").toFile()
        try {
            val context = ReportContext(root)
            val chatWorkspace = File(root, "chat-workspace").apply { mkdirs() }
            val selectedOtherWorkspace = File(root, "selected-other-workspace").apply { mkdirs() }
            val fileAccess = access(context, chatWorkspace)
            val tool = generateBugReportTool(context, builder(context), copyToWorkspace = { copyBugReportToWorkspace(it, fileAccess) })
            assertEquals("generate_bug_report", tool.name)
            assertTrue(tool.needsApproval(buildJsonObject {}))
            assertTrue((tool.parameters() as InputSchema.Obj).properties.isEmpty())
            val result = tool.execute(buildJsonObject {}).filterIsInstance<UIMessagePart.Text>().single()
            val payload = JsonInstant.parseToJsonElement(result.text).jsonObject
            val cacheFile = File(payload.getValue("path").jsonPrimitive.content)
            assertEquals(cacheFile.length(), payload.getValue("size_bytes").jsonPrimitive.long)
            assertEquals("application/zip", payload.getValue("mime_type").jsonPrimitive.content)
            assertEquals("android.intent.action.SEND", payload.getValue("share_intent_action").jsonPrimitive.content)
            val path = payload.getValue("workspace_path").jsonPrimitive.content
            assertEquals("/workspace/reports/${cacheFile.name}", path)
            assertArrayEquals(cacheFile.readBytes(), File(chatWorkspace, "reports/${cacheFile.name}").readBytes())
            assertEquals(0, selectedOtherWorkspace.listFiles()?.size)
            val listing = listZipContentsTool(context, fileAccess).execute(buildJsonObject { put("source", path) })
                .filterIsInstance<UIMessagePart.Text>().single().text
            assertTrue(listing.contains("logcat.txt"))
            assertTrue(listing.contains("errors.txt"))
            assertFalse(listing.contains("\"error\""))
        } finally { root.deleteRecursively() }
    }

    @Test fun absentOrFailedWorkspaceDoesNotClaimCopyOrDiscardCacheReport() = runBlocking {
        val root = Files.createTempDirectory("report-no-workspace").toFile()
        try {
            val context = ReportContext(root)
            val tools = listOf(generateBugReportTool(context, builder(context)), generateBugReportTool(context, builder(context),
                copyToWorkspace = { error("workspace missing") }))
            for (tool in tools) {
                val payload = JsonInstant.parseToJsonElement(tool.execute(buildJsonObject {}).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
                assertFalse("workspace_path" in payload)
                assertTrue("workspace_copy_error" in payload)
                assertTrue(File(payload.getValue("path").jsonPrimitive.content).isFile)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun workspaceSymlinkCannotOverwriteAnOutsideFile() = runBlocking {
        val root = Files.createTempDirectory("report-symlink").toFile()
        try {
            val context = ReportContext(root)
            val workspace = File(root, "workspace").apply { mkdirs() }
            val outside = File(root, "outside").apply { mkdirs() }
            val zip = builder(context).build()
            val sentinel = File(outside, zip.name).apply { writeText("preserved") }
            Files.createSymbolicLink(File(workspace, "reports").toPath(), outside.toPath())
            try {
                copyBugReportToWorkspace(zip, access(context, workspace))
                fail("Symlink outside workspace was accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals("preserved", sentinel.readText())
            assertTrue(zip.isFile)
        } finally { root.deleteRecursively() }
    }
}
