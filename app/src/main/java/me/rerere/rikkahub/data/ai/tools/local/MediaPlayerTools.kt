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
    description = "Начать новый медиафайл с нулевой позиции через Android, заменив текущий сеанс. " +
        "Для продолжения используйте pause_media/resume_media, для перемотки — seek_media. " +
        "Источник: HTTP(S), разрешённый файл workspace/scratch или документ SAF с пользовательским доступом. Вызов ожидает подготовки проигрывателя.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("source", buildJsonObject { put("type", "string"); put("description", "Источник медиа: разрешённый путь к файлу, URI документа SAF или URL по HTTP(S).") })
            listOf("title", "artist", "album").forEach { key -> put(key, buildJsonObject {
                put("type", "string")
                put("description", when (key) { "title" -> "Необязательное название медиа для проигрывателя."; "artist" -> "Необязательное имя исполнителя."; else -> "Необязательное название альбома." })
            }) }
            put("artwork_uri", buildJsonObject { put("type", "string"); put("description", "Необязательная разрешённая ссылка на обложку; проверяется доступ к файлу, но показ обложки не поддерживается.") })
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
    description = "Приостановить текущий медиасеанс, сохранив позицию. Для продолжения используйте resume_media, для завершения — stop_media.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult { ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_PAUSE).payload() } }
)

fun resumeMediaTool(context: Context, access: LocalFileAccess = LocalFileAccess(context)): Tool = Tool(
    name = "resume_media",
    description = "Продолжить текущую запись с сохранённой позиции. После stop_media восстанавливает последний остановленный файл и позицию, пока жив процесс приложения.",
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
    description = "Остановить медиапроигрыватель, освободить его и убрать уведомление. Для временной паузы используйте pause_media.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult { ToolMediaPlaybackService.command(context, ToolMediaPlaybackService.ACTION_STOP).payload() } }
)

fun seekMediaTool(context: Context): Tool = Tool(
    name = "seek_media",
    description = "Перемотать подготовленный медиасеанс к неотрицательной позиции в миллисекундах, сохранив воспроизведение или паузу. Позиция ограничивается длительностью записи.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("position_ms", buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "Целевая позиция в миллисекундах: целое число не меньше 0; позиция за концом ограничивается длительностью записи.") })
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
    description = "Проверить состояние медиасеанса, источник, позицию, длительность и метаданные, не меняя воспроизведение. Вызов требует одобрения инструмента.",
    parameters = { emptyMediaSchema() },
    needsApproval = { true },
    execute = { deviceToolResult { ToolMediaPlaybackService.status().payload() } }
)
