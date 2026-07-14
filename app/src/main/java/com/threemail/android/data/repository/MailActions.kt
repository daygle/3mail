package com.threemail.android.data.repository

import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates message mutations so that local state and the remote server stay in
 * sync. The local database is updated optimistically; the server call is best
 * effort and reported back via [Result]. Works against [MailRemote] so IMAP and
 * Gmail accounts are handled uniformly.
 */
@Singleton
class MailActions @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val mailRemoteFactory: MailRemoteFactory
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

    suspend fun move(message: MailMessage, target: MailFolder): Result<Unit> {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        mailRepository.moveMessageToFolder(message.id, target.id)
        return remote(message, source) { remote, folder -> remote.move(folder, message, target) }
    }

    /** Permanently deletes every message in each account's Trash folder. */
    suspend fun emptyTrash(): Result<Unit> = runCatching {
        accountRepository.getAccounts().first().forEach { account ->
            val folders = mailRepository.getFoldersOnce(account.id)
            val trash = folders.firstOrNull { it.type == FolderType.TRASH } ?: return@forEach
            val remote = mailRemoteFactory.create(account)
            mailRepository.getMessagesOnce(trash.id).forEach { message ->
                // Permanent delete (trash = null) removes it rather than re-trashing.
                remote.delete(trash, message, null)
                mailRepository.deleteMessageLocal(message.id)
            }
        }
    }

    private suspend fun remote(
        message: MailMessage,
        sourceFolder: MailFolder? = null,
        block: suspend (MailRemote, MailFolder) -> Result<Unit>
    ): Result<Unit> {
        val account = accountRepository.getAccountById(message.accountId) ?: return Result.success(Unit)
        val folder = sourceFolder ?: mailRepository.getFolderById(message.folderId) ?: return Result.success(Unit)
        val remote = mailRemoteFactory.create(account)
        return block(remote, folder)
    }
}
