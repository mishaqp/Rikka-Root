// Adapted from ExTV/rikkahub-agent reliability/BugReportBuilder.kt (AGPL-3.0).
package me.rerere.rikkahub.reliability

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BugReportBuilder"

/**
 * Builds a redacted bug-report ZIP suitable for sharing via the system share sheet.
 *
 * Contents (subject to availability):
 *   - meta.txt — version, device model, Android version, locale, timezone
 *   - logcat.txt — last 5000 logcat lines from this process, redacted by [SecretRedactor]
 *   - errors.txt — complete ChatService exceptions through the same redactor
 *
 * NOT included (deliberate):
 *   - Conversation dumps (logs and exception messages can still include fragments)
 *   - DataStore / Room dumps (would expose tokens, hosts, memories)
 *   - Files outside the app package
 *   - Tokens or keys (filtered by [SecretRedactor])
 *
 * Output goes to the app's cache directory under `bug_reports/` so the share sheet's
 * tempfile lifecycle handles cleanup. Caller is responsible for invoking
 * `ACTION_SEND` with the resulting URI.
 */
class BugReportBuilder(
    private val context: Context,
    private val errorsLoader: () -> List<Throwable> = { emptyList() },
) {
    // The command stays production logcat; JVM tests supply an in-memory Process.
    internal var logcatProcess: (List<String>) -> java.lang.Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    }

    suspend fun build(): File = withContext(Dispatchers.IO) {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.cacheDir, "bug_reports").apply { mkdirs() }
        val zipFile = File(outDir, "rikkahub-agent-bug-$ts.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putEntry("meta.txt", buildMeta())
            zip.putEntry("logcat.txt", captureLogcat())
            zip.putEntry("errors.txt", SecretRedactor.redact(errorsLoader().joinToString("\n\n") {
                it.stackTraceToString()
            }.ifEmpty { "Ошибок ChatService нет." }))
            zip.putEntry("README.txt", buildReadme())
        }
        Log.i(TAG, "build: wrote ${zipFile.length()} bytes to ${zipFile.absolutePath}")
        zipFile
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildMeta(): String = buildString {
        append("App: Rikka-Root\n")
        append("Version: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})\n")
        append("Build type: ${BuildConfig.BUILD_TYPE}\n")
        append("Application ID: ${BuildConfig.APPLICATION_ID}\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("Locale: ${Locale.getDefault()}\n")
        append("Timezone: ${java.util.TimeZone.getDefault().id}\n")
        append("Generated at: ${Date()}\n")
    }

    private fun buildReadme(): String =
        """
        Отчёт об ошибке Rikka-Root
        =========================

        Этот ZIP создан локально на устройстве. Он содержит:
          * meta.txt   — версию приложения, устройство и сведения об Android
          * logcat.txt — последние до 5000 строк журнала нашего процесса;
                          известные формы секретов скрыты редактором
          * errors.txt — полные тексты ошибок ChatService: тип, сообщение и стек;
                         известные формы секретов скрыты тем же редактором

        Отдельно в отчёт НЕ добавляются:
          * Ваши диалоги
          * Сохранённые SSH-хосты, токен Telegram, API-ключи, настройки MCP
          * Память и настройки ассистентов
          * Файлы за пределами процесса приложения

        Журналы провайдеров и сообщения исключений могут содержать фрагменты
        переписки. Перед публичной отправкой проверьте meta.txt, errors.txt и
        последние строки logcat.txt на личные данные и строки, похожие на ключи.
        Редактор скрывает распространённые формы, но не все возможные секреты.
        """.trimIndent()

    private fun captureLogcat(): String {
        // -t 5000: last ~5000 lines. -v threadtime: timestamps + thread/proc.
        // --pid limits the original Agent command to this process, without root/READ_LOGS.
        return try {
            val proc = logcatProcess(listOf("logcat", "-d", "-t", "5000", "-v", "threadtime",
                "--pid=${android.os.Process.myPid()}"))
            val raw = BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                lines.joinToString("\n")
            }
            proc.waitFor()
            SecretRedactor.redact(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "captureLogcat failed", t)
            "(Не удалось прочитать logcat: ${t.message ?: t.javaClass.simpleName})"
        }
    }
}

/**
 * Pattern-based redactor for known secret shapes appearing in logcat. Aggressive on
 * false positives — if a token-looking string slips through, that's a leak; if a
 * legitimate hex string gets blanked, that's just noise.
 */
object SecretRedactor {

    private val patterns: List<Pair<Regex, String>> = listOf(
        // Telegram bot tokens: <int>:<35-char alnum>
        Regex("""\b\d{8,12}:[A-Za-z0-9_-]{30,40}\b""") to "[redacted-telegram-token]",
        // Bearer / API-key-ish headers — captures `Authorization: Bearer XYZ` AND
        // `X-Api-Key: XYZ` as one unit so the value after the optional Bearer/Token
        // prefix gets redacted along with the header. Stops at newline so multi-line
        // logcat entries don't bleed into following lines.
        Regex("""(?i)(authorization|proxy-authorization|x-api-key|x-api-token|x-auth-token|x-access-token|cookie|set-cookie)\s*[:=]\s*(?:Bearer\s+|Token\s+)?[^\r\n,;]+""") to "$1: [redacted]",
        // 30+ char hex strings (likely keys / hashes)
        Regex("""\b[a-fA-F0-9]{32,}\b""") to "[redacted-hex]",
        // 30+ char base64-shaped tokens (alnum + + / =) — broad catch for raw tokens
        // logged outside a header context. Avoids matching `[redacted]`-style markers
        // because those are short.
        Regex("""\b[A-Za-z0-9+/]{30,}={0,2}\b""") to "[redacted-b64]",
        // ssh:// or sftp:// urls with embedded creds
        Regex("""(ssh|sftp)://[^\s/@]+@""") to "$1://[redacted]@",
    )

    // Added for Rikka-Root providers. Keep the original Agent patterns above unchanged.
    // Run these first so the broad original hex/base64 rules cannot split a key's suffix.
    private val providerPatterns: List<Pair<Regex, String>> = listOf(
        Regex("""(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----""") to "[redacted-private-key]",
        Regex("""\b(?:sk-|tp-|gsk_|xai-|AIza)[A-Za-z0-9._-]+""") to "[redacted-provider-key]",
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""") to "Bearer [redacted]",
        Regex("""(?i)(x-goog-api-key|xi-api-key|api-key|x-subscription-token|ocp-apim-subscription-key)\s*[:=]\s*[^\r\n,;]+""") to "$1: [redacted]",
        Regex("""(?i)(["']?\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|pwd|client[_-]?secret|private[_-]?key|secret[_-]?key|key)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|(?!\[redacted[^\]]*\])[^\s,;&}\]\r\n]+)""") to "$1[redacted]",
    )

    fun redact(input: String): String {
        var out = input
        for ((re, replacement) in providerPatterns + patterns) {
            out = re.replace(out, replacement)
        }
        return out
    }
}
