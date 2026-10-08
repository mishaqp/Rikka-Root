package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

/**
 * A saved SSH host the LLM (or user) can reference by name.
 *
 * Adapted from ExTV/rikkahub-agent (AGPL v3): Room stores metadata only. Credentials are
 * transient and persisted separately through AndroidKeyStore-backed authenticated encryption.
 */
@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    /** Display name; also the lookup key from the LLM. */
    @PrimaryKey val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val hasPassword: Boolean = false,
    val hasPrivateKey: Boolean = false,
    val createdAtMs: Long,
) {
    @Ignore var password: String? = null
    @Ignore var privateKey: String? = null
    @Ignore var passphrase: String? = null
}
