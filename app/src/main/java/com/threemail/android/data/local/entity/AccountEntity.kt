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
    val useEncryption: Boolean = true,
    val password: String? = null,
    val isActive: Boolean = true,
    val syncEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
