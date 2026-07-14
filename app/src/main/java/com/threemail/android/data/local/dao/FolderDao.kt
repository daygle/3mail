package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.threemail.android.data.local.entity.FolderEntity
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

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
}
