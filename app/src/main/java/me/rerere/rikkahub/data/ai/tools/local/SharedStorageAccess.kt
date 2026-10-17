package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files

/** A live capability, independent of SAF grants. Trusted Android volume roots are its only boundary. */
internal class SharedStorageAccess(
    private val isEnabled: () -> Boolean,
    private val isGranted: () -> Boolean,
    private val volumeRoots: () -> Map<String, File>,
    private val primaryAliases: List<File> = listOf(File("/sdcard"), File("/mnt/sdcard"), File("/storage/self/primary")),
) {
    // A compound read reuses Android's volume topology only until that call ends.
    // Capability switches remain live in resolve(), including on every guarded I/O.
    private class TopologySnapshot(var roots: Map<String, File>? = null)
    private val topologySnapshot = ThreadLocal<TopologySnapshot>()

    internal suspend fun <T> withTopologySnapshot(action: suspend () -> T): T {
        if (topologySnapshot.get() != null) return action()
        return withContext(topologySnapshot.asContextElement(TopologySnapshot())) { action() }
    }

    private fun currentVolumeRoots(): Map<String, File> {
        val snapshot = topologySnapshot.get() ?: return volumeRoots()
        return snapshot.roots ?: volumeRoots().toMap().also { snapshot.roots = it }
    }

    fun resolve(path: String, write: Boolean = false): File? {
        if (!isEnabled() || !isGranted()) return null
        // Refuse these addresses even if a faulty platform mapping ever names them as a volume.
        if (isPrivateSystemPath(File(path))) return null
        val address = storageAddress(path, currentVolumeRoots(), primaryAliases) ?: return null
        require(!isPrivateSystemPath(address.root)) { "Системные и личные данные приложений недоступны файловым инструментам." }
        val root = address.root.toPath()
        val relative = root.relativize(address.file.toPath())
        val names = if (relative.toString().isEmpty()) emptyList() else relative.map { it.toString() }
        require(names.size <= LocalFileAccess.MAX_DEPTH && names.all { it.length <= 255 }) { "Путь слишком длинный или глубокий." }
        if (names.size >= 2 && names[0].equals("Android", true) &&
            (names[1].equals("data", true) || names[1].equals("obb", true))) throw SharedStoragePrivatePathException()
        var current = address.root
        for (name in names) {
            current = File(current, name)
            require(!Files.isSymbolicLink(current.toPath())) { "Символические ссылки внутри памяти недоступны файловым инструментам." }
        }
        val canonical = current.canonicalFile
        require(!isPrivateSystemPath(canonical)) { "Системные и личные данные приложений недоступны файловым инструментам." }
        require(canonical.toPath().startsWith(root)) { "Выход за пределы памяти запрещён." }
        require(!write || canonical != address.root) { "Изменение корня памяти запрещено." }
        return canonical
    }

    private fun isPrivateSystemPath(file: File): Boolean =
        listOf(File("/data").toPath(), File("/system").toPath()).any { file.toPath().startsWith(it) }

    companion object {
        fun forContext(context: Context) = SharedStorageAccess(
            isEnabled = { GlobalContext.getOrNull()?.getOrNull<SettingsStore>()?.settingsFlow?.value?.fileToolsAllStorage == true },
            isGranted = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() },
            volumeRoots = { scannerVolumeRoots(context) },
        )
    }
}

internal class SharedStoragePrivatePathException : IllegalArgumentException(
    "Папки Android/data и Android/obb недоступны файловым инструментам. Выберите обычную папку, например Download/Rikka.")

/** A preference for the model, never a pathname rewrite or an implicit grant. */
internal const val ASSISTANT_FILE_DIRECTORY_HINT =
    " При включённых «Файловые инструменты: вся память телефона» и системном доступе ко всем файлам " +
        "/sdcard, /storage/emulated/0 и подключённые карты памяти доступны напрямую. " +
        "Если /sdcard/Download/Rikka доступна через SAF либо этот режим, предпочитайте её для сохранения без указанной папки. " +
        "Указанный пользователем путь имеет приоритет. /data, /system, Android/data и Android/obb запрещены."
