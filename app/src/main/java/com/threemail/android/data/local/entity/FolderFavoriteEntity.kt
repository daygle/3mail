package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Local-only record of folders the user has marked as a favorite, plus the
 * position the user has dragged them to in the drawer's Favorites shortcut list.
 *
 * Lives in a side table (not a column on `folders`) so a server-driven folder
 * sync that issues `INSERT … REPLACE` on the parent `folders` rows cannot
 * silently wipe favorite state. The composite primary key matches the unique
 * `(accountId, serverId)` index on [FolderEntity], so a `(accountId, serverId)`
 * pair is the natural identifier across both tables.
 *
 * `accountId` carries a `CASCADE` FK to [AccountEntity] so removing an account
 * (which already cascades to its [FolderEntity] rows) also cleans up its
 * favorite entries. There is intentionally NO FK to [FolderEntity] here: if a
 * server-side folder sync deletes a folder row, we want the user's favorite
 * intent to survive so re-favoriting is implicit on the next folder reap.
 *
 * `position` is a contiguous 0..N-1 ordinal (per account) used by the
 * drawer's drag-reorder UI. No unique constraint on `position` - row identity
 * is the composite PK, and shuffle-style reorders don't need a uniqueness
 * constraint on the order column to be safe to write atomically. See
 * [com.threemail.android.data.local.dao.FolderDao.reorderFavorites] for
 * the @Transaction that reassigns positions on drop.
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
    val serverId: String,
    // 0-indexed slot within the user's pinned shortcut list. -1 means
    // "not yet ranked" - used only on the moment-of-insert inside a single
    // transactional addFavorite; the DAO replaces -1 with the running max
    // plus one before INSERT so callers never see -1 in a query result.
    val position: Int = -1
)
