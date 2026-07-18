package com.threemail.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.notifications.NotificationHelper
import kotlinx.coroutines.flow.first

/**
 * Manually constructed by [ThreeMailWorkerFactory]. See that class
 * doc for the rationale (androidx.hilt 1.4.0 silently skips
 * generating @HiltWorker AssistedFactory bindings under KSP2).
 */
class MailSyncWorker(
    context: Context,
    params: WorkerParameters,
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
            Log.d(TAG, "Starting sync for ${accounts.size} accounts")
            val notificationsEnabled = settingsRepository.settings.first().notificationsEnabled
            var newMessages = 0

            accounts.forEach { account ->
                if (!account.syncEnabled) {
                    Log.d(TAG, "Sync disabled for ${account.email}")
                    return@forEach
                }
                // IMAP, Gmail, and POP3 all sync mail.
                if (account.accountType != AccountType.IMAP &&
                    account.accountType != AccountType.GMAIL &&
                    account.accountType != AccountType.POP3
                ) return@forEach

                Log.d(TAG, "Syncing account: ${account.email}")
                val remote = mailRemoteFactory.create(account)
                val remoteFolders = remote.fetchFolders().getOrElse {
                    Log.e(TAG, "Failed to fetch folders for ${account.email}", it)
                    return@forEach
                }
                Log.d(TAG, "Fetched ${remoteFolders.size} folders for ${account.email}")
                val savedFolders = mailRepository.saveFolders(remoteFolders)

                savedFolders.forEach { folder ->
                    // Only deep-sync the folders users care about most.
                    if (folder.type !in SYNCED_FOLDERS) return@forEach

                    Log.d(TAG, "Syncing folder: ${folder.name} (${folder.serverId}) [Type: ${folder.type}]")
                    val fetch = remote.fetchMessages(folder, folder.syncVersion, limit = 100).getOrElse {
                        Log.e(TAG, "Failed to fetch messages for folder ${folder.name}", it)
                        return@forEach
                    }

                    if (fetch.messages.isNotEmpty()) {
                        Log.d(TAG, "Fetched ${fetch.messages.size} new messages for folder ${folder.name}")
                        val toSave = fetch.messages.map { it.copy(folderId = folder.id) }
                        mailRepository.saveMessages(toSave)
                        // Only feed the aggregate new-mail notification when this
                        // account opts in; the global switch is applied once below.
                        if (folder.type == FolderType.INBOX && account.notificationsEnabled) {
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
            Log.e(TAG, "MailSyncWorker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MailSyncWorker"
        private val SYNCED_FOLDERS = setOf(FolderType.INBOX, FolderType.SENT, FolderType.DRAFTS)
    }
}
