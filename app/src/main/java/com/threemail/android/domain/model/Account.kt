package com.threemail.android.domain.model

data class Account(
    val id: Long = 0,
    val email: String,
    val displayName: String,
    val accountType: AccountType,
    val incomingServer: String? = null,
    val incomingPort: Int = 993,
    val useEncryption: Boolean = true,
    val password: String? = null,
    val isActive: Boolean = true,
    val syncEnabled: Boolean = true,
    val calendarSyncEnabled: Boolean = true,
    val pushEnabled: Boolean = true
)
