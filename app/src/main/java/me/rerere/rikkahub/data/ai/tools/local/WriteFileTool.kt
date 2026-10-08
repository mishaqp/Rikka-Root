// Agent write_text_file schema adapted from ExTV/rikkahub-agent (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

fun writeTextFileTool(access: LocalFileAccess): Tool = fileJsonTool(
    "write_text_file", "Write UTF8 text to a guarded scratch/workspace path or SAF document. Default refuses an existing file; overwrite=true replaces it; append=true is supported for local files. Maximum16MiB. Local writes are staged before replacement.",
    fileSchema(listOf("path", "content"), "path" to "string", "content" to "string", "append" to "boolean", "overwrite" to "boolean")) { obj ->
    val text = fileArgument(obj, "content")
    require(text.length <= LocalFileAccess.MAX_WRITE_BYTES) { "Текст слишком велик." }
    val source = access.resolve(fileArgument(obj, "path"), true)
    val append = fileBoolean(obj, "append")
    val bytes = access.write(source, text.toByteArray(Charsets.UTF_8), fileBoolean(obj, "overwrite"), append)
    buildJsonObject { put("success", true); put("path", source.reference); put("bytes_written", bytes); put("appended", append) }
}
