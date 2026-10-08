package me.rerere.rikkahub.reliability

import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class BugReportBuilderTest {
    private val metadata = BugReportMetadata("2.5.6-root.2", "256", "debug", "Google", 35, listOf("arm64-v8a"), 8)
    private val safeFrame = StackTraceElement("me.rerere.rikkahub.data.ai.GenerationLoop", "generateInternal", "/private/very-secret/path.kt", 42)
    private val secrets = listOf("qx", "s!", "short password", "sk-not_a_standard-secret-shape-123456789", "/data/private/customer.keys")

    private fun entries(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    @Test fun zipContainsOnlyAllowedMetadataCodesAndKnownStackSymbols() = runBlocking {
        val dir = Files.createTempDirectory("report-privacy").toFile()
        try {
            val error = IllegalStateException(secrets.joinToString(" ")).apply {
                stackTrace = arrayOf(safeFrame,
                    StackTraceElement("private.qx", "s!", secrets.last(), 1),
                    StackTraceElement("me.rerere.rikkahub.data.ai.qx", "generateInternal", "qx", 2),
                    StackTraceElement("me.rerere.rikkahub.data.ai.GenerationLoop", "qx", "qx", 3))
            }
            val file = BugReportBuilder(dir, metadata) {
                listOf(ReportDiagnostic.fromThrowable(ReportErrorCode.PROVIDER, error))
            }.build()
            val report = entries(file)
            assertEquals(setOf("meta.txt", "diagnostics.txt", "README.txt"), report.keys)
            assertTrue(file.canonicalPath.startsWith(File(dir, "bug_reports").canonicalPath + File.separator))
            assertTrue(report.getValue("meta.txt").contains("2.5.6-root.2"))
            assertTrue(report.getValue("meta.txt").contains("SDK: 35"))
            assertTrue(report.getValue("diagnostics.txt").contains("PROVIDER"))
            assertTrue(report.getValue("diagnostics.txt").contains("GenerationLoop.generateInternal:42"))
            val all = report.values.joinToString("\n")
            (secrets + listOf("IllegalStateException", "/private/very-secret/path.kt", dir.absolutePath)).forEach {
                assertFalse("Unsafe diagnostic input leaked: $it", all.contains(it))
            }
            assertFalse(report.keys.any { it.contains("logcat") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun metadataRejectsFreeformDeviceAndBuildFields() = runBlocking {
        val dir = Files.createTempDirectory("report-meta").toFile()
        try {
            val report = entries(BugReportBuilder(dir, metadata.copy(versionName = secrets[0], versionCode = secrets[1],
                buildType = secrets[2], manufacturer = secrets[3], cpuAbis = secrets)).build()).values.joinToString("\n")
            secrets.forEach { assertFalse(report.contains(it)) }
            assertTrue(report.contains("unknown"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun repeatedReportsHaveExclusiveNamesAndBoundedContent() = runBlocking {
        val dir = Files.createTempDirectory("report-bounds").toFile()
        try {
            val builder = BugReportBuilder(dir, metadata) {
                List(500) { ReportDiagnostic(ReportErrorCode.TOOL_EXECUTION, List(500) { safeFrame }) }
            }
            val first = builder.build()
            val bytes = first.readBytes()
            val second = builder.build()
            assertNotEquals(first.name, second.name)
            assertArrayEquals(bytes, first.readBytes())
            assertEquals(2, File(dir, "bug_reports").listFiles()?.size)
            listOf(first, second).forEach {
                assertTrue(it.length() <= 131072L)
                assertTrue(entries(it).values.sumOf { text -> text.toByteArray().size } <= 65536)
                assertTrue(entries(it).getValue("diagnostics.txt").lines().count { line -> line.startsWith("  at ") } <= 256)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationRemovesPartialArchiveAndPropagates() = runBlocking {
        val dir = Files.createTempDirectory("report-cancel").toFile()
        try {
            val started = CompletableDeferred<Unit>()
            val builder = BugReportBuilder(dir, metadata) {
                started.complete(Unit)
                CompletableDeferred<List<ReportDiagnostic>>().await()
            }
            val job = launch { builder.build() }
            started.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(0, File(dir, "bug_reports").listFiles()?.size ?: 0)
            try {
                BugReportBuilder(dir, metadata) { throw CancellationException("qx") }.build()
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) { }
            assertEquals(0, File(dir, "bug_reports").listFiles()?.size ?: 0)
        } finally { dir.deleteRecursively() }
    }

    @Test fun loaderAndDirectoryFailuresAreSanitizedAndCleanedUp() = runBlocking {
        val dir = Files.createTempDirectory("report-failure").toFile()
        try {
            var loaderInvoked = false
            var partialFileObserved = false
            try {
                BugReportBuilder(dir, metadata) {
                    loaderInvoked = true
                    partialFileObserved = File(dir, "bug_reports").listFiles()?.singleOrNull()?.isFile == true
                    throw IllegalArgumentException(secrets.joinToString(" "))
                }.build()
                fail("Loader failure was swallowed")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("отчёт", ignoreCase = true))
                secrets.forEach { assertFalse(failure.message.orEmpty().contains(it)) }
                assertNull(failure.cause)
            }
            assertTrue("Failure path never invoked the diagnostic loader", loaderInvoked)
            assertTrue("Failure path never created an owned partial ZIP", partialFileObserved)
            assertEquals(0, File(dir, "bug_reports").listFiles()?.size ?: 0)
            val reports = File(dir, "bug_reports")
            reports.deleteRecursively()
            reports.writeText("preexisting")
            try {
                BugReportBuilder(dir, metadata).build()
                fail("Non-directory cache entry was accepted")
            } catch (failure: IllegalStateException) {
                assertFalse(failure.message.orEmpty().contains(dir.absolutePath))
                assertNull(failure.cause)
            }
            assertEquals("preexisting", reports.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun toolRequiresApprovalHasNoInputsAndSanitizesInitializationErrors() = runBlocking {
        val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = error("qx /private/path")
        }
        val tool = generateBugReportTool(context)
        assertEquals("generate_bug_report", tool.name)
        assertTrue(tool.needsApproval(buildJsonObject {}))
        assertTrue((tool.parameters() as InputSchema.Obj).properties.isEmpty())
        val invalid = tool.execute(JsonObject(mapOf("logcat" to JsonPrimitive("qx")))).single() as UIMessagePart.Text
        assertTrue(invalid.text.contains("error"))
        assertFalse(invalid.text.contains("qx"))
        val failure = tool.execute(buildJsonObject {}).single() as UIMessagePart.Text
        assertTrue(failure.text.contains("error"))
        assertFalse(failure.text.contains("qx"))
        assertFalse(failure.text.contains("/private/path"))
    }
}
