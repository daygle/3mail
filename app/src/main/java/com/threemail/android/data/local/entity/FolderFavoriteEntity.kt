package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Local-only record of folders the user has marked as a favorite.
 *
 * Lives in a side table (not a column on `folders`) so a server-driven folder
 * sync that issues `INSERT … REPLACE` on the parent `folders` rows cannot
 * silently wipe favorite state. The composite primary key matches the unique
 * `(accountId, serverId)` index on [FolderEntity], so a `(accountId, serverId)`
 * pair is the natural identifier across both tables.
 *
 * `accountId` carries a `CASCADE` FK to [AccountEntity] so removing an
 * account (which already cascades to its [FolderEntity] rows) also cleans
 * up its favorite entries. There is intentionally NO FK to [FolderEntity]
 * here: if a server-side folder sync deletes a folder row, we want the
 * user's favorite intent to survive so re-favoriting is implicit on the
 * next folder reap.
 */
@Entity(
    tableName = "folder_favorites",
    primaryKeys = ["accountId", "serverId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["accountId"])]
)
data class FolderFavoriteEntity(
    val accountId: Long,
    val serverId: String
)
