package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId", "folderId", "messageId"], unique = true),
        Index(value = ["folderId"]),
        Index(value = ["accountId", "threadId"]),
        Index(value = ["date"]),
        Index(value = ["isRead"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val messageId: String,
    val threadId: String? = null,
    val subject: String,
    val fromJson: String,
    val toJson: String,
    val ccJson: String,
    val bccJson: String,
    val date: Long,
    val bodyPreview: String = "",
    val bodyHtml: String? = null,
    val bodyPlain: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isDraft: Boolean = false,
    val attachmentsJson: String = "[]",
    val uid: Long = 0,
    val remoteId: String = "",
    val syncedAt: Long = 0
)
