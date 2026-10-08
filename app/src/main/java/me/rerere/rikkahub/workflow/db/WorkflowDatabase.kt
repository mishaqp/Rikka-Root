package me.rerere.rikkahub.workflow.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

/** Dedicated database requested for Root, using the original Agent entities and DAOs. */
@Database(entities = [WorkflowEntity::class, WorkflowRunEntity::class], version = 1, exportSchema = true)
abstract class WorkflowDatabase : RoomDatabase() {
    abstract fun workflowDao(): WorkflowDao
    abstract fun workflowRunDao(): WorkflowRunDao

    companion object {
        const val NAME = "workflows.db"

        fun create(context: Context, name: String = NAME): WorkflowDatabase =
            Room.databaseBuilder(context.applicationContext, WorkflowDatabase::class.java, name)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Root's consistent VACUUM INTO snapshots need bundled SQLite on older Android.
                // Workflows do not use the main database's FTS/dictionary extensions.
                .openHelperFactory(RequerySQLiteOpenHelperFactory())
                .build()
    }
}
