// Adapted from ExTV/rikkahub-agent, local/MediaPlayerTool.kt (AGPL v3).
// Modified 2026-10-08: guarded media, native service and acknowledged commands.
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.service.ToolMediaPlaybackService
import me.rerere.rikkahub.service.ToolMediaStatus

private suspend fun playbackUri(source: String, access: LocalFileAccess): String {
    if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) return validatedDownloadUrl(source)
    return when (val guarded = access.resolve(source)) {
        is LocalFileSource.Local -> {
            val file = access.checkFile(guarded.file)
            require(file.isFile) { "Источник должен быть существующим медиафайлом. Проверьте source и доступ к файлу." }
            Uri.fromFile(file).toString()
        }
        is LocalFileSource.Content -> {
            require(!access.isDirectory(guarded)) { "Папку нельзя воспроизвести. Укажите медиафайл в source." }
            // Verify that a persisted or current user grant still opens this document.
            access.openInput(guarded).use { }
            guarded.uri.toString()
        }
    }
}

private fun ToolMediaStatus.payload(): JsonObject = buildJsonObject {
    put("success", error == null && state != "no_session")
    put("state", state); put("playing", playing)
    put("session_active", state in setOf("preparing", "playing", "paused", "completed"))
    source?.let { put("source", it) }
    put("position_ms", positionMs); put("duration_ms", durationMs)
    title?.let { put("title", it) }; artist?.let { put("artist", it) }; album?.let { put("album", it) }
    error?.let { put("error", it) }
    if (error == null && state == "no_session") put("message", "Нет медиасеанса. Для начала воспроизведения вызовите play_media и укажите source.")
}

private fun emptyMediaSchema() = InputSchema.Obj(properties = buildJsonObject { })

fun playMediaTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "play_media",
    description = "Start a new track at position zero using native Android media controls. Replaces the active session. " +
        "Use pause_media/resume_media to continue an existing track and seek_media to change its position. " +
        "Sources must be HTTP(S), allowed workspace/scratch files or user-granted SAF documents. Waits for preparation.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("source", buildJsonObject { put("type", "string"); put("description", "Allowed file reference or HTTP(S) URL") })
            listOf("title", "artist", "album").forEach { key -> put(key, buildJsonObject { put("type", "string"); put("description", "Optional media metadata") }) }
            put("artwork_uri", buildJsonObject { put("type", "string"); put("description", "Optional allowed artwork reference; cover display is not supported") })
        }, required = listOf("source"))
    },
    needsApproval = { true },
    execute = { arguments -> deviceToolResult {
        val params = arguments.jsonObject
        val source = params.textArgument("source") ?: error("Укажите source — HTTP(S)-ссылку, разрешённый путь или URI медиафайла.")
        val resolved = playbackUri(source, access)
        params.textArgument("artwork_uri")?.let { playbackUri(it, access) }
        ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PLAY, source, resolved,
            params.textArgument("title"), params.textArgument("artist"), params.textArgument("album")).payload()
    } }
)

fun pauseMediaTool(context: Context): Tool = Tool(
    name = "pause_media",
    description = "Pause the active native media session and preserve the current position. Use resume_media to continue.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult { ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PAUSE).payload() } }
)

fun resumeMediaTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "resume_media",
    description = "Resume the active track from its current position. If stopped, restore the last stopped track and position while the app process still exists.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult {
        val saved = ToolMediaPlaybackService.status()
        if (saved.state == "stopped" && saved.source != null) {
            ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PLAY, saved.source,
                playbackUri(saved.source, access), saved.title, saved.artist, saved.album, saved.positionMs).payload()
        } else ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_RESUME).payload()
    } }
)

fun stopMediaTool(context: Context): Tool = Tool(
    name = "stop_media",
    description = "Stop and release the active media player and dismiss its notification. Use pause_media for a temporary break.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult { ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_STOP).payload() } }
)

fun seekMediaTool(context: Context): Tool = Tool(
    name = "seek_media",
    description = "Seek to a nonnegative millisecond position in the ready native media session, preserving its playing or paused state. Clamps to the duration.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("position_ms", buildJsonObject { put("type", "integer"); put("description", "Nonnegative target position in milliseconds") })
        }, required = listOf("position_ms"))
    },
    needsApproval = { true },
    execute = { arguments -> deviceToolResult {
        val position = arguments.jsonObject["position_ms"]?.jsonPrimitive?.longOrNull ?: error("Укажите position_ms — неотрицательную позицию в миллисекундах.")
        require(position >= 0) { "Укажите неотрицательную позицию position_ms в миллисекундах." }
        ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_SEEK, positionMs = position).payload()
    } }
)

fun getMediaStatusTool(): Tool = Tool(
    name = "get_media_status",
    description = "Read the native media session state, source, current position, duration and metadata. This operation requires tool approval.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult { ToolMediaPlaybackService.status().payload() } }
)
