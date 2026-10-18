// Agent write_text_file schema adapted from ExTV/rikkahub-agent (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

fun writeTextFileTool(access: LocalFileAccess): Tool = fileJsonTool(
    "write_text_file", "Записать текст UTF-8 в разрешённый файл workspace/scratch, SAF или явно включённой общей памяти; для двоичных данных используйте write_binary_file. По умолчанию существующий файл не заменяется: overwrite=false, append=false. overwrite=true заменяет файл, append=true добавляет текст только к локальному файлу. Максимум 16 МиБ закодированных данных; локальная запись готовится до публикации. Возвращает фактические path, bytes_written и appended." + ASSISTANT_FILE_DIRECTORY_HINT,
    fileSchema(listOf("path", "content"), "path" to "string", "content" to "string", "append" to "boolean", "overwrite" to "boolean")) { obj ->
    val text = fileArgument(obj, "content")
    require(text.length <= LocalFileAccess.MAX_WRITE_BYTES) { "Текст слишком велик." }
    val append = fileBoolean(obj, "append")
    val (source, bytes) = access.writePath(fileArgument(obj, "path"), text.toByteArray(Charsets.UTF_8), fileBoolean(obj, "overwrite"), append)
    buildJsonObject { put("success", true); put("path", source.reference); put("bytes_written", bytes); put("appended", append) }
}
