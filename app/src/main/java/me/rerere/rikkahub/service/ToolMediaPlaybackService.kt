// Adapted from ExTV/rikkahub-agent, service/MediaPlaybackService.kt (AGPL v3).
// Modified 2026-10-08: native session, awaited commands and cancellable preparation.
package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Pure session state rejects callbacks from replaced/released players. Owned by the main thread. */
internal class ToolPlaybackState {
    var generation = 0L; private set
    var source: String? = null; private set
    var phase = "idle"; private set
    var playWhenReady = false; private set
    var positionMs = 0L; private set
    var durationMs = 0L; private set

    fun begin(source: String, positionMs: Long): Long {
        require(positionMs >= 0)
        generation++
        this.source = source
        this.positionMs = positionMs
        durationMs = 0
        phase = "preparing"
        playWhenReady = true
        return generation
    }

    fun prepared(generation: Long, durationMs: Long): Boolean {
        if (this.generation != generation || phase != "preparing") return false
        this.durationMs = durationMs.coerceAtLeast(0)
        positionMs = clamp(positionMs)
        phase = if (playWhenReady) "playing" else "paused"
        return true
    }

    fun pause() {
        playWhenReady = false
        if (phase == "playing") phase = "paused"
    }

    fun resume() {
        playWhenReady = true
        if (phase == "paused" || phase == "completed") phase = "playing"
    }

    fun seekTarget(positionMs: Long): Long {
        require(positionMs >= 0) { "Позиция не может быть отрицательной." }
        require(source != null && phase in setOf("playing", "paused", "completed")) { "Нет готового медиасеанса." }
        return clamp(positionMs)
    }

    fun seekComplete(generation: Long, positionMs: Long): Boolean {
        if (this.generation != generation || phase !in setOf("playing", "paused", "completed")) return false
        this.positionMs = clamp(positionMs)
        return true
    }

    fun stop(positionMs: Long) {
        generation++
        this.positionMs = clamp(positionMs)
        playWhenReady = false
        phase = "stopped"
    }

    fun complete() { positionMs = durationMs; playWhenReady = false; phase = "completed" }
    private fun clamp(position: Long) = if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
}

data class ToolMediaStatus(
    val state: String = "no_session", val source: String? = null, val positionMs: Long = 0,
    val durationMs: Long = 0, val title: String? = null, val artist: String? = null,
    val album: String? = null, val error: String? = null,
) {
    val playing: Boolean get() = state == "playing"
}

