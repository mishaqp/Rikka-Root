package me.rerere.rikkahub.data.datastore

import android.content.Context
import java.io.File

/**
 * A corrupted settings file is replaced with defaults; without a notice the user would only see
 * missing providers and assistants. The marker survives until the user acknowledges it.
 */
object SettingsResetNotice {
    private fun marker(context: Context) = File(context.noBackupFilesDir, "settings-reset-notice")

    fun mark(context: Context, corruptCopyName: String?) {
        runCatching { marker(context).writeText(corruptCopyName.orEmpty()) }
    }

    fun pending(context: Context): Boolean = marker(context).isFile

    fun dismiss(context: Context) {
        marker(context).delete()
    }
}
