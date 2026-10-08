package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity

@Dao
interface ScheduledJobDao {
    @Insert suspend fun insert(job: ScheduledJobEntity)
    @Query("SELECT * FROM scheduled_jobs WHERE id = :id") suspend fun getById(id: String): ScheduledJobEntity?
    @Query("SELECT * FROM scheduled_jobs WHERE owner_assistant_id = :owner AND deleted = 0 ORDER BY created_at_ms DESC LIMIT 100") suspend fun listOwned(owner: String): List<ScheduledJobEntity>
    @Query("SELECT * FROM scheduled_jobs WHERE enabled = 1 AND deleted = 0") suspend fun active(): List<ScheduledJobEntity>
    @Query("UPDATE scheduled_jobs SET enabled = :enabled, revision = revision + 1, next_run_at_ms = NULL WHERE id = :id AND owner_assistant_id = :owner AND deleted = 0") suspend fun setEnabled(id: String, owner: String, enabled: Boolean): Int
    @Query("UPDATE scheduled_jobs SET enabled = 0, deleted = 1, payload_ciphertext = '', revision = revision + 1, next_run_at_ms = NULL WHERE id = :id AND owner_assistant_id = :owner AND deleted = 0") suspend fun deleteOwned(id: String, owner: String): Int
    @Query("UPDATE scheduled_jobs SET revision = :newRevision, next_run_at_ms = NULL WHERE id = :id AND revision = :expectedRevision AND enabled = 1 AND deleted = 0") suspend fun invalidateSchedule(id: String, expectedRevision: Long, newRevision: Long): Int
    @Query("UPDATE scheduled_jobs SET next_run_at_ms = :next WHERE id = :id AND revision = :revision AND enabled = 1 AND deleted = 0") suspend fun setNext(id: String, revision: Long, next: Long?): Int
    @Query("UPDATE scheduled_jobs SET manual_sequence = manual_sequence + 1 WHERE id = :id AND revision = :revision AND enabled = 1 AND deleted = 0") suspend fun allocateManual(id: String, revision: Long): Int
    @Query("UPDATE scheduled_jobs SET total_runs = total_runs + 1, last_claimed_scheduled_at_ms = CASE WHEN :manual = 0 THEN :scheduledAt ELSE last_claimed_scheduled_at_ms END, last_claimed_manual_sequence = CASE WHEN :manual = 1 THEN :sequence ELSE last_claimed_manual_sequence END WHERE id = :id AND revision = :revision AND enabled = 1 AND deleted = 0") suspend fun recordClaim(id: String, revision: Long, manual: Boolean, scheduledAt: Long, sequence: Long): Int
    @Query("UPDATE scheduled_jobs SET total_succeeded = total_succeeded + CASE WHEN :status = 'succeeded' THEN 1 ELSE 0 END, total_failed = total_failed + CASE WHEN :status = 'failed' THEN 1 ELSE 0 END, total_blocked = total_blocked + CASE WHEN :status = 'blocked' THEN 1 ELSE 0 END, total_cancelled = total_cancelled + CASE WHEN :status = 'cancelled' THEN 1 ELSE 0 END, total_indeterminate = total_indeterminate + CASE WHEN :status = 'indeterminate' THEN 1 ELSE 0 END WHERE id = :id") suspend fun recordOutcome(id: String, status: String): Int
}
