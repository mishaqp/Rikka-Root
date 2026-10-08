package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.reliability.SecretRedactor
import me.rerere.rikkahub.workflow.db.WorkflowDatabase
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Required backup compatibility for the user-requested separate workflow database. */
internal object WorkflowDatabaseBackup {
    const val ARCHIVE_DATABASE = WorkflowDatabase.NAME
    const val WAL = "$ARCHIVE_DATABASE-wal"
    const val SHM = "$ARCHIVE_DATABASE-shm"

    fun createSnapshot(database: WorkflowDatabase, destination: File) {
        DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, destination)
    }

    /** Only mutates the staged copy; schema/content/credential failures leave live data untouched. */
    suspend fun normalizeAndValidate(
        context: Context,
        databaseFile: File,
        sanitizeDefinition: (WorkflowDefinition) -> WorkflowDefinition,
        normalizeDatabase: (Context, File) -> Unit = DatabaseBackup::normalize,
        openDatabase: (Context, String) -> WorkflowDatabase = WorkflowDatabase::create,
    ) {
        normalizeDatabase(context, databaseFile)
        val cleanSnapshot = File.createTempFile("${databaseFile.name}-clean-", ".db", databaseFile.parentFile)
        check(cleanSnapshot.delete()) { "Не удалось подготовить очищенную копию сценариев." }
        try {
            val room = openDatabase(context, databaseFile.absolutePath)
            try {
                // Forces Room to validate table shape and version before any installation is published.
                val sqlite = room.openHelper.writableDatabase
                room.withTransaction {
                    for (entity in room.workflowDao().listAll()) {
                        val definition = parseForRestore(entity.definitionJson)
                        val protected = sanitizeDefinition(definition)
                        require(protected.actions.size == definition.actions.size &&
                            protected.copy(actions = definition.actions) == definition) {
                            "Защита секретов изменила структуру сценария. Восстановление не применено."
                        }
                        val error = entity.lastRunError?.let(SecretRedactor::redact)
                        if (protected != definition || error != entity.lastRunError) {
                            room.workflowDao().upsert(entity.copy(
                                definitionJson = if (protected != definition) WorkflowJson.encode(protected) else entity.definitionJson,
                                lastRunError = error,
                            ))
                        }
                    }
                    val redactedErrors = buildList {
                        sqlite.query("SELECT rowId, errorMessage FROM workflow_runs WHERE errorMessage IS NOT NULL").use { cursor ->
                            while (cursor.moveToNext()) {
                                val original = cursor.getString(1)
                                val redacted = SecretRedactor.redact(original)
                                if (redacted != original) add(cursor.getLong(0) to redacted)
                            }
                        }
                    }
                    for ((id, error) in redactedErrors) {
                        sqlite.execSQL("UPDATE workflow_runs SET errorMessage = ? WHERE rowId = ?", arrayOf<Any>(error, id))
                    }
                }
                // UPDATE removes plaintext from rows, but SQLite can retain the old bytes in
                // freed pages. VACUUM INTO copies only live data into a standalone clean file.
                DatabaseBackup.createSnapshot(sqlite, cleanSnapshot)
                DatabaseBackup.checkpoint(sqlite)
            } finally {
                room.close()
            }
            DatabaseBackup.removeSidecars(databaseFile)
            Files.move(cleanSnapshot.toPath(), databaseFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            cleanSnapshot.delete()
        }
    }

    private fun parseForRestore(raw: String): WorkflowDefinition {
        val definition = try {
            WorkflowJson.parseStored(raw)
        } catch (_: Exception) { null }
        require(definition != null) { "Повреждённое определение сценария. Восстановление не применено." }
        // The source reader tolerates forward-compatible fields but silently drops bad entries.
        // Restoring must never turn a damaged condition into an unconditional background action.
        val root = Json.parseToJsonElement(raw) as? JsonObject
        val actions = root?.get("actions") as? JsonArray
        val conditions = root?.get("conditions") as? JsonArray
        require(actions?.size == definition.actions.size &&
            (conditions?.size ?: 0) == definition.conditions.size) {
            "Повреждены действия или условия сценария. Восстановление не применено."
        }
        return definition
    }
}
