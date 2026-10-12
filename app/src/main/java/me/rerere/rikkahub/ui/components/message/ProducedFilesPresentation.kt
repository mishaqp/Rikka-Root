package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.UIMessagePart

internal enum class ProducedFileAction { WRITTEN, EDITED, DOCUMENT }

internal sealed interface ProducedFileSource {
    data class Workspace(val path: String) : ProducedFileSource
    data class Document(val uri: String, val mime: String) : ProducedFileSource
}

internal data class ProducedFilePresentation(
    val path: String,
    val displayName: String,
    val action: ProducedFileAction,
    val source: ProducedFileSource,
) {
    val identity: String = when (source) {
        is ProducedFileSource.Workspace -> "workspace:${source.path}"
        is ProducedFileSource.Document -> "document:${source.uri}"
    }
}

/** Project only paths that a completed tool returned as a successful file result. */
internal fun presentProducedFiles(parts: List<UIMessagePart>): List<ProducedFilePresentation> {
    val entries = linkedMapOf<String, ProducedFilePresentation>()
    parts.filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
        if (!tool.isExecuted) return@forEach
        val results = tool.output.filterIsInstance<UIMessagePart.Text>()
            .mapNotNull { result ->
                runCatching { Json.parseToJsonElement(result.text) as? JsonObject }.getOrNull()
            }
        if (results.any(JsonObject::isFailureResult)) return@forEach

        when (tool.toolName) {
            "workspace_write_file", "workspace_edit_file" -> {
                val isWrite = tool.toolName == "workspace_write_file"
                val result = results.firstOrNull { if (isWrite) it.isWorkspaceWriteResult() else it.isWorkspaceEditResult() }
                    ?: return@forEach
                val path = (result.string("path") ?: (tool.inputAsJson() as? JsonObject)?.string("path"))
                    ?.takeIf { it.isAbsoluteFilePath() } ?: return@forEach
                val entry = ProducedFilePresentation(
                    path = path,
                    displayName = path.substringAfterLast('/'),
                    action = if (isWrite) ProducedFileAction.WRITTEN else ProducedFileAction.EDITED,
                    source = ProducedFileSource.Workspace(path),
                )
                entries[entry.identity] = entry
            }
        }

        // A Document output supplies its own actionable URI. Current producer: generate_bug_report.
        // Guard that producer's explicit result because it may also return a diagnostic error.
        if (tool.toolName == "generate_bug_report" && results.none { it["ok"] == JsonPrimitive(true) })
            return@forEach
        tool.output.filterIsInstance<UIMessagePart.Document>().forEach { document ->
            val uri = document.url.takeIf { it.startsWith("file://") || it.startsWith("content://") }
                ?: return@forEach
            val name = document.fileName.takeIf(String::isNotBlank) ?: uri.substringAfterLast('/')
            val entry = ProducedFilePresentation(
                path = uri,
                displayName = name,
                action = ProducedFileAction.DOCUMENT,
                source = ProducedFileSource.Document(uri, document.mime),
            )
            entries[entry.identity] = entry
        }
    }
    return entries.values.toList()
}

private fun JsonObject.isFailureResult(): Boolean =
    this["success"] == JsonPrimitive(false) || this["ok"] == JsonPrimitive(false) ||
        this["error"]?.let { it != JsonNull && it != JsonPrimitive(false) } == true

private fun JsonObject.isWorkspaceWriteResult(): Boolean =
    (string("name")?.isNotBlank() == true && this["isDirectory"] == JsonPrimitive(false) &&
        (this["sizeBytes"] as? JsonPrimitive)?.longOrNull?.let { it >= 0 } == true &&
        this["updatedAt"] != null) ||
        (this["success"] == JsonPrimitive(true) && string("path") != null)

private fun JsonObject.isWorkspaceEditResult(): Boolean =
    (this["replacements"] as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true &&
        (this["sizeBytes"] as? JsonPrimitive)?.longOrNull?.let { it >= 0 } == true &&
        this["updatedAt"] != null

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun String.isAbsoluteFilePath(): Boolean = startsWith('/') && length > 1 && !endsWith('/')
