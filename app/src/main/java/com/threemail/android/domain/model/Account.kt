package com.threemail.android.domain.model

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
    val useEncryption: Boolean = true,
    val password: String? = null,
    val isActive: Boolean = true,
    val syncEnabled: Boolean = true,
    val calendarSyncEnabled: Boolean = true,
    val pushEnabled: Boolean = true
)
