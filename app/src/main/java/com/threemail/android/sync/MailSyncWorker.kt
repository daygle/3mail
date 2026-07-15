package com.threemail.android.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
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
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper,
    private val mailRemoteFactory: MailRemoteFactory
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val targetAccountId = inputData.getLong(SyncScheduler.KEY_ACCOUNT_ID, -1L).takeIf { it > 0 }
            val allAccounts = accountRepository.getAccounts().first()
            val accounts = if (targetAccountId != null) {
                allAccounts.filter { it.id == targetAccountId }
            } else {
                allAccounts
            }
            val notificationsEnabled = settingsRepository.settings.first().notificationsEnabled
            var newMessages = 0

            accounts.forEach { account ->
                if (!account.syncEnabled) return@forEach
                if (account.accountType != AccountType.IMAP && account.accountType != AccountType.GMAIL) return@forEach

                val remote = mailRemoteFactory.create(account)
                val remoteFolders = remote.fetchFolders().getOrNull() ?: return@forEach
                val savedFolders = mailRepository.saveFolders(remoteFolders)

                savedFolders.forEach { folder ->
                    // Only deep-sync the folders users care about most.
                    if (folder.type !in SYNCED_FOLDERS) return@forEach

                    val fetch = remote.fetchMessages(folder, folder.syncVersion, limit = 100).getOrNull()
                        ?: return@forEach

                    if (fetch.messages.isNotEmpty()) {
                        val toSave = fetch.messages.map { it.copy(folderId = folder.id) }
                        mailRepository.saveMessages(toSave)
                        if (folder.type == FolderType.INBOX) {
                            newMessages += toSave.count { !it.isRead }
                        }
                    }
                    mailRepository.updateFolderCursor(folder.id, fetch.nextCursor)
                }
            }

            if (newMessages > 0 && notificationsEnabled) {
                notificationHelper.showNewMailNotification(newMessages)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val SYNCED_FOLDERS = setOf(FolderType.INBOX, FolderType.SENT, FolderType.DRAFTS)
    }
}
