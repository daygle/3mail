package com.threemail.android.domain.model

/**
 * Connection security applied to both IMAP and SMTP. Replaces the legacy
 * `useEncryption: Boolean` (where `true` meant implicit SSL/TLS) with a
 * tri-state enum that also exposes STARTTLS, which many self-hosted IMAP
 * providers require.
 *
 * The on-disk representation in
 * [com.threemail.android.data.local.entity.AccountEntity] is still the
 * (useEncryption, useStartTls) boolean pair - kept that way because an
 * additive column migration in SQLite is far safer than a column-type swap.
 * [com.threemail.android.data.repository.AccountRepository] reconciles the
 * two and enforces the invariant that exactly one of SSL_TLS, STARTTLS, or
 * NONE is representable in domain code.
 *
 * Gmail accounts ignore this entirely: they authenticate via OAuth XOAUTH2
 * and the Gmail REST API rather than IMAP/SMTP.
 */
enum class Security { NONE, STARTTLS, SSL_TLS }

data class Account(
    val id: Long = 0,
    val email: String,
    val displayName: String,
    val accountType: AccountType,
    val incomingServer: String? = null,
    val incomingPort: Int = 993,
    // Outgoing (SMTP submission) server. When null, ImapClient falls back to a
    // best-effort guess from the email domain / incoming server. Set explicitly
    // for providers whose SMTP host isn't derivable from the IMAP host.
    val outgoingServer: String? = null,
    val outgoingPort: Int = 587,
    val security: Security = Security.SSL_TLS,
    val password: String? = null,
    val isActive: Boolean = true,
    val syncEnabled: Boolean = true,
    val calendarSyncEnabled: Boolean = true,
    val pushEnabled: Boolean = true,
    /** Per-account signature; blank falls back to the global signature. */
    val signature: String = "",
    /**
     * Per-account mail-check frequency in minutes. `0` means "use the global
     * default sync interval".
     */
    val syncIntervalMinutes: Long = 0,
    /** Per-account new-mail notification toggle (gated by the global switch). */
    val notificationsEnabled: Boolean = true
)
