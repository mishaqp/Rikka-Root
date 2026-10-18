// Adapted from ExTV/rikkahub-agent, local/MicRecorderTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.core.net.toUri
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager

fun micRecorderTool(context: Context, files: FilesManager): Tool = personalJsonTool(context,"record_audio",
    "Записывает микрофон на видимом экране с кнопкой отмены; требуется доступ к микрофону. duration_ms 1000–300000, по умолчанию 10000. Фон или отмена останавливает запись и удаляет неполный файл. Для получения текста речи используйте speech_to_text. Успех JSON возвращает постоянные file_uri, workspace_path в /upload и duration_ms для AAC/m4a до 16 МиБ; не записывает скрытно.", LocalToolOption.MicRecorder,
    InputSchema.Obj(buildJsonObject { put("duration_ms",buildJsonObject { put("type","integer"); put("minimum",1000); put("maximum",300000); put("description", "Длительность записи в миллисекундах: 1000–300000, по умолчанию 10000."); put("default", 10000) }) }), ::recordingDuration,
    read={ duration ->
        val result=awaitPersonalToolUi(context,PersonalUiRequest.Record(duration),duration+20000L)
        if (result !is PersonalUiResult.Recording) return@personalJsonTool personalUiError(result, "Проверьте разрешение микрофона и завершите другой звонок или запись.")
        try {
            if (!result.file.isFile || result.file.length() !in 1..16_777_216L) return@personalJsonTool deviceToolError("Запись отсутствует или повреждена. Проверьте микрофон и выполните новую запись.")
            val entity=files.saveManagedFromUri(FileFolders.UPLOAD,result.file.toUri(),"Запись.m4a","audio/mp4")
            val file=files.getFile(entity)
            buildJsonObject { put("success",true); put("path",file.path); put("file_uri",file.toUri().toString()); put("workspace_path","/upload/${file.name}"); put("duration_ms",result.durationMs) }
        } finally { result.file.delete() }
    })
