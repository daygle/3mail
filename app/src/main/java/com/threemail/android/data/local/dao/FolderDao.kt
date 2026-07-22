package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.FolderFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY type ASC, name ASC")
    fun getByAccount(accountId: Long): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY type ASC, name ASC")
    suspend fun getByAccountOnce(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND serverId = :serverId LIMIT 1")
    suspend fun getByServerId(accountId: Long, serverId: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<FolderEntity>): List<Long>

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE folders SET syncVersion = :syncVersion WHERE id = :id")
    suspend fun updateSyncVersion(id: Long, syncVersion: Long)

    @Query("UPDATE folders SET unreadCount = :unreadCount WHERE id = :id")
    suspend fun updateUnreadCount(id: Long, unreadCount: Int)

    @Query("UPDATE folders SET isHidden = :isHidden WHERE id = :id")
    suspend fun setHidden(id: Long, isHidden: Boolean)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)

    @Query("DELETE FROM folders WHERE accountId = :accountId AND serverId = :serverId")
    suspend fun deleteByServerId(accountId: Long, serverId: String)

    /** Change a folder's full path AND its displayed leaf name (used on rename). */
    @Query("UPDATE folders SET serverId = :newServerId, name = :newName WHERE accountId = :accountId AND serverId = :oldServerId")
    suspend fun updateFolderPathAndName(accountId: Long, oldServerId: String, newServerId: String, newName: String)

    /** Change only a folder's full path, keeping its name (used for descendants). */
    @Query("UPDATE folders SET serverId = :newServerId WHERE accountId = :accountId AND serverId = :oldServerId")
    suspend fun updateFolderServerId(accountId: Long, oldServerId: String, newServerId: String)

    /** Follow a folder's path change in the favourites side table so a renamed
     *  or moved folder keeps its starred state and drag-order slot. */
    @Query("UPDATE folder_favorites SET serverId = :newServerId WHERE accountId = :accountId AND serverId = :oldServerId")
    suspend fun updateFavoriteServerId(accountId: Long, oldServerId: String, newServerId: String)

    /**
     * Apply a rename or reparent (both are a full-path change) atomically for a
     * folder and all its descendants. [descendantRewrites] is a list of
     * (oldServerId -> newServerId) for every folder strictly beneath the moved
     * one; those keep their display name and only shift path prefix. The moved
     * folder itself takes [newServerId] and [newName] (unchanged on a pure move).
     *
     * Wrapped in a [Transaction] so a crash or cancellation mid-rewrite can't
     * leave the tree half-renamed. Descendants are rewritten before the folder
     * itself, but order is immaterial here: the server has already accepted the
     * same rename, so none of the target paths collide with a surviving row.
     * Favourite rows are moved alongside each path so starred state survives.
     */
    @Transaction
    suspend fun relocateFolder(
        accountId: Long,
        oldServerId: String,
        newServerId: String,
        newName: String,
        descendantRewrites: List<Pair<String, String>>
    ) {
        descendantRewrites.forEach { (oldId, newId) ->
            updateFolderServerId(accountId, oldId, newId)
            updateFavoriteServerId(accountId, oldId, newId)
        }
        updateFolderPathAndName(accountId, oldServerId, newServerId, newName)
        updateFavoriteServerId(accountId, oldServerId, newServerId)
    }

    /**
     * Delete a folder and its descendants locally after a successful server
     * delete. Removing each `folders` row cascades to its cached `messages`
     * (folderId FK is ON DELETE CASCADE); the favourites side table has no FK,
     * so its rows are cleared explicitly. [serverIds] must include the folder
     * itself plus every descendant serverId.
     */
    @Transaction
    suspend fun deleteFolderTree(accountId: Long, serverIds: List<String>) {
        serverIds.forEach { serverId ->
            removeFavorite(accountId, serverId)
            deleteByServerId(accountId, serverId)
        }
    }

    /**
     * Reactive feed of all (accountId, serverId) pairs in `folder_favorites`
     * for the given account, in the order the user has dragged them to.
     *
     * Sort key: `position ASC, rowid ASC`. The position column is the
     * contiguous 0..N-1 user rank (set by
     * [com.threemail.android.data.local.migrations.MIGRATION_11_12]); `rowid`
     * is the implicit tie-breaker for rows that all landed at the default
     * `position = 0` on migration - `rowid` is SQLite's monotonic insert
     * counter so ties fall back to FIFO insertion order.
     *
     * Used by [com.threemail.android.data.repository.MailRepository] via
     * `Flow.combine` so the drawer's Favorites section updates the moment
     * the user taps a star toggle or finishes a drag.
     */
    @Query("SELECT * FROM folder_favorites WHERE accountId = :accountId ORDER BY position ASC, rowid ASC")
    fun getFavoritesByAccount(accountId: Long): Flow<List<FolderFavoriteEntity>>

    /**
     * One-shot snapshot of favorite pairs, ranked. Used by single-row folder
     * lookups (getFolderByServerId / getFolderById) so they don't silently
     * return `isFavorite = false` for a folder that is, in fact, starred,
     * AND by the repository when it needs to compute
     * `MAX(position) + 1` to append a newly-starred folder at the bottom
     * of the user's pinned list.
     */
    @Query("SELECT * FROM folder_favorites WHERE accountId = :accountId ORDER BY position ASC, rowid ASC")
    suspend fun getFavoritesByAccountOnce(accountId: Long): List<FolderFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(favorite: FolderFavoriteEntity)

    @Query("DELETE FROM folder_favorites WHERE accountId = :accountId AND serverId = :serverId")
    suspend fun removeFavorite(accountId: Long, serverId: String)

    /**
     * Update the `position` column for one (accountId, serverId) pair.
     * Public so unit tests can drive position reassignments directly
     * without round-tripping through a list-ordering API. Default-body
     * callers should use [reorderFavorites] instead - that wrapper wraps
     * N updates in a single [Transaction].
     */
    @Query("UPDATE folder_favorites SET position = :position WHERE accountId = :accountId AND serverId = :serverId")
    suspend fun updatePosition(accountId: Long, serverId: String, position: Int)

    /**
     * Reassign positions for ALL favorites of one account, in the order
     * given. Wrapped in [Transaction] so a partial reorder (e.g. crash
     * mid-loop, cancellation between UPDATEs) rolls back instead of leaving
     * the user's pinned shortcut list half-renumbered. Position 0 is the
     * top-of-list slot - the drawer's "pinned shortcuts" mental model.
     *
     * ServerIds not currently present in `folder_favorites` are silently
     * ignored (the per-row UPDATE matches zero rows, no error). Reorder is
     * a no-op for unknowns, which is the safe fallback - the UI only
     * supplies serverIds that came from the current list, so a partial
     * table state means a phantom folder was filtered out at draw time.
     *
     * The folder content is unchanged: just whose order.
     */
    @Transaction
    suspend fun reorderFavorites(accountId: Long, serverIds: List<String>) {
        serverIds.forEachIndexed { index, serverId ->
            updatePosition(accountId, serverId, index)
        }
    }
}
