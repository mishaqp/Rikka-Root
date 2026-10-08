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
    "Record microphone audio on a visible foreground screen with Cancel. duration_ms 1000-300000 (default 10000). Backgrounding/cancellation stops and removes partial recordings. Returns durable AAC/m4a file_uri and workspace_path in /upload.", LocalToolOption.MicRecorder,
    InputSchema.Obj(buildJsonObject { put("duration_ms",buildJsonObject { put("type","integer"); put("minimum",1000); put("maximum",300000) }) }), ::recordingDuration,
    read={ duration ->
        val result=awaitPersonalToolUi(context,PersonalUiRequest.Record(duration),duration+20000L)
        if (result !is PersonalUiResult.Recording) return@personalJsonTool personalUiError(result)
        try {
            if (!result.file.isFile || result.file.length() !in 1..16_777_216L) return@personalJsonTool deviceToolError("Запись отсутствует или повреждена.")
            val entity=files.saveManagedFromUri(FileFolders.UPLOAD,result.file.toUri(),"Запись.m4a","audio/mp4")
            val file=files.getFile(entity)
            buildJsonObject { put("success",true); put("path",file.path); put("file_uri",file.toUri().toString()); put("workspace_path","/upload/${file.name}"); put("duration_ms",result.durationMs) }
        } finally { result.file.delete() }
    })
