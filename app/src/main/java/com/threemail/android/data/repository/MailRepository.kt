package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.FolderDao
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.util.FtsUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MailRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val messageDao: MessageDao
) {

    /**
     * Folder list joined with the user's locally-tracked favorite set. Any
     * favorite toggle that hits `folder_favorites` causes `combine` to re-emit,
     * so the drawer's Favorites section and star icons update without a server
     * round-trip.
     */
    fun getFolders(accountId: Long): Flow<List<MailFolder>> =
        combine(
            folderDao.getByAccount(accountId),
            folderDao.getFavoritesByAccount(accountId)
        ) { folders, favorites ->
            val favoriteIds = favorites.mapTo(HashSet(folders.size)) { it.serverId }
            folders.map { entity ->
                entity.toDomain(isFavorite = entity.serverId in favoriteIds)
            }
        }

    suspend fun getFolderByServerId(accountId: Long, serverId: String): MailFolder? {
        val entity = folderDao.getByServerId(accountId, serverId) ?: return null
        // Read a one-shot snapshot so a single-row lookup doesn't silently
        // return isFavorite=false when the folder is actually starred.
        val favoriteIds = folderDao.getFavoritesByAccountOnce(accountId).mapTo(HashSet()) { it.serverId }
        return entity.toDomain(isFavorite = entity.serverId in favoriteIds)
    }

    suspend fun saveFolders(folders: List<MailFolder>): List<MailFolder> {
        // Preserve the id and sync cursor of folders we already know about, so
        // re-saving the folder list each sync does not reset incremental cursors.
        return folders.map { folder ->
            val existing = folderDao.getByServerId(folder.accountId, folder.serverId)
            val merged = folder.copy(
                id = existing?.id ?: 0,
                syncVersion = existing?.syncVersion ?: folder.syncVersion,
                unreadCount = existing?.unreadCount ?: folder.unreadCount
            )
            val id = folderDao.insert(merged.toEntity())
            merged.copy(id = id)
        }
    }

    suspend fun getFoldersOnce(accountId: Long): List<MailFolder> =
        folderDao.getByAccountOnce(accountId).map { entity ->
            // One-shot variant: isFavorite defaults to false here because the
            // existing call sites are non-reactive (e.g. message actions) and
            // have no need to surface the star state. Callers that need the
            // favorite flag should subscribe to getFolders() instead.
            entity.toDomain()
        }

    suspend fun getFolderById(id: Long): MailFolder? {
        val entity = folderDao.getById(id) ?: return null
        val favoriteIds = folderDao.getFavoritesByAccountOnce(entity.accountId)
            .mapTo(HashSet()) { it.serverId }
        return entity.toDomain(isFavorite = entity.serverId in favoriteIds)
    }

    /**
     * Toggle a folder's favorite state. INSERT OR IGNORE / DELETE keep the
     * side table consistent without races.
     *
     * When starring a fresh folder, the recipient slot is computed as
     * `MAX(position) + 1` over the current ranked list so newly-starred
     * folders append at the bottom of the user's pinned shortcut list
     * (i.e. position N when there are already N favourites). That matches
     * the user's mental model: "I just starred this - it should land at
     * the end, and I can drag it up from there if I want."
     *
     * INSERT OR IGNORE means: if a row for the same (accountId, serverId)
     * already exists (e.g. intended re-favorite with no position change),
     * the existing position is preserved. The repository never silently
     * renumbers an existing row.
     */
    suspend fun setFolderFavorite(accountId: Long, serverId: String, isFavorite: Boolean) {
        if (isFavorite) {
            val ranked = folderDao.getFavoritesByAccountOnce(accountId)
            val appendPosition = (ranked.maxOfOrNull { it.position } ?: -1) + 1
            folderDao.addFavorite(
                com.threemail.android.data.local.entity.FolderFavoriteEntity(
                    accountId = accountId,
                    serverId = serverId,
                    position = appendPosition
                )
            )
        } else {
            folderDao.removeFavorite(accountId, serverId)
        }
    }

    /**
     * Reassign positions for ALL favorites of one account in the order
     * supplied. Called by the drawer's drag-reorder UI on drop release;
     * the [androidx.room.Transaction] in
     * [com.threemail.android.data.local.dao.FolderDao.reorderFavorites]
     * ensures a partial reorder can't leave the on-disk list mid-shuffle.
     * ServerIds not in the favourites set are no-ops (per the DAO contract).
     */
    suspend fun reorderFavorites(accountId: Long, serverIds: List<String>) {
        folderDao.reorderFavorites(accountId, serverIds)
    }

    suspend fun updateFolderCursor(folderId: Long, maxUid: Long) {
        folderDao.updateSyncVersion(folderId, maxUid)
    }

    fun getMessages(folderId: Long): Flow<List<MailMessage>> =
        messageDao.getByFolder(folderId).map { list -> list.map { it.toDomain() } }

    suspend fun getMessageById(id: Long): MailMessage? =
        messageDao.getById(id)?.toDomain()

    suspend fun getMessagesOnce(folderId: Long): List<MailMessage> =
        messageDao.getByFolderOnce(folderId).map { it.toDomain() }

    fun getThread(accountId: Long, threadId: String): Flow<List<MailMessage>> =
        messageDao.getByThread(accountId, threadId).map { list -> list.map { it.toDomain() } }

    /**
     * Bounded paged folder fetch. Emits once on collection - not Room-reactive, so
     * new mail arriving in the folder won't auto-push to the UI. Re-selecting the
     * folder via InboxViewModel triggers a fresh snapshot. The inbox cap is
     * implemented on top of `MessageDao.getByFolderPaged` so the JVM-side query is
     * bounded; full paging with loadMore() can be layered on top without changing
     * this method.
     */
    fun getMessagesPaged(folderId: Long, limit: Int, offset: Int): Flow<List<MailMessage>> = flow {
        emit(messageDao.getByFolderPaged(folderId, limit, offset).map { it.toDomain() })
    }

    /**
     * Reactive, bounded feed of a single folder. Unlike [getMessagesPaged],
     * Room re-emits on every mutation so the inbox reflects sync, swipe, and
     * batch actions live.
     */
    fun observeFolder(folderId: Long, limit: Int): Flow<List<MailMessage>> =
        messageDao.observeByFolder(folderId, limit).map { list -> list.map { it.toDomain() } }

    /**
     * Reactive, bounded cross-account unified inbox (all INBOX folders).
     */
    fun observeUnifiedInbox(limit: Int): Flow<List<MailMessage>> =
        messageDao.observeUnifiedInbox(limit).map { list -> list.map { it.toDomain() } }

    suspend fun getMaxUid(folderId: Long): Long =
        messageDao.getMaxUid(folderId) ?: 0L

    suspend fun saveMessages(messages: List<MailMessage>) {
        messageDao.insertAll(messages.map { it.toEntity() })
    }

    suspend fun updateBody(id: Long, bodyHtml: String?, bodyPlain: String?, bodyPreview: String, attachments: List<Attachment>) {
        messageDao.updateBody(id, bodyHtml, bodyPlain, bodyPreview, serializeAttachments(attachments))
    }

    suspend fun moveMessageToFolder(id: Long, folderId: Long) {
        messageDao.updateFolder(id, folderId)
    }

    suspend fun deleteMessageLocal(id: Long) {
        messageDao.deleteById(id)
    }

    suspend fun deleteMessagesInFolder(folderId: Long) {
        messageDao.deleteByFolder(folderId)
    }

    suspend fun updateReadStatus(id: Long, isRead: Boolean) {
        messageDao.updateReadStatus(id, isRead)
    }

    suspend fun updateStarred(id: Long, isStarred: Boolean) {
        messageDao.updateStarred(id, isStarred)
    }

    fun searchMessages(query: String): Flow<List<MailMessage>> {
        val match = FtsUtil.sanitize(query)
        if (match.isEmpty()) return flowOf(emptyList())
        return messageDao.search(match).map { list -> list.map { it.toDomain() } }
    }

    private fun FolderEntity.toDomain(isFavorite: Boolean = false): MailFolder = MailFolder(
        id = id,
        accountId = accountId,
        serverId = serverId,
        name = name,
        type = type,
        messageCount = messageCount,
        unreadCount = unreadCount,
        syncVersion = syncVersion,
        isFavorite = isFavorite
    )

    private fun MailFolder.toEntity(): FolderEntity = FolderEntity(
        id = id,
        accountId = accountId,
        serverId = serverId,
        name = name,
        type = type,
        messageCount = messageCount,
        unreadCount = unreadCount,
        syncVersion = syncVersion
    )

    private fun MessageEntity.toDomain(): MailMessage = MailMessage(
        id = id,
        accountId = accountId,
        folderId = folderId,
        messageId = messageId,
        threadId = threadId,
        subject = subject,
        from = parseAddresses(fromJson),
        to = parseAddresses(toJson),
        cc = parseAddresses(ccJson),
        bcc = parseAddresses(bccJson),
        date = date,
        bodyPreview = bodyPreview,
        bodyHtml = bodyHtml,
        bodyPlain = bodyPlain,
        isRead = isRead,
        isStarred = isStarred,
        isDraft = isDraft,
        attachments = parseAttachments(attachmentsJson),
        uid = uid,
        remoteId = remoteId,
        syncedAt = syncedAt
    )

    private fun MailMessage.toEntity(): MessageEntity = MessageEntity(
        id = id,
        accountId = accountId,
        folderId = folderId,
        messageId = messageId,
        threadId = threadId,
        subject = subject,
        fromJson = serializeAddresses(from),
        toJson = serializeAddresses(to),
        ccJson = serializeAddresses(cc),
        bccJson = serializeAddresses(bcc),
        date = date,
        bodyPreview = bodyPreview,
        bodyHtml = bodyHtml,
        bodyPlain = bodyPlain,
        isRead = isRead,
        isStarred = isStarred,
        isDraft = isDraft,
        attachmentsJson = serializeAttachments(attachments),
        uid = uid,
        remoteId = remoteId,
        syncedAt = syncedAt
    )

    private fun serializeAddresses(addresses: List<EmailAddress>): String {
        val json = JSONArray()
        addresses.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("address", it.address)
            json.put(obj)
        }
        return json.toString()
    }

    private fun parseAddresses(json: String): List<EmailAddress> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map {
                val obj = array.getJSONObject(it)
                EmailAddress(
                    name = obj.optString("name", ""),
                    address = obj.optString("address", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeAttachments(attachments: List<Attachment>): String {
        val json = JSONArray()
        attachments.forEach {
            val obj = JSONObject()
            obj.put("fileName", it.fileName)
            obj.put("mimeType", it.mimeType)
            obj.put("size", it.size)
            obj.put("localPath", it.localPath)
            obj.put("remoteId", it.remoteId)
            json.put(obj)
        }
        return json.toString()
    }

    private fun parseAttachments(json: String): List<Attachment> {
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map {
                val obj = array.getJSONObject(it)
                Attachment(
                    fileName = obj.optString("fileName", ""),
                    mimeType = obj.optString("mimeType", ""),
                    size = obj.optLong("size", 0),
                    localPath = obj.optString("localPath").takeIf { it.isNotBlank() },
                    remoteId = obj.optString("remoteId").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
