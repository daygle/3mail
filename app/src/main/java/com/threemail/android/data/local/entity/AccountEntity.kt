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
    /**
     * Per-account signature appended to composed mail from this account. Empty
     * means "no signature for this account"; the composer treats a blank value
     * as a fall-through to the global signature in
     * [com.threemail.android.data.settings.SettingsRepository].
     */
    val signature: String = "",
    /**
     * Per-account mail-check frequency in minutes. `0` is the sentinel for
     * "follow the global default" so a user who never touches this keeps the
     * app-wide cadence, while an override schedules a dedicated periodic
     * [com.threemail.android.sync.MailSyncWorker] for just this account. See
     * [com.threemail.android.sync.SyncScheduler.reconcileAccountSyncs].
     */
    val syncIntervalMinutes: Long = 0,
    /**
     * Per-account toggle for new-mail notifications. Gated together with the
     * global [com.threemail.android.data.settings.AppSettings.notificationsEnabled]
     * master switch, so both must be on for this account to notify.
     */
    val notificationsEnabled: Boolean = true,
    /**
     * JSON-encoded list of additional send-as identities (aliases). Stored as a
     * single column rather than a child table because identities are small,
     * always loaded with the account, and never queried independently. Default
     * `[]` keeps the v15 -> v16 migration a single additive `ALTER TABLE`.
     */
    val identitiesJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)
