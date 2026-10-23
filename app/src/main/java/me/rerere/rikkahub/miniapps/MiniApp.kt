package me.rerere.rikkahub.miniapps

// Kotlin adaptation of Moru mini_app_store.dart, pinned 2101b5e457031d8adb1098dac880bdbb3019623f.
import java.io.File
import kotlinx.serialization.json.*

data class MiniApp(
    val id: String,
    val name: String,
    val directory: File,
    val description: String = "",
    val entry: String = "index.html",
    val icon: String? = null,
    val dataHelp: String = "",
    val network: List<String> = emptyList(),
    val permissions: Set<String> = emptySet(),
    val updatedAt: Long = 0,
) {
    val codeDirectory get() = File(directory, "app")
    val entryFile get() = File(codeDirectory, entry)
    val iconFile get() = icon?.let { File(codeDirectory, it) }
    val link get() = MiniAppStore.linkFor(id)
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("name", name)
        if (description.isNotEmpty()) put("description", description)
        put("entry", entry)
        icon?.let { put("icon", it) }
        if (dataHelp.isNotEmpty()) put("data", dataHelp)
        if (network.isNotEmpty()) put("network", JsonArray(network.map(::JsonPrimitive)))
        if (permissions.isNotEmpty()) put("permissions", JsonArray(permissions.sorted().map(::JsonPrimitive)))
        put("updatedAt", updatedAt)
    }
}

class MiniAppException(val code: String, message: String) : Exception(message)
data class MiniAppStorageChange(val appId: String, val key: String)
data class MiniAppInstallResult(val app: MiniApp, val updated: Boolean, val files: Int, val bytes: Long)
data class MiniAppImportResult(val app: MiniApp, val updated: Boolean, val dataRestored: Boolean)
