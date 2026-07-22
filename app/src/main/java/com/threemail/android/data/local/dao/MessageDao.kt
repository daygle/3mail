package com.threemail.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
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

    /**
     * Reactive, unbounded feed of a single folder, newest first. Room re-emits
     * on any insert / delete / flag flip touching the folder, so the inbox
     * reflects sync results, swipe actions, and multi-select batch triage
     * without the viewmodel having to re-query. The feed is intentionally NOT
     * capped at a SQL LIMIT - a previous `LIMIT 50` call site cap silently
     * hid every message older than the latest 50, which on a busy inbox meant
     * "today's e-mail only" from the user's perspective. Feed size is bounded
     * by what's in the database; the rendering consumer handles large lists
     * downstream (Compose's LazyColumn is the canonical example in this app,
     * but the DAO only states the contract). If a pathological folder ever
     * needs paging, an `ORDER BY date DESC LIMIT/OFFSET` companion query can
     * be added without changing this contract.
     */
    /**
     * Reactive, unbounded feed of a single folder, newest first. Room re-emits
     * on any insert / delete / flag flip touching the folder, so the inbox
     * reflects sync results, swipe actions, and multi-select batch triage
     * without the viewmodel having to re-query. The feed is intentionally NOT
     * capped at a SQL LIMIT - a previous `LIMIT 50` call site cap silently
     * hid every message older than the latest 50, which on a busy inbox meant
     * "today's e-mail only" from the user's perspective (the exact complaint
     * this change was made for). Feed size grows with the synced window;
     * no SQL-level cap is applied. The rendering consumer handles large lists
     * downstream.
     * If a pathological folder ever needs paging, an `ORDER BY date DESC
     * LIMIT/OFFSET` companion query can be added without changing this
     * contract.
     */
    @Query("SELECT * FROM messages WHERE folderId = :folderId ORDER BY date DESC")
    fun observeByFolder(folderId: Long): Flow<List<MessageEntity>>

    @Query("""
        SELECT m.*, f.isEncrypted 
        FROM messages m 
        LEFT JOIN message_flags f ON m.accountId = f.accountId AND m.messageId = f.messageId
        WHERE m.folderId = :folderId 
        ORDER BY m.date DESC
    """)
    fun observeByFolderWithFlags(folderId: Long): Flow<List<MessageWithFlags>>

    @Query("""
        SELECT m.*, f.isEncrypted 
        FROM messages m 
        LEFT JOIN message_flags f ON m.accountId = f.accountId AND m.messageId = f.messageId
        JOIN folders fold ON m.folderId = fold.id
        WHERE fold.type IN ('Inbox', 'INBOX')
        ORDER BY m.date DESC
    """)
    fun observeUnifiedInboxWithFlags(): Flow<List<MessageWithFlags>>

    /**
     * Reactive, unbounded cross-account unified inbox: every message in a
     * folder of type INBOX, newest first, aggregated across all accounts via
     * a JOIN on `folders`. Same reactivity guarantees as [observeByFolder];
     * also intentionally uncapped for the same reason.
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "JOIN folders f ON m.folderId = f.id " +
            "WHERE f.type = 'Inbox' " +
            "ORDER BY m.date DESC"
    )
    fun observeUnifiedInbox(): Flow<List<MessageEntity>>

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

    @Query("SELECT COUNT(*) FROM messages WHERE folderId = :folderId")
    suspend fun countByFolder(folderId: Long): Int

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND threadId = :threadId ORDER BY date ASC")
    suspend fun getThreadOnce(accountId: Long, threadId: String): List<MessageEntity>

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)

    /**
     * Lightweight (local id, server uid) pairs for a folder, restricted to
     * rows that carry a real server uid (`uid > 0`). Backs sync's deletion
     * reconciliation: the uids are probed against the server and the ids of
     * any that no longer exist are passed to [deleteByIds]. POP3 rows (whose
     * uid is an unstable message number) are intentionally still included, but
     * the POP3 transport's no-op existence check means they're never dropped.
     */
    @Query("SELECT id, uid FROM messages WHERE folderId = :folderId AND uid > 0")
    suspend fun getUidRows(folderId: Long): List<MessageUidRow>

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
            "WHERE f.type = 'Inbox' AND m.isRead = 0"
    )
    fun observeTotalUnreadAcrossInboxes(): Flow<Int>
}

data class MessageWithFlags(
    @Embedded val message: MessageEntity,
    val isEncrypted: Boolean?
)

/** Projection of a cached message's local id and its server uid, for sync reconciliation. */
data class MessageUidRow(
    val id: Long,
    val uid: Long
)
