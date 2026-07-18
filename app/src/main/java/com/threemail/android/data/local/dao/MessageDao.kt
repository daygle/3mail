package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.threemail.android.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY date DESC")
    fun getByFolder(folderId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getByFolderPaged(folderId: Long, limit: Int, offset: Int): List<MessageEntity>

    /**
     * Reactive, bounded feed of a single folder, newest first. Room re-emits
     * on any insert / delete / flag flip touching the folder, so the inbox
     * reflects sync results, swipe actions, and multi-select batch triage
     * without the viewmodel having to re-query. LIMIT keeps a giant folder
     * from streaming every row into Compose.
     */
    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY date DESC LIMIT :limit")
    fun observeByFolder(folderId: Long, limit: Int): Flow<List<MessageEntity>>

    /**
     * Reactive, bounded cross-account unified inbox: every message in a folder
     * of type INBOX, newest first, aggregated across all accounts via a JOIN
     * on `folders`. Same reactivity guarantees as [observeByFolder].
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "JOIN folders f ON m.folderId = f.id " +
            "WHERE f.type = 'INBOX' " +
            "ORDER BY m.date DESC LIMIT :limit"
    )
    fun observeUnifiedInbox(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY date DESC")
    suspend fun getByFolderOnce(folderId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(accountId: Long, messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND threadId = :threadId ORDER BY date DESC")
    fun getByThread(accountId: Long, threadId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT messages.* FROM messages " +
            "JOIN messages_fts ON messages.id = messages_fts.rowid " +
            "WHERE messages_fts MATCH :query " +
            "ORDER BY messages.date DESC"
    )
    fun search(query: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET isRead = :isRead WHERE id = :id")
    suspend fun updateReadStatus(id: Long, isRead: Boolean)

    @Query("UPDATE messages SET isStarred = :isStarred WHERE id = :id")
    suspend fun updateStarred(id: Long, isStarred: Boolean)

    @Query("UPDATE messages SET bodyHtml = :bodyHtml, bodyPlain = :bodyPlain, bodyPreview = :bodyPreview, attachmentsJson = :attachmentsJson WHERE id = :id")
    suspend fun updateBody(id: Long, bodyHtml: String?, bodyPlain: String?, bodyPreview: String, attachmentsJson: String)

    @Query("UPDATE messages SET folderId = :folderId WHERE id = :id")
    suspend fun updateFolder(id: Long, folderId: Long)

    @Query("UPDATE messages SET isRead = 1 WHERE folderId = :folderId")
    suspend fun markAllReadInFolder(folderId: Long)

    @Query("SELECT MAX(uid) FROM messages WHERE folderId = :folderId")
    suspend fun getMaxUid(folderId: Long): Long?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND threadId = :threadId ORDER BY date ASC")
    suspend fun getThreadOnce(accountId: Long, threadId: String): List<MessageEntity>

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE folderId = :folderId AND isRead = 0")
    fun getUnreadCount(folderId: Long): Flow<Int>

    /**
     * Aggregate unread count across the inbox folders of every account.
     *
     * Backed by a JOIN on the `folders` table so the count is reactive: any
     * `insert`/`delete` and read-flag flip on a folder with `type = 'INBOX'`
     * re-emits. Powering the launcher badge from this query keeps the count
     * accurate without polling.
     */
    @Query(
        "SELECT COUNT(*) FROM messages m " +
            "JOIN folders f ON m.folderId = f.id " +
            "WHERE f.type = 'INBOX' AND m.isRead = 0"
    )
    fun observeTotalUnreadAcrossInboxes(): Flow<Int>
}
