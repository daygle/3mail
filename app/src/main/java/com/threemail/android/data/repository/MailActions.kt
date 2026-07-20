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
 * sync. Message actions (read/delete/archive/move) go through [MailRemote] so
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
     * Multi-select move into a single target folder. Mirrors [markSpamBatch]:
     * local Room edits commit immediately so the rows disappear from the
     * current feed, while the IMAP/Gmail UID MOVE is deferred until the undo
     * window expires - one Undo tap reverts every local move back to its
     * source folder and discards the deferred server commits in one go.
     *
     * Silently skipped per message (no UI feedback) when:
     *  - the message's account doesn't match `target.accountId` (multi-account
     *    selection in unified mode; same-server IMAP MOVE can't span
     *    accounts). The InboxViewModel disables Move in unified mode so
     *    this is normally a no-op, but the guard is here for safety.
     *  - the source folder can't be resolved from the local cache.
     *  - the message is already in the target folder.
     *
     * If every selected message is skipped, no undo entry is enqueued and
     * no snackbar is shown.
     */
    suspend fun moveBatch(messages: Collection<MailMessage>, target: MailFolder) {
        if (messages.isEmpty()) return
        val moves = messages.mapNotNull { message ->
            if (message.accountId != target.accountId) return@mapNotNull null
            val folders = mailRepository.getFoldersOnce(message.accountId)
            val source = folders.firstOrNull { it.id == message.folderId } ?: return@mapNotNull null
            if (source.id == target.id) return@mapNotNull null
            mailRepository.moveMessageToFolder(message.id, target.id)
            Triple(message, source, target)
        }
        if (moves.isEmpty()) return
        // Resolve accounts eagerly so the deferred commit closure still has
        // them even after the originating screen has been torn down.
        val accounts = moves.associate { (msg, _, _) ->
            msg.id to accountRepository.getAccountById(msg.accountId)
        }
        undoController.enqueue(
            kind = UndoKind.MOVE,
            commit = {
                for ((msg, source, tgt) in moves) {
                    val account = accounts[msg.id] ?: continue
                    runCatching {
                        mailRemoteFactory.create(account).move(source, msg, tgt).getOrThrow()
                    }
                }
            },
            revert = {
                for ((msg, source, _) in moves) {
                    runCatching {
                        mailRepository.moveMessageToFolder(msg.id, source.id)
                    }
                }
            }
        )
    }

    /**
     * Move a single message to its account's Spam/Junk folder immediately
     * (server-first, no UndoController wiring). Mirrors [archive] / [delete] -
     * used by [markSpamBatch] so a multi-select mark-as-spam completes
     * once-through, matching the existing archiveBatch / deleteBatch
     * convention. Silently no-ops when the account has no Spam folder.
     */
    suspend fun markSpam(message: MailMessage): Result<Unit> {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val spam = folders.firstOrNull { it.type == FolderType.SPAM }
            ?: return Result.success(Unit)
        if (source?.id == spam.id) return Result.success(Unit) // already in spam
        mailRepository.moveMessageToFolder(message.id, spam.id)
        return remote(message, source) { remote, folder -> remote.move(folder, message, spam) }
    }

    /**
     * Single-message spam move that surfaces an Undo snackbar in the inbox.
     * Mirrors [archiveWithUndo] / [deleteWithUndo]. Used by the message-detail
     * screen's MoreVert overflow so the same action triggered from a single
     * message keeps the existing single-action undo flow.
     */
    suspend fun markSpamWithUndo(message: MailMessage) {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val spam = folders.firstOrNull { it.type == FolderType.SPAM } ?: return
        if (source?.id == spam.id) return // already in spam, no undo either
        mailRepository.moveMessageToFolder(message.id, spam.id)
        val account = accountRepository.getAccountById(message.accountId)
        undoController.enqueue(
            kind = UndoKind.SPAM,
            commit = { if (source != null) account?.let { mailRemoteFactory.create(it).move(source, message, spam) } },
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

    suspend fun deleteBatch(messages: Collection<MailMessage>) {
        messages.forEach { runCatching { delete(it) } }
    }

    suspend fun archiveBatch(messages: Collection<MailMessage>) {
        messages.forEach { runCatching { archive(it) } }
    }

    /**
     * Multi-select spam with a single composite undo entry. Local moves commit
     * immediately so the rows disappear from the inbox, but the server-side
     * moves are deferred until the undo window expires - a single tap on the
     * snackbar's Undo action reverts every local move back to its source
     * folder and discards the deferred server commits in one go.
     *
     * Messages are silently skipped when the source account lacks a Spam
     * folder, or when the message is already in Spam; those don't get a
     * local move and so don't participate in the composite undo entry. If
     * every message gets skipped (e.g. all selections are already in Spam),
     * no undo entry is enqueued and no snackbar is shown.
     */
    suspend fun markSpamBatch(messages: Collection<MailMessage>) {
        if (messages.isEmpty()) return
        val moves = messages.mapNotNull { message ->
            val folders = mailRepository.getFoldersOnce(message.accountId)
            val source = folders.firstOrNull { it.id == message.folderId } ?: return@mapNotNull null
            val spam = folders.firstOrNull { it.type == FolderType.SPAM } ?: return@mapNotNull null
            if (source.id == spam.id) return@mapNotNull null
            mailRepository.moveMessageToFolder(message.id, spam.id)
            Triple(message, source, spam)
        }
        if (moves.isEmpty()) return
        // Resolve accounts eagerly so the deferred commit closure still has
        // them even after the originating screen has been torn down.
        val accounts = moves.associate { (msg, _, _) ->
            msg.id to accountRepository.getAccountById(msg.accountId)
        }
        undoController.enqueue(
            kind = UndoKind.SPAM_BATCH,
            commit = {
                for ((msg, source, target) in moves) {
                    val account = accounts[msg.id] ?: continue
                    runCatching {
                        mailRemoteFactory.create(account).move(source, msg, target).getOrThrow()
                    }
                }
            },
            revert = {
                for ((msg, source, _) in moves) {
                    runCatching {
                        mailRepository.moveMessageToFolder(msg.id, source.id)
                    }
                }
            }
        )
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
