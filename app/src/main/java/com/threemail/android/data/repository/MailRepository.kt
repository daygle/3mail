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

    fun getFolders(accountId: Long): Flow<List<MailFolder>> =
        folderDao.getByAccount(accountId).map { list -> list.map { it.toDomain() } }

    suspend fun getFolderByServerId(accountId: Long, serverId: String): MailFolder? =
        folderDao.getByServerId(accountId, serverId)?.toDomain()

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
        folderDao.getByAccountOnce(accountId).map { it.toDomain() }

    suspend fun getFolderById(id: Long): MailFolder? =
        folderDao.getById(id)?.toDomain()

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

    private fun FolderEntity.toDomain(): MailFolder = MailFolder(
        id = id,
        accountId = accountId,
        serverId = serverId,
        name = name,
        type = type,
        messageCount = messageCount,
        unreadCount = unreadCount,
        syncVersion = syncVersion
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
