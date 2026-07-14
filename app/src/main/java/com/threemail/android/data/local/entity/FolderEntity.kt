package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.threemail.android.domain.model.FolderType

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId", "serverId"], unique = true),
        Index(value = ["accountId", "type"])
    ]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long,
    val serverId: String,
    val name: String,
    val type: FolderType,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val syncVersion: Long = 0
)
