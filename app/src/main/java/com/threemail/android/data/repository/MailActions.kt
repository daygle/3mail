package com.threemail.android.data.repository

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
 * sync. The local database is updated optimistically; the server call is best
 * effort and reported back via [Result].
 */
@Singleton
class MailActions @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val imapClientFactory: ImapClientFactory
) {

    suspend fun setRead(message: MailMessage, isRead: Boolean): Result<Unit> {
        mailRepository.updateReadStatus(message.id, isRead)
        return remote(message) { client, serverId ->
            client.setSeen(serverId, message.uid, isRead)
        }
    }

    suspend fun setStarred(message: MailMessage, isStarred: Boolean): Result<Unit> {
        mailRepository.updateStarred(message.id, isStarred)
        return remote(message) { client, serverId ->
            client.setFlagged(serverId, message.uid, isStarred)
        }
    }

    suspend fun delete(message: MailMessage): Result<Unit> {
        mailRepository.deleteMessageLocal(message.id)
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val trash = folders.firstOrNull { it.type == FolderType.TRASH }
        return remote(message, source) { client, serverId ->
            client.deleteMessage(serverId, message.uid, trash?.serverId)
        }
    }

    suspend fun archive(message: MailMessage): Result<Unit> {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        val archive = folders.firstOrNull { it.type == FolderType.ARCHIVE || it.type == FolderType.ALL_MAIL }
            ?: return delete(message)
        mailRepository.moveMessageToFolder(message.id, archive.id)
        return remote(message, source) { client, serverId ->
            client.moveMessage(serverId, message.uid, archive.serverId)
        }
    }

    suspend fun move(message: MailMessage, target: MailFolder): Result<Unit> {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        val source = folders.firstOrNull { it.id == message.folderId }
        mailRepository.moveMessageToFolder(message.id, target.id)
        return remote(message, source) { client, serverId ->
            client.moveMessage(serverId, message.uid, target.serverId)
        }
    }

    /**
     * Permanently empties the trash folder for [account].
     *
     * Server-first, then local per the trash policy: the IMAP `DELETE` flag is set
     * on every message and the folder is expunged. Only after the server reports
     * success do we drop the cached rows from Room. If the server-side step fails
     * we return the error and intentionally leave the local cache intact so it can
     * be retried on the next cleanup trigger.
     */
    suspend fun emptyTrash(account: Account, trashFolder: MailFolder): Result<Int> =
        withContext(Dispatchers.IO) {
            if (account.accountType != AccountType.GMAIL && account.accountType != AccountType.IMAP) {
                return@withContext Result.success(0)
            }
            try {
                val client = imapClientFactory.create(account)
                val serverResult = client.emptyTrashFolder(trashFolder.serverId)
                val expunged = serverResult.getOrElse { failure ->
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
        block: suspend (com.threemail.android.data.remote.imap.ImapClient, String) -> Result<Unit>
    ): Result<Unit> {
        val account = accountRepository.getAccountById(message.accountId) ?: return Result.success(Unit)
        val folder = sourceFolder ?: mailRepository.getFolderById(message.folderId)
        val serverId = folder?.serverId ?: return Result.success(Unit)
        val client = imapClientFactory.create(account)
        return block(client, serverId)
    }
}
