package com.threemail.android.data.repository

import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates message mutations so that local state and the remote server stay in
 * sync. Message actions (read/star/delete/archive/move) go through [MailRemote] so
 * IMAP and Gmail accounts are handled by their native transport. Emptying trash
 * uses the IMAP expunge path directly (efficient bulk delete).
 */
@Singleton
class MailActions @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val mailRemoteFactory: MailRemoteFactory,
    private val imapClientFactory: ImapClientFactory,
    private val undoController: UndoController
) {

    suspend fun setRead(message: MailMessage, isRead: Boolean): Result<Unit> {
        mailRepository.updateReadStatus(message.id, isRead)
        return remote(message) { remote, folder -> remote.setSeen(folder, message, isRead) }
    }

    suspend fun setStarred(message: MailMessage, isStarred: Boolean): Result<Unit> {
        mailRepository.updateStarred(message.id, isStarred)
        return remote(message) { remote, folder -> remote.setFlagged(folder, message, isStarred) }
    }

    suspend fun delete(message: MailMessage): Result<Unit> {
        mailRepository.deleteMessageLocal(message.id)
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val trash = folders.firstOrNull { it.type == FolderType.TRASH }
        return remote(message, source) { remote, folder -> remote.delete(folder, message, trash) }
    }

    suspend fun archive(message: MailMessage): Result<Unit> {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val archive = folders.firstOrNull { it.type == FolderType.ARCHIVE || it.type == FolderType.ALL_MAIL }
            ?: return delete(message)
        mailRepository.moveMessageToFolder(message.id, archive.id)
        return remote(message, source) { remote, folder -> remote.move(folder, message, archive) }
    }

    /**
     * Undo-aware triage: apply the folder move locally now (so the row leaves
     * the list immediately), then defer the server operation through
     * [UndoController]. If the user taps Undo within the window, only the local
     * move is reverted and the server is never touched; otherwise the server op
     * commits after the window. See [UndoController] for why deferral matters on
     * IMAP.
     *
     * The [message] snapshot captured here still points at its original folder /
     * UID, which is exactly what the deferred server op needs.
     */
    suspend fun archiveWithUndo(message: MailMessage) {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val archive = folders.firstOrNull { it.type == FolderType.ARCHIVE || it.type == FolderType.ALL_MAIL }
        if (source == null || archive == null) {
            deleteWithUndo(message)
            return
        }
        mailRepository.moveMessageToFolder(message.id, archive.id)
        val account = accountRepository.getAccountById(message.accountId)
        undoController.enqueue(
            kind = UndoKind.ARCHIVE,
            commit = { account?.let { mailRemoteFactory.create(it).move(source, message, archive) } },
            revert = { mailRepository.moveMessageToFolder(message.id, source.id) }
        )
    }

    suspend fun deleteWithUndo(message: MailMessage) {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val trash = folders.firstOrNull { it.type == FolderType.TRASH }
        if (source == null || trash == null || trash.id == message.folderId) {
            // Deleting from Trash, or no Trash folder (e.g. POP3): permanent,
            // nothing to undo, so commit immediately.
            delete(message)
            return
        }
        mailRepository.moveMessageToFolder(message.id, trash.id)
        val account = accountRepository.getAccountById(message.accountId)
        undoController.enqueue(
            kind = UndoKind.DELETE,
            commit = { account?.let { mailRemoteFactory.create(it).delete(source, message, trash) } },
            revert = { mailRepository.moveMessageToFolder(message.id, source.id) }
        )
    }

    suspend fun moveWithUndo(message: MailMessage, target: MailFolder, spam: Boolean = false) {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        mailRepository.moveMessageToFolder(message.id, target.id)
        val account = accountRepository.getAccountById(message.accountId)
        undoController.enqueue(
            kind = if (spam) UndoKind.SPAM else UndoKind.MOVE,
            commit = { if (source != null) account?.let { mailRemoteFactory.create(it).move(source, message, target) } },
            revert = { source?.let { mailRepository.moveMessageToFolder(message.id, it.id) } }
        )
    }

    /**
     * Batch variants for multi-select triage. Each delegates to the
     * per-message path so local Room mutation and the provider-native remote
     * call (IMAP/Gmail) stay identical to the single-message actions. Failures
     * are swallowed per message so one bad row doesn't abort the rest of the
     * selection; callers that need per-item status should fall back to the
     * single-message methods.
     */
    suspend fun setReadBatch(messages: Collection<MailMessage>, isRead: Boolean) {
        messages.forEach { runCatching { setRead(it, isRead) } }
    }

    suspend fun setStarredBatch(messages: Collection<MailMessage>, isStarred: Boolean) {
        messages.forEach { runCatching { setStarred(it, isStarred) } }
    }

    suspend fun deleteBatch(messages: Collection<MailMessage>) {
        messages.forEach { runCatching { delete(it) } }
    }

    suspend fun archiveBatch(messages: Collection<MailMessage>) {
        messages.forEach { runCatching { archive(it) } }
    }

    suspend fun move(message: MailMessage, target: MailFolder): Result<Unit> {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        mailRepository.moveMessageToFolder(message.id, target.id)
        return remote(message, source) { remote, folder -> remote.move(folder, message, target) }
    }

    /**
     * Permanently empties the trash folder for [account].
     *
     * Server-first: every message is flagged `DELETED` and the folder is expunged;
     * only after the server reports success do we drop the cached rows from Room, so
     * a failed cleanup leaves the local cache intact for the next retry. Returns the
     * number of expunged messages.
     */
    suspend fun emptyTrash(account: Account, trashFolder: MailFolder): Result<Int> =
        withContext(Dispatchers.IO) {
            if (account.accountType != AccountType.GMAIL && account.accountType != AccountType.IMAP) {
                return@withContext Result.success(0)
            }
            try {
                val client = imapClientFactory.create(account)
                val expunged = client.emptyTrashFolder(trashFolder.serverId).getOrElse { failure ->
                    return@withContext Result.failure(failure)
                }
                mailRepository.deleteMessagesInFolder(trashFolder.id)
                Result.success(expunged)
            } catch (e: RecoverableAuthException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun remote(
        message: MailMessage,
        sourceFolder: MailFolder? = null,
        block: suspend (MailRemote, MailFolder) -> Result<Unit>
    ): Result<Unit> {
        val account = accountRepository.getAccountById(message.accountId)
            ?: return Result.success(Unit)
        val folder = sourceFolder ?: mailRepository.getFolderById(message.folderId)
            ?: return Result.success(Unit)
        val remote = mailRemoteFactory.create(account)
        return block(remote, folder)
    }
}
