package com.threemail.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * `open` (class + the enqueue/schedule methods) so unit tests can subclass it
 * with no-op overrides and avoid touching the real WorkManager singleton, which
 * isn't initialised under Robolectric. Mirrors the same test-seam pattern used
 * by [com.threemail.android.data.repository.AccountRepository]. Production always
 * gets the Hilt-wired concrete class.
 */
open class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    open fun schedulePeriodicSync(intervalMinutes: Long = 15, replace: Boolean = false) {
        val interval = intervalMinutes.coerceAtLeast(15) // WorkManager minimum period.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<MailSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Reconciles the set of per-account periodic mail syncs against the current
     * account list and the app-wide default cadence.
     *
     * Each syncable account gets its own unique periodic worker
     * (`$ACCOUNT_SYNC_PREFIX<id>`) whose period is the account's per-account
     * override when set (`syncIntervalMinutes > 0`) or [defaultIntervalMinutes]
     * otherwise. Accounts that are paused (`syncEnabled == false`) or that don't
     * sync mail (e.g. a future non-IMAP/Gmail type) have their worker cancelled.
     *
     * The legacy single all-accounts worker ([SYNC_WORK_NAME]) is retired here:
     * per-account workers fully replace it, so leaving it running would just
     * double-sync every account.
     */
    open fun reconcileAccountSyncs(accounts: List<Account>, defaultIntervalMinutes: Long = 15) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        accounts.forEach { account ->
            val syncsMail = account.accountType == AccountType.IMAP ||
                account.accountType == AccountType.GMAIL
            if (account.syncEnabled && syncsMail) {
                val effective = account.syncIntervalMinutes.takeIf { it > 0 } ?: defaultIntervalMinutes
                schedulePeriodicSyncForAccount(account.id, effective)
            } else {
                cancelPeriodicSyncForAccount(account.id)
            }
        }
    }

    /**
     * Schedules (or updates) the dedicated periodic mail sync for a single
     * account. The worker is handed the account id as input so [MailSyncWorker]
     * only touches that account, and [ExistingPeriodicWorkPolicy.UPDATE] lets a
     * cadence change take effect without dropping the existing schedule.
     */
    open fun schedulePeriodicSyncForAccount(
        accountId: Long,
        intervalMinutes: Long,
        replace: Boolean = true
    ) {
        val interval = intervalMinutes.coerceAtLeast(15) // WorkManager minimum period.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<MailSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "$ACCOUNT_SYNC_PREFIX$accountId",
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    open fun cancelPeriodicSyncForAccount(accountId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("$ACCOUNT_SYNC_PREFIX$accountId")
    }

    fun schedulePeriodicCalendarSync(intervalMinutes: Long = 30, replace: Boolean = false) {
        val interval = intervalMinutes.coerceAtLeast(15)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CALENDAR_SYNC_WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Schedules a single TrashCleanupWorker for the given trigger (open / close).
     * ExistingWorkPolicy.REPLACE means a new occurrence replaces the in-flight one
     * so we never queue duplicate cleanups when the app is bounced repeatedly.
     */
    fun enqueueTrashCleanup(trigger: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TrashCleanupWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to trigger))
            .build()

        val uniqueName = when (trigger) {
            TrashCleanupWorker.TRIGGER_LAUNCH -> TRASH_LAUNCH_WORK_NAME
            TrashCleanupWorker.TRIGGER_QUIT -> TRASH_QUIT_WORK_NAME
            else -> "${TRASH_LAUNCH_WORK_NAME}_$trigger"
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Enqueues a [SendMailWorker] to drain the outbox. Called after Compose
     * queues a message.
     *
     * Uses [ExistingWorkPolicy.APPEND] so a new message always gets its own
     * drain after any in-flight send completes, rather than being stranded if a
     * send is already running (which [ExistingWorkPolicy.KEEP] would allow).
     * The worker never returns [androidx.work.ListenableWorker.Result.failure]
     * - only success or retry - so APPEND never cascades a cancellation onto
     * the appended work. A network constraint + backoff makes a failed send
     * retry once connectivity returns.
     */
    fun enqueueSendMail() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SendMailWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SEND_MAIL_WORK_NAME,
            ExistingWorkPolicy.APPEND,
            request
        )
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(CALENDAR_SYNC_WORK_NAME)
    }

    /**
     * Enqueues a [MailSyncWorker] that targets a single account. Used by the
     * IMAP IDLE push service when a new-mail notification arrives so the UI
     * updates without waiting for the next periodic tick.
     *
     * Uses [ExistingWorkPolicy.REPLACE] keyed by account ID so two consecutive
     * push events from the same account collapse - we don't need to spam the
     * server with redundant refreshes.
     */
    open fun enqueueImmediateSync(accountId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MailSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$IMMEDIATE_SYNC_PREFIX$accountId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        private const val SYNC_WORK_NAME = "threemail_periodic_sync"
        private const val CALENDAR_SYNC_WORK_NAME = "threemail_calendar_periodic_sync"
        private const val TRASH_LAUNCH_WORK_NAME = "threemail_trash_cleanup_launch"
        private const val TRASH_QUIT_WORK_NAME = "threemail_trash_cleanup_quit"
        private const val IMMEDIATE_SYNC_PREFIX = "threemail_immediate_sync_"
        private const val ACCOUNT_SYNC_PREFIX = "threemail_account_sync_"
        private const val SEND_MAIL_WORK_NAME = "threemail_send_mail"
        const val KEY_TRIGGER: String = "triggerKey"
        const val KEY_ACCOUNT_ID: String = "accountId"
    }
}
