package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.FolderDao
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.data.local.dao.MessageFlagDao
import com.threemail.android.data.local.entity.MessageFlagEntity
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.data.remote.MailRemote
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.util.FtsUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MailRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val messageDao: MessageDao,
    private val messageFlagDao: MessageFlagDao
) {

    /**
     * Index of (`accountId`, `messageId`) -> `MessageFlagEntity` built
     * from a flag-list snapshot. Reactive flows call this per emission
     * and merge it into the toDomain mapper so a flag row written after
     * the message re-fetches reflects in the very next emission.
     *
     * Key collision is impossible: the (accountId, messageId) pair is
     * the composite primary key on [MessageFlagEntity] so each flag is
     * unique.
     */
    private fun flagsByMessageId(flags: List<MessageFlagEntity>): Map<Long, Map<String, MessageFlagEntity>> =
        flags.groupBy { it.accountId }
            .mapValues { entry -> entry.value.associateBy { it.messageId } }

    /**
     * Look up the `isEncrypted` flag synchronously for a single
     * one-shot read. Used by the suspend functions that don't have a
     * reactive context to combine against.
     */
    private suspend fun isEncryptedFor(accountId: Long, messageId: String): Boolean =
        messageFlagDao.getOne(accountId, messageId)?.isEncrypted ?: false

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
            // Map serverId -> user-dragged rank so the domain folder carries
            // its position through to the drawer. `favorites` arrives already
            // ordered by position (see FolderDao.getFavoritesByAccount), so the
            // index is the rank; a folder absent from the map is not a favorite
            // and keeps the MailFolder default of Int.MAX_VALUE.
            val positionByServerId = HashMap<String, Int>(favorites.size)
            favorites.forEachIndexed { index, favorite ->
                positionByServerId[favorite.serverId] = index
            }
            folders.map { entity ->
                val position = positionByServerId[entity.serverId]
                entity.toDomain(
                    isFavorite = position != null,
                    favoritePosition = position ?: Int.MAX_VALUE,
                    isHidden = entity.isHidden
                )
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
            if (existing != null) {
                val merged = folder.copy(
                    id = existing.id,
                    syncVersion = existing.syncVersion,
                    unreadCount = existing.unreadCount,
                    // Preserve the user's hide choice across folder re-syncs.
                    isHidden = existing.isHidden
                )
                // UPDATE the row in place - do NOT re-INSERT it. An
                // `@Insert(onConflict = REPLACE)` on an existing folder is a
                // DELETE-then-INSERT under the hood, and `messages.folderId`
                // carries `ON DELETE CASCADE`, so the REPLACE would silently
                // wipe every cached message in the folder. The worker only
                // deep-syncs INBOX/SENT/DRAFTS, so any other folder's messages
                // would never be re-fetched - and because the sync cursor is
                // preserved, even re-opening the folder fetches nothing new -
                // leaving a folder the user knows has mail looking permanently
                // empty. Updating by primary key leaves the row (and its
                // cascaded children) intact.
                folderDao.update(merged.toEntity())
                merged
            } else {
                // Brand-new folder: insert with id = 0 so Room autogenerates one.
                val id = folderDao.insert(folder.toEntity())
                folder.copy(id = id)
            }
        }
    }

    /**
     * Remove local folders that are no longer present in the server's folder
     * listing (deleted or renamed from another client), so a stale folder -
     * and its cached messages, which the per-message reconcile can't reach
     * because probing a vanished folder just fails - doesn't linger forever.
     *
     * [keepServerIds] is the set of serverIds from a SUCCESSFUL folder fetch;
     * the empty-set guard makes a failed/empty fetch a no-op rather than a
     * mass wipe. Returns the number of folders pruned. Called by the sync
     * worker right after [saveFolders].
     */
    suspend fun pruneFolders(accountId: Long, keepServerIds: Collection<String>): Int {
        if (keepServerIds.isEmpty()) return 0
        return folderDao.deleteFoldersNotIn(accountId, keepServerIds.toList())
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

    /**
     * Mirror a successful server-side folder rename/move into the local cache:
     * the folder takes [newServerId] / [newName], and each descendant shifts to
     * its rewritten path. Favourite and hidden state follow the path so a
     * renamed folder stays starred / hidden. Call only AFTER the remote op
     * succeeds - this method never touches the server.
     */
    suspend fun applyFolderRelocation(
        accountId: Long,
        oldServerId: String,
        newServerId: String,
        newName: String,
        descendantRewrites: List<Pair<String, String>>
    ) {
        folderDao.relocateFolder(accountId, oldServerId, newServerId, newName, descendantRewrites)
    }

    /**
     * Mirror a successful server-side folder delete into the local cache by
     * removing the folder and all its descendant rows (cached messages cascade
     * away with them). Call only AFTER the remote delete succeeds.
     */
    suspend fun applyFolderDeletion(accountId: Long, serverIds: List<String>) {
        folderDao.deleteFolderTree(accountId, serverIds)
    }

    suspend fun updateFolderCursor(folderId: Long, maxUid: Long) {
        folderDao.updateSyncVersion(folderId, maxUid)
    }

    /** Toggle a folder's drawer visibility (kept locally; not synced to the server). */
    suspend fun setFolderHidden(folderId: Long, isHidden: Boolean) {
        folderDao.setHidden(folderId, isHidden)
    }

    fun getMessages(folderId: Long): Flow<List<MailMessage>> =
        combine(messageDao.getByFolder(folderId), messageFlagDao.observeAll()) { list, flags ->
            val indexed = flagsByMessageId(flags)
            list.map { entity ->
                val flag = indexed[entity.accountId]?.get(entity.messageId)
                entity.toDomain(isEncrypted = flag?.isEncrypted ?: false)
            }
        }

    suspend fun getMessageById(id: Long): MailMessage? {
        val entity = messageDao.getById(id) ?: return null
        val isEncrypted = isEncryptedFor(entity.accountId, entity.messageId)
        return entity.toDomain(isEncrypted = isEncrypted)
    }

    suspend fun getMessagesOnce(folderId: Long): List<MailMessage> {
        val list = messageDao.getByFolderOnce(folderId)
        return list.map { entity ->
            entity.toDomain(isEncrypted = isEncryptedFor(entity.accountId, entity.messageId))
        }
    }

    /**
     * Resolve the message displayed immediately after the currently-open
     * one in the inbox list, so the message-detail VM can advance to it
     * after the user deletes the current message. Matches the inbox's
     * newest-first ordering (date DESC) and returns null when the
     * current one is the oldest in the folder, when it's missing from
     * the folder's snapshot, or when the folder is empty.
     *
     * O(n) over the folder; cheap for the typical synced-window size.
     */
    /**
     * Lightweight, reactive id-only feed for a single folder (or, when
     * [unified] is true, the cross-account unified inbox), newest-first.
     * Backs the swipe pager in the message-detail screen so the per-page
     * ViewModel only has to load the body of the message the user is
     * actually looking at. Returns an empty flow when neither scope is
     * supplied, so deep-link callers (Search / notifications) opt out
     * cleanly and end up with the non-pager detail view.
     */
    fun observeMessageIds(folderId: Long? = null, unified: Boolean = false): Flow<List<Long>> = when {
        unified -> messageDao.observeUnifiedInboxIds()
        folderId != null -> messageDao.observeIdsByFolder(folderId)
        else -> flowOf(emptyList())
    }

    suspend fun findNextMessageIdInFolder(folderId: Long, currentMessageId: Long): Long? {
        val messages = getMessagesOnce(folderId).sortedByDescending { it.date }
        val index = messages.indexOfFirst { it.id == currentMessageId }
        return if (index == -1 || index + 1 >= messages.size) null
        else messages[index + 1].id
    }

    fun getThread(accountId: Long, threadId: String): Flow<List<MailMessage>> =
        combine(messageDao.getByThread(accountId, threadId), messageFlagDao.observeAll()) { list, flags ->
            val indexed = flagsByMessageId(flags)
            list.map { entity ->
                val flag = indexed[entity.accountId]?.get(entity.messageId)
                entity.toDomain(isEncrypted = flag?.isEncrypted ?: false)
            }
        }

    /**
     * Reactive feed of a single folder. Room re-emits on every mutation so
     * the inbox reflects sync, swipe, and batch actions live. Intentionally
     * uncapped: the underlying DAO [MessageDao.observeByFolder] issues no
     * SQL LIMIT, so every message currently stored for the folder is in
     * the feed. Emit size grows with the folder's stored rows; the rendering
     * consumer handles large lists downstream.
     */
    fun observeFolder(folderId: Long): Flow<List<MailMessage>> =
        messageDao.observeByFolderWithFlags(folderId).map { list ->
            list.map { (entity, isEncrypted) ->
                entity.toDomain(isEncrypted = isEncrypted ?: false)
            }
        }

    /**
     * Reactive cross-account unified inbox (all INBOX folders). Uncapped, same
     * rationale as [observeFolder].
     */
    fun observeUnifiedInbox(): Flow<List<MailMessage>> =
        messageDao.observeUnifiedInboxWithFlags().map { list ->
            list.map { (entity, isEncrypted) ->
                entity.toDomain(isEncrypted = isEncrypted ?: false)
            }
        }

    suspend fun getMaxUid(folderId: Long): Long =
        messageDao.getMaxUid(folderId) ?: 0L

    /** Number of messages currently cached locally for [folderId]. */
    suspend fun getFolderMessageCount(folderId: Long): Int =
        messageDao.countByFolder(folderId)

    suspend fun saveMessages(messages: List<MailMessage>) {
        messageDao.insertAll(messages.map { it.toEntity() })
    }

    suspend fun updateBody(id: Long, bodyHtml: String?, bodyPlain: String?, bodyPreview: String, attachments: List<Attachment>) {
        messageDao.updateBody(id, bodyHtml, bodyPlain, bodyPreview, serializeAttachments(attachments))
    }

    suspend fun moveMessageToFolder(id: Long, folderId: Long) {
        messageDao.updateFolder(id, folderId)
    }

    /**
     * Undo an optimistic [moveMessageToFolder]: put the message back in
     * [folderId] and restore its original server [uid] (which is still valid
     * because the deferred server move was discarded). Keeps the reconcile
     * sweep and by-uid fetches working after an Undo.
     */
    suspend fun restoreMessageToFolder(id: Long, folderId: Long, uid: Long) {
        messageDao.restoreFolder(id, folderId, uid)
    }

    suspend fun deleteMessageLocal(id: Long) {
        messageDao.deleteById(id)
    }

    suspend fun deleteMessagesInFolder(folderId: Long) {
        messageDao.deleteByFolder(folderId)
    }

    /**
     * Server uids (uid > 0) currently cached for a folder. Sync probes these
     * against the server to find messages another client deleted.
     */
    suspend fun getCachedUids(folderId: Long): List<Long> =
        messageDao.getUidRows(folderId).map { it.uid }

    /**
     * Remove locally-cached messages in [folderId] whose uid is not in
     * [existingUids] (i.e. the server no longer has them). Returns the count
     * deleted.
     *
     * Callers MUST pass a set they successfully fetched from the server -
     * passing an empty set after a failed lookup would wipe the whole folder.
     * A message with no server uid (uid <= 0) is never touched.
     */
    suspend fun reconcileFolderDeletions(folderId: Long, existingUids: Set<Long>): Int {
        val staleIds = messageDao.getUidRows(folderId)
            .filter { it.uid !in existingUids }
            .map { it.id }
        if (staleIds.isNotEmpty()) messageDao.deleteByIds(staleIds)
        return staleIds.size
    }

    /**
     * Probe [folder]'s locally-cached uids against [remote] and drop any the
     * server no longer has (messages another client deleted). Returns the
     * number removed, or [Result.failure] if the server probe failed - the
     * mapping only deletes on SUCCESS, so a transient network error never
     * reads as "everything was deleted" and never wipes the cache. Gmail/POP3
     * fall back to the [MailRemote.listExistingMessageUids] no-op default and
     * are left untouched.
     *
     * Shared by [com.threemail.android.sync.MailSyncWorker] (periodic sync) and
     * the inbox's pull-to-refresh so BOTH sync entry points reconcile remote
     * deletions - not just the background worker.
     */
    suspend fun reconcileDeletions(remote: MailRemote, folder: MailFolder): Result<Int> {
        val cachedUids = getCachedUids(folder.id)
        if (cachedUids.isEmpty()) return Result.success(0)
        return remote.listExistingMessageUids(folder, cachedUids)
            .map { existing -> reconcileFolderDeletions(folder.id, existing) }
    }

    /**
     * Reconcile remote deletions across [folders] in one shot. Collects each
     * folder's cached uids, hands the non-empty ones to
     * [MailRemote.listExistingMessageUidsBatch] (IMAP probes them over a single
     * connection instead of reconnecting per folder), then prunes the missing
     * uids folder-by-folder. Returns the total number of messages removed.
     *
     * Only folders the remote actually reported back are touched: a folder it
     * omitted (probe failed) or that had nothing cached is left intact. A total
     * batch failure is a safe no-op (returns 0) so a dropped connection never
     * reads as "everything was deleted". Gmail/POP3 fall back to the default
     * batch, which reports every cached uid as still-existing (deletes nothing).
     */
    suspend fun reconcileDeletionsBatch(remote: MailRemote, folders: List<MailFolder>): Int {
        val folderUids = folders.associateWith { getCachedUids(it.id) }
            .filterValues { it.isNotEmpty() }
        if (folderUids.isEmpty()) return 0
        val existingByFolder = remote.listExistingMessageUidsBatch(folderUids).getOrElse { return 0 }
        var removed = 0
        for ((folder, existing) in existingByFolder) {
            removed += reconcileFolderDeletions(folder.id, existing)
        }
        return removed
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

    private fun FolderEntity.toDomain(
        isFavorite: Boolean = false,
        favoritePosition: Int = Int.MAX_VALUE,
        isHidden: Boolean = false
    ): MailFolder = MailFolder(
        id = id,
        accountId = accountId,
        serverId = serverId,
        name = name,
        type = type,
        messageCount = messageCount,
        unreadCount = unreadCount,
        syncVersion = syncVersion,
        isFavorite = isFavorite,
        favoritePosition = favoritePosition,
        isHidden = isHidden
    )

    private fun MailFolder.toEntity(): FolderEntity = FolderEntity(
        id = id,
        accountId = accountId,
        serverId = serverId,
        name = name,
        type = type,
        messageCount = messageCount,
        unreadCount = unreadCount,
        syncVersion = syncVersion,
        isHidden = isHidden
    )

    private fun MessageEntity.toDomain(isEncrypted: Boolean = false): MailMessage = MailMessage(
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
        syncedAt = syncedAt,
        isEncrypted = isEncrypted
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
