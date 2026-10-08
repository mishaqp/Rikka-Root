// Adapted from ExTV/rikkahub-agent, local/StorageTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private fun storageStats(path: String): JsonObject {
    val stat = StatFs(path)
    return buildJsonObject {
        put("total_bytes", stat.totalBytes)
        put("free_bytes", stat.availableBytes)
        put("used_bytes", stat.totalBytes - stat.availableBytes)
    }
}

fun storageTool(): Tool = Tool(
    name = "get_storage_info",
    description = "Read total, free and used space on internal and primary external storage. Does not read or enumerate files.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    execute = { deviceToolResult {
        val internal = storageStats(Environment.getDataDirectory().path)
        val externalState = Environment.getExternalStorageState()
        @Suppress("DEPRECATION")
        val external = if (externalState == Environment.MEDIA_MOUNTED || externalState == Environment.MEDIA_MOUNTED_READ_ONLY) {
            try { storageStats(Environment.getExternalStorageDirectory().path) } catch (_: RuntimeException) { null }
        } else null
        buildJsonObject {
            put("internal", internal)
            put("external", external ?: JsonNull)
            if (external == null) put("note", "Внешний накопитель не подключён или его статистика недоступна.")
        }
    } },
)
