package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

@Dao
interface ScheduledJobRunDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(run: ScheduledJobRunEntity): Long
    @Query("SELECT * FROM scheduled_job_runs WHERE occurrence_id = :id") suspend fun getById(id: String): ScheduledJobRunEntity?
    @Query("SELECT * FROM scheduled_job_runs WHERE job_id = :jobId ORDER BY scheduled_at_ms DESC, occurrence_id DESC LIMIT :limit") suspend fun recent(jobId: String, limit: Int): List<ScheduledJobRunEntity>
    @Query("SELECT COUNT(*) FROM scheduled_job_runs WHERE job_id = :jobId AND status IN ('queued', 'running')") suspend fun pendingCount(jobId: String): Int
    @Query("SELECT * FROM scheduled_job_runs WHERE status = 'queued' ORDER BY scheduled_at_ms, manual_sequence") suspend fun queued(): List<ScheduledJobRunEntity>
    @Query("SELECT * FROM scheduled_job_runs WHERE job_id = :jobId AND status = 'queued'") suspend fun queuedForJob(jobId: String): List<ScheduledJobRunEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM scheduled_job_runs WHERE job_id = :jobId AND status = 'running')") suspend fun hasRunning(jobId: String): Boolean
    @Query("UPDATE scheduled_job_runs SET status = 'running', claim_token = :token, started_at_ms = :now WHERE occurrence_id = :id AND status = 'queued'") suspend fun claim(id: String, token: String, now: Long): Int
    @Query("UPDATE scheduled_job_runs SET status = :status, outcome_code = :code, finished_at_ms = :now WHERE occurrence_id = :id AND status = 'running' AND claim_token = :token") suspend fun finish(id: String, token: String, status: String, code: String?, now: Long): Int
    @Query("UPDATE scheduled_job_runs SET status = :status, outcome_code = :code, finished_at_ms = :now WHERE occurrence_id = :id AND status = 'queued'") suspend fun finishQueued(id: String, status: String, code: String?, now: Long): Int
    @Query("SELECT * FROM scheduled_job_runs WHERE status = 'running'") suspend fun running(): List<ScheduledJobRunEntity>
    @Query("SELECT * FROM scheduled_job_runs WHERE job_id = :jobId AND status = 'running'") suspend fun runningForJob(jobId: String): List<ScheduledJobRunEntity>
    @Query("UPDATE scheduled_job_runs SET status = 'cancelled', finished_at_ms = :now, outcome_code = 'job_disabled' WHERE job_id = :jobId AND status = 'queued'") suspend fun cancelQueued(jobId: String, now: Long): Int
    @Query("DELETE FROM scheduled_job_runs WHERE job_id = :jobId AND status NOT IN ('queued', 'running') AND occurrence_id NOT IN (SELECT occurrence_id FROM scheduled_job_runs WHERE job_id = :jobId AND status NOT IN ('queued', 'running') ORDER BY scheduled_at_ms DESC, occurrence_id DESC LIMIT :keep)") suspend fun trim(jobId: String, keep: Int)
    @Query("DELETE FROM scheduled_job_runs WHERE job_id = :jobId") suspend fun deleteForJob(jobId: String)
}
