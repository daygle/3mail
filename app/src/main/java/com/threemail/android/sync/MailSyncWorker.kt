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
import com.threemail.android.domain.model.MailMessage
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
            val newMessagesToNotify = mutableListOf<MailMessage>()

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
                    // Deep-sync the core folders, plus any folder the user opted
                    // into IMAP push for (so an IDLE hit on it actually fetches
                    // and can notify).
                    if (folder.type !in SYNCED_FOLDERS &&
                        folder.serverId !in account.pushFolders
                    ) return@forEach

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
                        // Notify for genuinely-new unread mail only, and never for
                        // the initial backfill of an empty inbox (adding an account
                        // or a cache clear fetches up to 100 existing messages at
                        // once). We snapshot the folder's known server-ids before
                        // the upsert; the messages whose messageId wasn't present
                        // and that arrive unread are the ones worth a notification.
                        val isNotifyFolder = folder.type == FolderType.Inbox ||
                            folder.serverId in account.pushFolders
                        val shouldNotify = isNotifyFolder &&
                            account.notificationsEnabled &&
                            countBefore > 0
                        val existingIds: Set<String> = if (shouldNotify) {
                            mailRepository.getMessagesOnce(folder.id).mapTo(HashSet<String>()) { it.messageId }
                        } else {
                            emptySet()
                        }
                        mailRepository.saveMessages(toSave)
                        if (shouldNotify) {
                            val fresh = mailRepository.getMessagesOnce(folder.id)
                                .filter { it.messageId !in existingIds && !it.isRead }
                            newMessagesToNotify += fresh
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

                    // Propagate server-side deletions: any message another
                    // client expunged should disappear here too. Incremental
                    // fetch only ever ADDS (it asks for uids above the cursor),
                    // so without this a message deleted elsewhere lingered
                    // forever. The shared helper probes the exact set of cached
                    // uids against the server and only deletes when the probe
                    // SUCCEEDS, so a network failure is never read as "everything
                    // was deleted"; Gmail/POP3 fall back to the no-op default.
                    mailRepository.reconcileDeletions(remote, folder)
                        .onSuccess { removed ->
                            if (removed > 0) {
                                Log.d(TAG, "Removed $removed remotely-deleted message(s) from ${folder.name}")
                            }
                        }
                        .onFailure {
                            Log.w(TAG, "Deletion reconcile skipped for ${folder.name}: ${it.message}")
                        }
                }
            }

            if (newMessagesToNotify.isNotEmpty() && notificationsEnabled) {
                notificationHelper.showNewMailNotifications(newMessagesToNotify)
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
