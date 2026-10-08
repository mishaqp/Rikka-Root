// Adapted from ExTV/rikkahub-agent reliability/BugReportBuilder.kt (AGPL-3.0).
package me.rerere.rikkahub.reliability

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Only stable application-owned codes are accepted; raw error text is never exported. */
enum class ReportErrorCode { NETWORK, PROVIDER, TOOL_EXECUTION, STORAGE, UNKNOWN }

data class ReportDiagnostic(val code: ReportErrorCode, val frames: List<StackTraceElement> = emptyList()) {
    companion object {
        /** Throwable messages, causes, suppressed exceptions and filenames are not serialized. */
        fun fromThrowable(code: ReportErrorCode, error: Throwable): ReportDiagnostic =
            ReportDiagnostic(code, error.stackTrace.take(MAX_FRAMES))
    }
}

internal data class BugReportMetadata(
    val versionName: String,
    val versionCode: String,
    val buildType: String,
    val manufacturer: String,
    val androidSdk: Int,
    val cpuAbis: List<String>,
    val processors: Int,
)

private const val MAX_DIAGNOSTICS = 16
private const val MAX_FRAMES = 16
private const val MAX_CONTENT_BYTES = 65_536
private const val MAX_ZIP_BYTES = 131_072L

// Prefix matching would allow arbitrary secrets hidden in a class/method name.
// Emit only these exact application-owned symbols, never filenames or freeform stack text.
private val knownFrames = mapOf(
    "me.rerere.rikkahub.data.ai.GenerationLoop" to setOf(
        "generateText", "generateInternal", "executeProviderRequestWithRetry", "awaitNetworkRetryOrThrow"
    ),
)
private val manufacturers = setOf(
    "google", "samsung", "xiaomi", "oneplus", "oppo", "vivo", "motorola", "sony", "huawei", "honor",
    "asus", "realme", "nokia", "nothing", "lenovo", "zte", "htc", "amazon",
)
private val allowedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64")
private val versionPattern = Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(?:-root\\.[0-9]{1,4})?")
private val versionCodePattern = Regex("[0-9]{1,10}")

/**
 * Creates a bounded, allowlisted ZIP under managed cache. No process execution or user data reads.
 * The optional loader is trusted application code that returns typed diagnostic records, never logs.
 */
class BugReportBuilder private constructor(
    private val cacheDirectory: () -> File,
    private val metadata: () -> BugReportMetadata,
    private val diagnosticLoader: suspend () -> List<ReportDiagnostic>,
) {
    // Defer context/metadata access until build, so initialization errors use the same safe boundary.
    constructor(context: Context, diagnosticLoader: suspend () -> List<ReportDiagnostic> = { emptyList() }) :
        this({ context.cacheDir }, {
            BugReportMetadata(
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), BuildConfig.BUILD_TYPE,
                Build.MANUFACTURER, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                Runtime.getRuntime().availableProcessors(),
            )
        }, diagnosticLoader)

    internal constructor(cacheDir: File, metadata: BugReportMetadata,
        diagnosticLoader: suspend () -> List<ReportDiagnostic> = { emptyList() }) :
        this({ cacheDir }, { metadata }, diagnosticLoader)

    suspend fun build(): File {
        var ownedFile: File? = null
        try {
            // Catch outside withContext too: cancellation during dispatch back must delete the ZIP.
            return withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                val cache = cacheDirectory().canonicalFile
                val directory = File(cache, "bug_reports")
                check(directory.canonicalFile == directory && (directory.isDirectory || directory.mkdirs()))
                check(directory.canonicalFile.parentFile == cache)
                val output = File.createTempFile("rikka-root-report-", ".zip", directory)
                ownedFile = output
                val contents = linkedMapOf(
                    "meta.txt" to metadataText(metadata()),
                    "diagnostics.txt" to diagnosticText(diagnosticLoader()),
                    "README.txt" to REPORT_README,
                )
                var totalBytes = 0
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    for ((name, text) in contents) {
                        coroutineContext.ensureActive()
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        check(bytes.size <= MAX_CONTENT_BYTES - totalBytes)
                        totalBytes += bytes.size
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                check(output.length() <= MAX_ZIP_BYTES)
                coroutineContext.ensureActive()
                output
            }
        } catch (cancelled: CancellationException) {
            ownedFile?.delete()
            throw cancelled
        } catch (_: Exception) {
            ownedFile?.delete()
            // Never retain raw cause/message: they can contain URLs, keys, commands or local paths.
            throw IllegalStateException("Не удалось создать диагностический отчёт в кэше приложения.")
        }
    }
}

private fun metadataText(meta: BugReportMetadata): String = buildString {
    val version = meta.versionName.takeIf { it.length <= 32 && versionPattern.matches(it) } ?: "unknown"
    val versionCode = meta.versionCode.takeIf { versionCodePattern.matches(it) } ?: "unknown"
    val buildType = meta.buildType.takeIf { it in setOf("debug", "release") } ?: "unknown"
    val manufacturer = meta.manufacturer.takeIf { it.length <= 32 }?.lowercase(Locale.ROOT)
        ?.takeIf { it in manufacturers } ?: "unknown"
    append("App: Rikka-Root\nVersion: $version\nVersion code: $versionCode\nBuild type: $buildType\n")
    append("Manufacturer: $manufacturer\n")
    append("Android SDK: ${meta.androidSdk.takeIf { it in 1..100 } ?: 0}\n")
    append("CPU ABI: ${meta.cpuAbis.take(8).filter { it in allowedAbis }.distinct().joinToString(",").ifEmpty { "unknown" }}\n")
    append("Processors: ${meta.processors.takeIf { it in 1..1024 } ?: 0}\n")
}

private suspend fun diagnosticText(diagnostics: List<ReportDiagnostic>): String = buildString {
    if (diagnostics.isEmpty()) append("Диагностические коды отсутствуют.\n")
    for ((index, diagnostic) in diagnostics.take(MAX_DIAGNOSTICS).withIndex()) {
        coroutineContext.ensureActive()
        append("Error ${index + 1}: ${diagnostic.code.name}\n")
        for (frame in diagnostic.frames.take(MAX_FRAMES)) {
            val knownMethods = knownFrames[frame.className] ?: continue
            if (frame.methodName !in knownMethods) continue
            append("  at ${frame.className}.${frame.methodName}")
            if (frame.lineNumber in 1..1_000_000) append(":${frame.lineNumber}")
            append('\n')
        }
    }
}

private val REPORT_README = """
    Диагностический отчёт Rikka-Root

    meta.txt: разрешённые поля версии приложения, производителя, Android и среды выполнения.
    diagnostics.txt: коды ошибок и известные символы стека без имён файлов и сообщений исключений.

    В отчёт не включаются журналы logcat, диалоги, настройки, ключи, пароли, команды,
    вывод команд, адреса серверов и произвольные пути. Файл хранится в кэше приложения.
""".trimIndent()
