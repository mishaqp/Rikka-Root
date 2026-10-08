// Adapted from ExTV/rikkahub-agent, local/MicRecorderTool.kt (AGPL v3).
package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.ai.tools.local.PersonalUiResult
import java.io.File

/** One owning coroutine performs every recorder call; cancellation joins its non-cancellable cleanup. */
internal suspend fun capturePersonalAudio(context: Context, durationMs: Int, progress: (Int) -> Unit): PersonalUiResult {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        return PersonalUiResult.Error("Доступ к микрофону отозван. Разрешите его в настройках функции.")
    val directory=File(context.cacheDir,"tool-recordings").apply { mkdirs() }
    val output=File.createTempFile("recording_", ".m4a",directory)
    var returned=false
    try {
        var recorder: MediaRecorder? = null
        var started=false
        var elapsed=0
        var error: String?=null
        val hardwareError=CompletableDeferred<String>()
        try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val current = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }
                recorder=current
                current.setAudioSource(MediaRecorder.AudioSource.MIC)
                current.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                current.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                current.setAudioChannels(1); current.setAudioSamplingRate(44100); current.setAudioEncodingBitRate(64000)
                current.setMaxFileSize(16_777_216L)
                current.setOutputFile(output.path)
                current.setOnErrorListener { _, _, _ -> hardwareError.complete("Android остановил запись микрофона из-за ошибки.") }
                current.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) hardwareError.complete("Запись достигла предельного размера 16 МиБ.") }
                current.prepare()
                currentCoroutineContext().ensureActive()
                current.start(); started=true
            }
            val start=SystemClock.elapsedRealtime()
            error=withTimeoutOrNull(durationMs.toLong()) {
                coroutineScope {
                    val ticker=launch {
                        while (isActive) { progress((SystemClock.elapsedRealtime()-start).toInt().coerceAtMost(durationMs)); delay(250) }
                    }
                    try { hardwareError.await() } finally { ticker.cancelAndJoin() }
                }
            }
            elapsed=(SystemClock.elapsedRealtime()-start).toInt().coerceAtMost(durationMs)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error="Не удалось записать звук. Проверьте разрешение и не занят ли микрофон другим приложением." }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (started) try { recorder?.stop() } catch (_: Exception) { error="Запись не удалось завершить. Короткий или повреждённый файл удалён." }
                try { recorder?.release() } catch (_: Exception) { error="Android не подтвердил освобождение микрофона." }
            }
        }
        currentCoroutineContext().ensureActive()
        if (error != null) return PersonalUiResult.Error(error)
        if (!output.isFile || output.length() !in 1..16_777_216L) return PersonalUiResult.Error("Android не создал допустимую запись. Пустой или повреждённый файл удалён.")
        returned=true
        return PersonalUiResult.Recording(output,elapsed)
    } finally { if (!returned) output.delete() }
}