/** Native MediaSession handles notification, Bluetooth and lock-screen media controls. */
class ToolMediaPlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "me.rerere.rikkahub.toolmedia.PLAY"
        const val ACTION_PAUSE = "me.rerere.rikkahub.toolmedia.PAUSE"
        const val ACTION_RESUME = "me.rerere.rikkahub.toolmedia.RESUME"
        const val ACTION_SEEK = "me.rerere.rikkahub.toolmedia.SEEK"
        const val ACTION_STOP = "me.rerere.rikkahub.toolmedia.STOP"
        private const val ACTION_CANCEL = "me.rerere.rikkahub.toolmedia.CANCEL"
        private const val CHANNEL = "tool_media_playback"
        private const val NOTIFICATION_ID = 7001
        private const val REQUEST_ID = "request_id"
        private val requests = ConcurrentHashMap<String, CompletableDeferred<ToolMediaStatus>>()
        private var instance: ToolMediaPlaybackService? = null
        private var stopped: ToolMediaStatus? = null

        suspend fun status(): ToolMediaStatus = withContext(Dispatchers.Main.immediate) {
            instance?.snapshot() ?: stopped ?: ToolMediaStatus()
        }

        suspend fun command(
            context: Context, action: String, source: String? = null, playbackUri: String? = null,
            title: String? = null, artist: String? = null, album: String? = null, positionMs: Long = 0,
        ): ToolMediaStatus {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<ToolMediaStatus>()
            requests[id] = deferred
            var delivered = false
            try {
                withContext(Dispatchers.Main.immediate) {
                    if (action != ACTION_PLAY && instance == null) {
                        deferred.complete(ToolMediaStatus(error = "Нет активного медиасеанса."))
                        return@withContext
                    }
                    val intent = Intent(context, ToolMediaPlaybackService::class.java).apply {
                        this.action = action
                        putExtra(REQUEST_ID, id); putExtra("source", source); putExtra("playback_uri", playbackUri)
                        putExtra("title", title); putExtra("artist", artist); putExtra("album", album)
                        putExtra("position_ms", positionMs)
                    }
                    if (action == ACTION_PLAY) ContextCompat.startForegroundService(context, intent)
                    else context.startService(intent)
                }
                val result = withTimeout(30_000) { deferred.await() }
                delivered = true
                return result
            } finally {
                requests.remove(id)
                if (!delivered) withContext(NonCancellable + Dispatchers.Main.immediate) {
                    // A queued or preparing PLAY must not outlive its cancelled tool invocation.
                    runCatching { context.startService(Intent(context, ToolMediaPlaybackService::class.java).apply {
                        this.action = ACTION_CANCEL; putExtra(REQUEST_ID, id)
                    }) }
                }
            }
        }
    }

    private val state = ToolPlaybackState()
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var focusGranted = false
    private var focusHeld = false
    private var resumeOnFocusGain = false
    private var currentTitle: String? = null
    private var currentArtist: String? = null
    private var currentAlbum: String? = null
    private var preparingRequest: String? = null
    private var sessionRequest: String? = null
    private var seekingRequest: String? = null
    private var seekInProgress = false
    private var seekPosition = 0L
    private var initialSeek = false
    private var noisyReceiverRegistered = false
    private var lastError: String? = null
    private var preparationTimeout: Runnable? = null
    private var seekTimeout: Runnable? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pausePlayback(abandon = true) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusGranted = true
                        focusHeld = true
                        if (resumeOnFocusGain) { resumeOnFocusGain = false; runCatching { resumePlayback() }.onFailure { failPlayback() } }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeOnFocusGain = state.playWhenReady
                        focusGranted = false
                        pausePlayback(abandon = false)
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pausePlayback(abandon = true) }
                }
            }, handler).build()
        session = MediaSession(this, "Rikka-Root tools").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { runCatching { resumePlayback() }.onFailure { failPlayback() } }
                override fun onPause() { resumeOnFocusGain = false; pausePlayback(abandon = true) }
                override fun onStop() { stopPlayback() }
                override fun onSeekTo(pos: Long) { seekPlayback(pos, null) }
            }, handler)
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Воспроизведение медиа", NotificationManager.IMPORTANCE_LOW)
        )
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(REQUEST_ID)
        if (intent?.action == ACTION_CANCEL) {
            if (id != null && sessionRequest == id) { stopPlayback(saveSnapshot = false) }
            if (id != null && seekingRequest == id) { seekingRequest = null }
            if (player == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Removed request means cancellation arrived before this intent was dispatched.
        if (id != null && requests[id] == null) {
            if (player == null) { startAsForeground(); stopSelf(startId) }
            return START_NOT_STICKY
        }
        try {
            when (intent?.action) {
                ACTION_PLAY -> {
                    instance = this
                    session.isActive = true
                    startAsForeground()
                    val source = intent.getStringExtra("source") ?: error("Источник не задан.")
                    val uri = intent.getStringExtra("playback_uri") ?: error("Источник не задан.")
                    startPlayback(source, uri, intent.getStringExtra("title"), intent.getStringExtra("artist"),
                        intent.getStringExtra("album"), intent.getLongExtra("position_ms", 0), id)
                }
                ACTION_PAUSE -> { resumeOnFocusGain = false; pausePlayback(abandon = true); finish(id) }
                ACTION_RESUME -> { resumePlayback(); finish(id) }
                ACTION_SEEK -> seekPlayback(intent.getLongExtra("position_ms", 0), id)
                ACTION_STOP -> { val result = snapshot(); stopPlayback(); finish(id, result.copy(state = "stopped")) }
                else -> { if (player == null) stopSelf(startId) }
            }
        } catch (_: Exception) {
            lastError = "Android не смог выполнить медиакоманду."
            finish(id, snapshot().copy(error = lastError))
            if (intent?.action == ACTION_PLAY) stopPlayback(saveSnapshot = false)
        }
        if (player == null && intent?.action != ACTION_PLAY) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startPlayback(source: String, uri: String, title: String?, artist: String?, album: String?, position: Long, id: String?) {
        releasePlayer("Медиасеанс заменён.")
        stopped = null
        currentTitle = title?.take(200); currentArtist = artist?.take(200); currentAlbum = album?.take(200)
        lastError = null
        val generation = state.begin(source, position)
        preparingRequest = id
        sessionRequest = id
        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        created.setOnPreparedListener {
            if (player !== created || !state.prepared(generation, created.duration.toLong())) return@setOnPreparedListener
            try {
                if (state.positionMs > 0) {
                    initialSeek = true
                    seekPosition = state.positionMs
                    created.seekTo(seekPosition, MediaPlayer.SEEK_CLOSEST)
                } else finishPreparation()
            } catch (_: Exception) { failPlayback() }
        }
        created.setOnSeekCompleteListener {
            if (player !== created || !state.seekComplete(generation, seekPosition)) return@setOnSeekCompleteListener
            if (initialSeek) { initialSeek = false; finishPreparation() }
            else {
                seekInProgress = false
                seekTimeout?.let(handler::removeCallbacks); seekTimeout = null
                publishState(); finish(seekingRequest); seekingRequest = null
            }
        }
        created.setOnCompletionListener {
            if (player === created) { state.complete(); abandonFocus(); publishState() }
        }
        created.setOnErrorListener { _, _, _ -> if (player === created) failPlayback(); true }
        created.setDataSource(this, Uri.parse(uri))
        created.prepareAsync()
        publishState()
        preparationTimeout = Runnable { if (player === created && (state.phase == "preparing" || initialSeek)) failPlayback("Подготовка медиа превысила 30 секунд.") }
        handler.postDelayed(preparationTimeout!!, 30_000)
    }

    private fun finishPreparation() {
        preparationTimeout?.let(handler::removeCallbacks); preparationTimeout = null
        if (state.playWhenReady && !startReadyPlayer()) return
        publishState()
        finish(preparingRequest)
        preparingRequest = null
    }

    private fun startReadyPlayer(): Boolean {
        if (!focusGranted) {
            if (focusHeld) abandonFocus()
            focusGranted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusHeld = focusGranted
            if (!focusGranted) { state.pause(); lastError = "Android не предоставил аудиофокус."; publishState(); finish(preparingRequest, snapshot()); preparingRequest = null; return false }
        }
        player?.start()
        state.resume()
        return true
    }

    private fun pausePlayback(abandon: Boolean) {
        if (state.phase == "playing") runCatching { player?.pause() }
        state.pause()
        if (abandon) { resumeOnFocusGain = false; abandonFocus() }
        publishState()
    }

    private fun resumePlayback() {
        lastError = null
        if (player == null) { lastError = "Нет активного медиасеанса."; return }
        if (state.phase == "preparing") state.resume()
        else if (state.phase == "completed") {
            state.resume(); seekPlayback(0, null); startReadyPlayer()
        } else startReadyPlayer()
        publishState()
    }

    private fun seekPlayback(position: Long, id: String?) {
        if (seekInProgress || initialSeek) { finish(id, snapshot().copy(error = "Предыдущая перемотка ещё не завершена.")); return }
        try {
            seekPosition = state.seekTarget(position)
            seekingRequest = id
            seekInProgress = true
            player?.seekTo(seekPosition, MediaPlayer.SEEK_CLOSEST) ?: error("Нет медиасеанса.")
            seekTimeout = Runnable { if (seekInProgress) failPlayback("Android не завершил перемотку за 15 секунд.") }
            handler.postDelayed(seekTimeout!!, 15_000)
        } catch (_: Exception) { seekInProgress = false; seekingRequest = null; finish(id, snapshot().copy(error = "Не удалось перемотать медиа.")) }
    }

    private fun failPlayback(message: String = "Не удалось прочитать или воспроизвести медиа.") {
        lastError = message
        val result = snapshot().copy(error = message)
        finish(preparingRequest, result); preparingRequest = null
        finish(seekingRequest, result); seekingRequest = null
        stopPlayback(saveSnapshot = false)
    }

    private fun finish(id: String?, result: ToolMediaStatus = snapshot()) { if (id != null) requests[id]?.complete(result) }
    private fun readPosition(): Long = if (state.phase == "preparing") state.positionMs else runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: state.positionMs
    private fun snapshot() = ToolMediaStatus(if (initialSeek) "preparing" else state.phase, state.source, readPosition(), state.durationMs, currentTitle, currentArtist, currentAlbum, lastError)

    private fun abandonFocus() {
        if (focusHeld) audioManager.abandonAudioFocusRequest(focusRequest)
        focusGranted = false
        focusHeld = false
    }
    private fun releasePlayer(reason: String) {
        finish(preparingRequest, snapshot().copy(error = reason)); preparingRequest = null
        finish(seekingRequest, snapshot().copy(error = reason)); seekingRequest = null
        sessionRequest = null
        preparationTimeout?.let(handler::removeCallbacks); preparationTimeout = null
        seekTimeout?.let(handler::removeCallbacks); seekTimeout = null
        seekInProgress = false
        initialSeek = false
        player?.let { runCatching { it.reset() }; it.release() }; player = null
        abandonFocus(); resumeOnFocusGain = false
    }

    private fun stopPlayback(saveSnapshot: Boolean = true) {
        val result = snapshot().copy(state = "stopped")
        if (saveSnapshot && result.source != null) stopped = result
        state.stop(result.positionMs)
        releasePlayer("Медиасеанс остановлен.")
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (instance === this) instance = null
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun publishState() {
        val phase = when (state.phase) {
            "preparing" -> PlaybackState.STATE_BUFFERING
            "playing" -> PlaybackState.STATE_PLAYING
            "paused" -> PlaybackState.STATE_PAUSED
            "completed", "stopped" -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_NONE
        }
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(phase, readPosition(), if (state.playWhenReady) 1f else 0f).build())
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle ?: "Медиа")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist).putString(MediaMetadata.METADATA_KEY_ALBUM, currentAlbum)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        fun actionPending(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(this, requestCode,
            Intent(this, ToolMediaPlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = state.playWhenReady
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle ?: "Воспроизведение медиа")
            .setContentText(if (state.phase == "preparing") "Подготовка…" else if (playing) currentArtist ?: "Воспроизводится" else "На паузе")
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(playing)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                if (playing) "Пауза" else "Продолжить", actionPending(if (playing) ACTION_PAUSE else ACTION_RESUME, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Остановить", actionPending(ACTION_STOP, 2)).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { builder.setContentIntent(PendingIntent.getActivity(this, 3, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer("Медиаслужба завершена.")
        if (noisyReceiverRegistered) unregisterReceiver(noisyReceiver)
        session.release()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
