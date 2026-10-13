package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Marks a failure at an Android SAF boundary without copying provider text. */
internal class SafFileOperationException(cause: Exception) : Exception(null, cause)

/** Fixed code identifiers, never a tool argument, pathname or provider message. */
internal enum class FileToolOperation(val action: String, val permissionAction: String) {
    COPY("Копирование файла", "создать копию"),
    MOVE("Перемещение файла", "переместить файл"),
    ZIP("Создание ZIP", "создать ZIP"),
    UNZIP("Распаковка ZIP", "распаковать ZIP"),
    LIST_ZIP("Чтение ZIP", "прочитать ZIP"),
    READ("Чтение файла", "прочитать файл"),
    WRITE("Запись файла", "записать файл"),
    CREATE("Создание папки", "создать папку"),
    DELETE("Удаление файла", "удалить файл"),
    LIST("Просмотр файлов", "прочитать список файлов"),
    INFO("Чтение свойств файла", "прочитать свойства файла"),
    OPEN("Открытие файла", "открыть файл"),
    SHOW_IMAGE("Чтение изображения", "прочитать изображение"),
}

internal fun fileToolOperation(name: String): FileToolOperation? = when (name) {
    "copy_file", "batch_copy" -> FileToolOperation.COPY
    "move_file", "batch_move" -> FileToolOperation.MOVE
    "zip_files" -> FileToolOperation.ZIP
    "unzip_file" -> FileToolOperation.UNZIP
    "list_zip_contents" -> FileToolOperation.LIST_ZIP
    "read_file" -> FileToolOperation.READ
    "write_text_file", "write_binary_file" -> FileToolOperation.WRITE
    "create_directory" -> FileToolOperation.CREATE
    "delete_file", "batch_delete" -> FileToolOperation.DELETE
    "list_files", "find_files" -> FileToolOperation.LIST
    "file_info" -> FileToolOperation.INFO
    "open_file" -> FileToolOperation.OPEN
    "show_image" -> FileToolOperation.SHOW_IMAGE
    else -> null
}

/** Unwrap only our SAF marker; ordinary app-authored validation remains unchanged. */
internal fun safFileCause(error: Throwable): Throwable? {
    if (error !is SafFileOperationException) return null
    var cause: Throwable = error
    repeat(16) {
        if (cause !is SafFileOperationException) return cause
        cause = cause.cause ?: return cause
    }
    return cause
}

/**
 * The last failure survives later successes until process exit. Retain only two strings;
 * never retain the Throwable, stack, cause, message, input parameters, URI or pathname.
 */
internal object FileOperationDiagnostics {
    private data class Failure(val operation: FileToolOperation, val exceptionType: String)
    private var lastFailure: Failure? = null

    @Synchronized
    fun record(operation: FileToolOperation, error: Throwable) {
        val direct = safFileCause(error) ?: error
        if (direct is CancellationException) return
        var exception = direct
        // Recovery/cleanup errors can contain a provider marker below their own cause.
        var cursor: Throwable? = error
        repeat(16) {
            val current = cursor ?: return@repeat
            safFileCause(current)?.let { exception = it }
            cursor = current.cause
        }
        if (exception is CancellationException) return
        lastFailure = Failure(operation, exception.javaClass.name)
    }

    @Synchronized
    fun report(): String = buildString {
        append("Файловые операции: последняя неудачная операция текущего процесса.\n")
        append("Сохраняются только вид операции и класс исключения, без путей и сообщений.\n")
        val failure = lastFailure
        if (failure == null) append("Неудачных файловых операций не зарегистрировано.")
        else append(buildJsonObject {
            put("operation", failure.operation.name)
            put("exception_type", failure.exceptionType)
        })
    }
}
