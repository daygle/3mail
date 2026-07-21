package com.threemail.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.crypto.AutocryptLearner
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
    private val mailRemoteFactory: MailRemoteFactory,
    private val autocrLearner: AutocryptLearner
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
                    // Fetch from the stored cursor normally, but from the start
                    // when the folder's local cache is empty, so a stale cursor
                    // can't leave an empty folder stuck (it would only ask for
                    // messages newer than itself and get nothing back).
                    val countBefore = mailRepository.getFolderMessageCount(folder.id)
                    val cursor = if (countBefore == 0) 0L else folder.syncVersion
                    val fetch = remote.fetchMessages(folder, cursor, limit = 100).getOrElse {
                        Log.e(TAG, "Failed to fetch messages for folder ${folder.name}", it)
                        return@forEach
                    }

                    if (fetch.messages.isNotEmpty()) {
                        Log.d(TAG, "Fetched ${fetch.messages.size} new messages for folder ${folder.name}")
                        val toSave = fetch.messages.map { it.copy(folderId = folder.id) }
                        mailRepository.saveMessages(toSave)
                        // Only feed the aggregate new-mail notification when this
                        // account opts in; the global switch is applied once below.
                        //
                        // Count only genuinely-new unread mail, and never notify
                        // for the initial backfill of an empty inbox: adding an
                        // account (or a cache clear) fetches up to 100 existing
                        // messages at once, and the old `count { !isRead }` fired
                        // a notification for every one of them. `saveMessages`
                        // upserts on the unique (folder, account, messageId) index,
                        // so the row-count delta is exactly the number of newly
                        // inserted messages; cap the unread tally by that delta so
                        // re-fetched overlap at the cursor boundary isn't recounted.
                        if (folder.type == FolderType.Inbox &&
                            account.notificationsEnabled &&
                            countBefore > 0
                        ) {
                            val countAfter = mailRepository.getFolderMessageCount(folder.id)
                            val newlyInserted = (countAfter - countBefore).coerceAtLeast(0)
                            newMessages += minOf(toSave.count { !it.isRead }, newlyInserted)
                        }
                        // Autocrypt-key learner (RFC 8180). After the new
                        // messages are persisted to Room we ask the IMAP
                        // server for each new UID's full header set and
                        // parse any `Autocrypt` / `Autocrypt-Gossip`
                        // header. Resolved keydata is merged into
                        // Account.peerKeys so the next compose for these
                        // recipients skips the WKD round-trip. The learner
                        // is a no-op for Gmail / POP3 / passphrase-only
                        // MOVED folders; failures here are logged but
                        // never abort the sync - a missing key just means
                        // we fall back to WKD on the next compose.
                        if (folder.type == FolderType.Inbox) {
                            val newUids = toSave.mapNotNull { it.uid.takeIf { uid -> uid > 0L } }
                            if (newUids.isNotEmpty()) {
                                val outcome = autocrLearner
                                    .learnFrom(account, folder, newUids)
                                    .getOrElse { e ->
                                        Log.w(
                                            TAG,
                                            "Autocrypt learner pass failed for " +
                                                "${account.email}/${folder.serverId}: ${e.message}"
                                        )
                                        null
                                    }
                                if (outcome != null && outcome.newKeysLearned > 0) {
                                    Log.d(
                                        TAG,
                                        "Learned ${outcome.newKeysLearned} new peer key(s) " +
                                            "from ${outcome.uidsInspected} inspected uid(s) " +
                                            "(${account.email}/${folder.serverId})"
                                    )
                                }
                            }
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
        private val SYNCED_FOLDERS = setOf(FolderType.Inbox, FolderType.SENT, FolderType.DRAFTS)
    }
}
