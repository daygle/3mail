package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.threemail.android.domain.model.AccountType

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val email: String,
    val displayName: String,
    val accountType: AccountType,
    val incomingServer: String? = null,
    val incomingPort: Int = 993,
    val outgoingServer: String? = null,
    val outgoingPort: Int = 587,
    /**
     * Legacy "true means implicit SSL/TLS on the IMAPS port (993)" flag. The
     * repository mapper translates this into the domain [com.threemail.android.domain.model.Security]
     * enum, which is the only thing callers should be reading in new code.
     * Preserved here to make the v9 -> v10 backfill a single `ALTER TABLE …
     * DEFAULT 0` rather than a column-type swap.
     */
    val useEncryption: Boolean = true,
    /**
     * Adds STARTTLS-on-port-143 support at the same time the entity gained the
     * [com.threemail.android.domain.model.Security] enum on the domain model.
     * The repository mapper enforces "exactly one of (useEncryption, useStartTls)
     * is true at the domain layer", so the two columns together map cleanly to
     * `Security.SSL_TLS` / `Security.STARTTLS` / `Security.NONE`.
     */
    val useStartTls: Boolean = false,
    val password: String? = null,
    val isActive: Boolean = true,
    val syncEnabled: Boolean = true,
    val calendarSyncEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
