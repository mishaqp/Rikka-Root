package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Only scheduling metadata is cleartext. User text and execution snapshot are encrypted. */
@Entity(tableName = "scheduled_jobs", indices = [Index(value = ["owner_assistant_id"]), Index(value = ["next_run_at_ms"])])
data class ScheduledJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("owner_assistant_id") val ownerAssistantId: String,
    @ColumnInfo("creator_conversation_id") val creatorConversationId: String,
    @ColumnInfo("payload_ciphertext") val payloadCiphertext: String,
    val revision: Long = 1,
    val mode: String,
    @ColumnInfo("schedule_type") val scheduleType: String,
    @ColumnInfo("at_unix_ms") val atUnixMs: Long? = null,
    @ColumnInfo("cron_expression") val cronExpression: String? = null,
    val timezone: String,
    @ColumnInfo("start_at_ms") val startAtMs: Long? = null,
    @ColumnInfo("end_at_ms") val endAtMs: Long? = null,
    @ColumnInfo("max_runs") val maxRuns: Long? = null,
    val catchup: String = "fire_once",
    val enabled: Boolean = true,
    val deleted: Boolean = false,
    @ColumnInfo("created_at_ms") val createdAtMs: Long,
    @ColumnInfo("next_run_at_ms") val nextRunAtMs: Long? = null,
    @ColumnInfo("last_claimed_scheduled_at_ms") val lastClaimedScheduledAtMs: Long? = null,
    @ColumnInfo("manual_sequence") val manualSequence: Long = 0,
    @ColumnInfo("last_claimed_manual_sequence") val lastClaimedManualSequence: Long = 0,
    @ColumnInfo("total_runs") val totalRuns: Long = 0,
    @ColumnInfo("total_succeeded") val totalSucceeded: Long = 0,
    @ColumnInfo("total_failed") val totalFailed: Long = 0,
    @ColumnInfo("total_blocked") val totalBlocked: Long = 0,
    @ColumnInfo("total_cancelled") val totalCancelled: Long = 0,
    @ColumnInfo("total_indeterminate") val totalIndeterminate: Long = 0,
)
