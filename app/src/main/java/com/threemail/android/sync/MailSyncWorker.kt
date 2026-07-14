package com.threemail.android.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.AccountType
import com.threemail.android.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class MailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val notificationHelper: NotificationHelper,
    private val imapClientFactory: ImapClientFactory
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val accounts = accountRepository.getAccounts().first()
            var newMessages = 0

            accounts.forEach { account ->
                if (!account.syncEnabled) return@forEach

                if (account.accountType == AccountType.IMAP || account.accountType == AccountType.GMAIL) {
                    val client = imapClientFactory.create(account)
                    val foldersResult = client.fetchFolders()
                    foldersResult.getOrNull()?.let { remoteFolders ->
                        val savedFolders = mailRepository.saveFolders(remoteFolders)
                        savedFolders.forEach { folder ->
                            val messagesResult = client.fetchMessages(folder.serverId, limit = 25)
                            messagesResult.getOrNull()?.let { messages ->
                                val toSave = messages.map { it.copy(folderId = folder.id) }
                                mailRepository.saveMessages(toSave)
                                if (folder.type == com.threemail.android.domain.model.FolderType.INBOX) {
                                    newMessages += toSave.count { it.date > System.currentTimeMillis() - 24 * 60 * 60 * 1000 }
                                }
                            }
                        }
                    }
                }
            }

            if (newMessages > 0) {
                notificationHelper.showNewMailNotification(newMessages)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
